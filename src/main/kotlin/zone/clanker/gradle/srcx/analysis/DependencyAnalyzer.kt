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
    val byQualifiedName = components.associateBy { it.source.qualifiedName }

    val edges = mutableListOf<ClassDependency>()

    for (component in components) {
        addImportEdges(component, byQualifiedName, edges)
        addSupertypeEdges(component, byQualifiedName, bySimpleName, edges)
        addSamePackageEdges(component, components, edges)
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
    edges: MutableList<ClassDependency>,
) {
    val pkg = component.source.packageName
    if (pkg.isEmpty()) return
    val sourceText = runCatching { component.source.file.readText() }.getOrDefault("")
    if (sourceText.isEmpty()) return
    val codeOnly = stripComments(sourceText)

    allComponents
        .filter { it !== component && it.source.packageName == pkg && it.source.simpleName.length >= 2 }
        .filter { candidate ->
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
fun findCycles(edges: List<ClassDependency>): List<List<String>> {
    val adjacency = mutableMapOf<String, MutableSet<String>>()
    for (edge in edges) {
        if (edge.from.source.qualifiedName == edge.to.source.qualifiedName) continue
        adjacency
            .getOrPut(edge.from.source.qualifiedName) { mutableSetOf() }
            .add(edge.to.source.qualifiedName)
    }

    val cycles = mutableListOf<List<String>>()
    val visited = mutableSetOf<String>()
    val inStack = mutableSetOf<String>()
    val stack = mutableListOf<String>()

    fun dfs(node: String) {
        if (node in inStack) {
            val cycleStart = stack.indexOf(node)
            if (cycleStart >= 0) {
                val cycle =
                    stack
                        .subList(cycleStart, stack.size)
                        .map { it.substringAfterLast(".") } +
                        node.substringAfterLast(".")
                cycles.add(cycle)
            }
            return
        }
        if (node in visited) return

        visited.add(node)
        inStack.add(node)
        stack.add(node)

        for (neighbor in adjacency[node] ?: emptySet()) {
            dfs(neighbor)
        }

        stack.removeAt(stack.size - 1)
        inStack.remove(node)
    }

    for (node in adjacency.keys) {
        dfs(node)
    }

    return cycles
}
