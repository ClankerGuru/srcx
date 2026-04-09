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

    allComponents
        .filter { it !== component && it.source.packageName == pkg && it.source.simpleName.length >= 2 }
        .filter { candidate ->
            Regex("\\b${Regex.escape(candidate.source.simpleName)}\\b").containsMatchIn(sourceText)
        }.forEach { edges.add(ClassDependency(component, it)) }
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
 * Find hub classes -- the most-depended-on components.
 * Returns components sorted by inbound edge count (descending).
 */
fun findHubClasses(
    components: List<ClassifiedComponent>,
    edges: List<ClassDependency>,
    limit: Int = 15,
): List<Pair<ClassifiedComponent, Int>> {
    val inboundCounts = mutableMapOf<String, Int>()
    for (edge in edges) {
        val key = edge.to.source.qualifiedName
        inboundCounts[key] = (inboundCounts[key] ?: 0) + 1
    }

    val componentByName = components.associateBy { it.source.qualifiedName }

    return inboundCounts.entries
        .sortedByDescending { it.value }
        .take(limit)
        .mapNotNull { (name, count) ->
            componentByName[name]?.let { it to count }
        }
}

/**
 * Detect circular dependencies between components.
 * Returns lists of component names forming cycles.
 */
fun findCycles(edges: List<ClassDependency>): List<List<String>> {
    val adjacency = mutableMapOf<String, MutableSet<String>>()
    for (edge in edges) {
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
