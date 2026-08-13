@file:Suppress("MatchingDeclarationName")

package zone.clanker.gradle.srcx.analysis

/**
 * An edge in the class dependency graph.
 *
 * @property from the component that depends on [to]
 * @property to the component being depended on
 */
data class ClassDependency(
    val from: ClassifiedComponent,
    val to: ClassifiedComponent,
)

/**
 * Build a dependency graph between classified components using import analysis.
 * Only includes edges between project source files (ignores external imports).
 */
fun buildDependencyGraph(components: List<ClassifiedComponent>): List<ClassDependency> {
    val bySimpleName = components.groupBy { it.source.simpleName }
    val byQualifiedName =
        components
            .groupBy { it.source.qualifiedName }
            .mapNotNull { (qualifiedName, candidates) ->
                candidates.singleOrNull()?.let { component -> qualifiedName to component }
            }.toMap()
    val unambiguousQualifiedNames = byQualifiedName.keys

    val edges = mutableListOf<ClassDependency>()

    for (component in components) {
        if (component.source.qualifiedName !in unambiguousQualifiedNames) continue
        addImportEdges(component, byQualifiedName, edges)
        addSupertypeEdges(component, byQualifiedName, bySimpleName, edges)
        addSamePackageEdges(component, components, unambiguousQualifiedNames, edges)
    }

    return edges.distinct()
}

private fun addImportEdges(
    component: ClassifiedComponent,
    byQualifiedName: Map<String, ClassifiedComponent>,
    edges: MutableList<ClassDependency>,
) {
    for (imp in component.source.imports) {
        val target = byQualifiedName[imp]
        if (target != null && target !== component) {
            edges.add(ClassDependency(component, target))
        }
    }
}

private fun addSupertypeEdges(
    component: ClassifiedComponent,
    byQualifiedName: Map<String, ClassifiedComponent>,
    bySimpleName: Map<String, List<ClassifiedComponent>>,
    edges: MutableList<ClassDependency>,
) {
    for (supertype in component.source.supertypes) {
        val resolved =
            resolveSupertypeTarget(
                component, supertype, byQualifiedName, bySimpleName,
            )
        if (resolved != null && resolved !== component) {
            edges.add(ClassDependency(component, resolved))
        }
    }
}

private fun addSamePackageEdges(
    component: ClassifiedComponent,
    allComponents: List<ClassifiedComponent>,
    unambiguousQualifiedNames: Set<String>,
    edges: MutableList<ClassDependency>,
) {
    val pkg = component.source.packageName
    if (pkg.isEmpty()) return
    val sourceText = runCatching { component.source.file.readText() }.getOrDefault("")
    if (sourceText.isEmpty()) return
    val codeOnly = stripComments(sourceText)

    allComponents
        .filter {
            it !== component &&
                it.source.qualifiedName in unambiguousQualifiedNames &&
                it.source.packageName == pkg &&
                it.source.simpleName.length >= 2
        }.filter { candidate ->
            Regex("\\b${Regex.escape(candidate.source.simpleName)}\\b").containsMatchIn(codeOnly)
        }.forEach { edges.add(ClassDependency(component, it)) }
}

private fun stripComments(source: String): String {
    val noBlockComments = source.replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
    return noBlockComments
        .lines()
        .filter { !it.trimStart().startsWith("//") }
        .joinToString("\n")
}

private fun resolveSupertypeTarget(
    component: ClassifiedComponent,
    supertype: String,
    byQualifiedName: Map<String, ClassifiedComponent>,
    bySimpleName: Map<String, List<ClassifiedComponent>>,
): ClassifiedComponent? =
    if ('.' in supertype) {
        byQualifiedName[supertype]
    } else {
        component.source.imports
            .firstOrNull { it.substringAfterLast(".") == supertype }
            ?.let { byQualifiedName[it] }
            ?: byQualifiedName["${component.source.packageName}.$supertype"]
            ?: bySimpleName[supertype]?.singleOrNull()
    }

/**
 * A class that depends on a hub: name, file path, and declaration line.
 */
data class HubDependent(
    val name: String,
    val filePath: String,
    val line: Int,
)

/**
 * Result of hub class detection: the component, its inbound count, and who depends on it.
 */
data class HubResult(
    val component: ClassifiedComponent,
    val count: Int,
    val dependents: List<HubDependent>,
)

/**
 * Find hub classes -- the most-depended-on components.
 * Returns components sorted by inbound edge count (descending).
 */
fun findHubClasses(
    components: List<ClassifiedComponent>,
    edges: List<ClassDependency>,
    limit: Int = 15,
): List<HubResult> {
    val inbound = mutableMapOf<String, MutableList<ClassifiedComponent>>()
    for (edge in edges) {
        val key = edge.to.source.qualifiedName
        inbound.getOrPut(key) { mutableListOf() }.add(edge.from)
    }

    val componentByName = components.associateBy { it.source.qualifiedName }

    return inbound.entries
        .sortedByDescending { it.value.size }
        .take(limit)
        .mapNotNull { (name, deps) ->
            componentByName[name]?.let { hub ->
                val dependents =
                    deps
                        .distinctBy { it.source.qualifiedName }
                        .sortedBy { it.source.simpleName }
                        .map { dep ->
                            HubDependent(
                                dep.source.simpleName,
                                dep.source.relativePath,
                                dep.source.declarationLine,
                            )
                        }
                HubResult(hub, dependents.size, dependents)
            }
        }
}

/**
 * Detect circular dependencies between components.
 * Returns lists of component names forming cycles.
 */
fun findCycles(
    edges: List<ClassDependency>,
): List<List<String>> = findQualifiedCycles(edges).map { cycle -> cycle.map { it.substringAfterLast('.') } }

/** Detect deterministic closed routes while preserving exact qualified component IDs. */
internal fun findQualifiedCycles(edges: List<ClassDependency>): List<List<String>> {
    val graphEdges =
        edges
            .map { ComponentDependencyEdge(it.from.source.qualifiedName, it.to.source.qualifiedName) }
            .filter { it.source != it.target }
            .distinct()
            .sortedWith(compareBy({ it.source }, { it.target }))
    val adjacency = graphEdges.groupBy { it.source }.mapValues { (_, outgoing) -> outgoing.map { it.target }.sorted() }
    return graphEdges
        .mapNotNull { edge ->
            shortestComponentPath(edge.target, edge.source, adjacency)
                ?.let { returnPath -> canonicalComponentCycle(listOf(edge.source) + returnPath) }
        }.distinct()
        .sortedBy { it.joinToString("\u0000") }
}

private fun shortestComponentPath(
    start: String,
    target: String,
    adjacency: Map<String, List<String>>,
): List<String>? {
    val queue = ArrayDeque<String>()
    val visited = mutableSetOf(start)
    val predecessor = mutableMapOf<String, String>()
    queue.addLast(start)
    while (queue.isNotEmpty() && target !in predecessor) {
        val current = queue.removeFirst()
        adjacency[current].orEmpty().forEach { next ->
            if (visited.add(next)) {
                predecessor[next] = current
                queue.addLast(next)
            }
        }
    }
    return if (target in predecessor) componentPath(start, target, predecessor) else null
}

private fun componentPath(
    start: String,
    target: String,
    predecessor: Map<String, String>,
): List<String> {
    val reversed = mutableListOf(target)
    var current = target
    while (current != start) {
        current = requireNotNull(predecessor[current])
        reversed += current
    }
    return reversed.asReversed()
}

private fun canonicalComponentCycle(cycle: List<String>): List<String> {
    val members = cycle.dropLast(1)
    return members.indices
        .map { offset ->
            val rotated = members.drop(offset) + members.take(offset)
            rotated + rotated.first()
        }.minBy { it.joinToString("\u0000") }
}

private data class ComponentDependencyEdge(
    val source: String,
    val target: String,
)
