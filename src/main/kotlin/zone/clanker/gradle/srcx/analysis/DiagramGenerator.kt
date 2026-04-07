package zone.clanker.gradle.srcx.analysis

private const val MAX_SEQUENCE_DIAGRAMS = 5
private const val MAX_CHAIN_LENGTH = 6

/**
 * Generate a Mermaid flowchart showing component dependencies.
 * Groups nodes by their actual package group.
 */
fun generateDependencyDiagram(
    components: List<ClassifiedComponent>,
    edges: List<ClassDependency>,
): String {
    val connected = mutableSetOf<String>()
    for (edge in edges) {
        connected.add(edge.from.source.qualifiedName)
        connected.add(edge.to.source.qualifiedName)
    }

    if (connected.isEmpty()) return ""

    val relevantComponents = components.filter { it.source.qualifiedName in connected }
    val byGroup = relevantComponents.groupBy { it.packageGroup }

    val sb = StringBuilder()
    sb.appendLine("```mermaid")
    sb.appendLine("flowchart TD")

    for ((group, comps) in byGroup.entries.sortedBy { it.key }) {
        sb.appendLine("    subgraph $group")
        for (c in comps.sortedBy { it.source.simpleName }) {
            sb.appendLine("        ${nodeId(c.source.qualifiedName)}[${c.source.simpleName}]")
        }
        sb.appendLine("    end")
    }

    for (edge in edges.distinctBy { "${it.from.source.qualifiedName}->${it.to.source.qualifiedName}" }) {
        sb.appendLine(
            "    ${nodeId(edge.from.source.qualifiedName)} --> ${nodeId(edge.to.source.qualifiedName)}",
        )
    }

    sb.appendLine("```")
    return sb.toString()
}

/**
 * Generate Mermaid sequence diagrams tracing data flow from entry points.
 */
fun generateSequenceDiagrams(
    components: List<ClassifiedComponent>,
    edges: List<ClassDependency>,
): String {
    val entryPoints = findEntryPoints(components, edges)
    if (entryPoints.isEmpty()) return ""

    val adjacency = edges.groupBy({ it.from.source.qualifiedName }, { it.to })

    val sb = StringBuilder()

    for (entry in entryPoints.take(MAX_SEQUENCE_DIAGRAMS)) {
        val chain = traceChain(entry, adjacency)
        if (chain.size < 2) continue
        appendSequenceDiagram(sb, entry, chain)
    }

    return sb.toString()
}

private fun appendSequenceDiagram(
    sb: StringBuilder,
    entry: ClassifiedComponent,
    chain: List<ClassifiedComponent>,
) {
    sb.appendLine("### ${entry.source.simpleName} Flow")
    sb.appendLine()
    sb.appendLine("```mermaid")
    sb.appendLine("sequenceDiagram")

    for (c in chain) {
        sb.appendLine("    participant ${nodeId(c.source.qualifiedName)} as ${c.source.simpleName}")
    }

    for (i in 0 until chain.size - 1) {
        val from = nodeId(chain[i].source.qualifiedName)
        val to = nodeId(chain[i + 1].source.qualifiedName)
        sb.appendLine("    $from->>$to: ")
        sb.appendLine("    $to-->>$from: ")
    }

    sb.appendLine("```")
    sb.appendLine()
}

private fun traceChain(
    entry: ClassifiedComponent,
    adjacency: Map<String, List<ClassifiedComponent>>,
): List<ClassifiedComponent> {
    val chain = mutableListOf(entry)
    val visited = mutableSetOf(entry.source.qualifiedName)
    var current = entry

    repeat(MAX_CHAIN_LENGTH) {
        val neighbors = adjacency[current.source.qualifiedName] ?: return chain
        val next =
            neighbors
                .filter { it.source.qualifiedName !in visited }
                .filter { it.role != ComponentRole.CONFIGURATION }
                .maxByOrNull { adjacency[it.source.qualifiedName]?.size ?: 0 }
                ?: return chain

        chain.add(next)
        visited.add(next.source.qualifiedName)
        current = next
    }

    return chain
}

private fun nodeId(name: String): String =
    name.replace(Regex("[^a-zA-Z0-9]"), "_")
