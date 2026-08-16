package zone.clanker.gradle.srcx.snapshot

import zone.clanker.report.model.CycleSnapshot
import zone.clanker.report.model.RelationshipKind

/**
 * Derives multi-symbol closed routes solely from resolved, non-import relationship evidence.
 * Self-references remain in the relationship catalog but do not form multi-symbol cycle summaries.
 */
internal fun relationshipCycleSnapshots(records: List<RelationshipRecord>): List<CycleSnapshot> {
    val edges = observedTopologyEdges(records)
    val routes =
        stronglyConnectedComponents(edges)
            .flatMap { component -> cycleRoutes(component, edges) }
            .distinctBy(ObservedCycleRoute::key)
            .sortedBy(ObservedCycleRoute::key)
    return routes
        .map { route ->
            CycleSnapshot(
                id = stableSnapshotId("relationship-cycle", route.key),
                symbolIds = route.symbolIds,
                relationshipIds = route.relationshipIds,
            )
        }.sortedBy(CycleSnapshot::id)
}

private fun observedTopologyEdges(records: List<RelationshipRecord>): List<ObservedRelationshipEdge> =
    records
        .filter { record -> record.snapshot.kind != RelationshipKind.IMPORT }
        .mapNotNull { record ->
            record.snapshot.sourceSymbolId?.let { sourceId ->
                ObservedRelationshipEdge(sourceId, record.snapshot.targetSymbolId, record.snapshot.id)
            }
        }.filterNot { edge -> edge.sourceId == edge.targetId }
        .groupBy { edge -> edge.sourceId to edge.targetId }
        .values
        .map { parallelEdges -> parallelEdges.minBy(ObservedRelationshipEdge::relationshipId) }
        .sortedWith(OBSERVED_EDGE_ORDER)

private fun stronglyConnectedComponents(edges: List<ObservedRelationshipEdge>): List<List<String>> {
    val memberIds = edges.flatMap { edge -> listOf(edge.sourceId, edge.targetId) }.distinct().sorted()
    val adjacency = memberIds.associateWith { mutableListOf<String>() }
    val reverseAdjacency = memberIds.associateWith { mutableListOf<String>() }
    edges.forEach { edge ->
        adjacency.getValue(edge.sourceId) += edge.targetId
        reverseAdjacency.getValue(edge.targetId) += edge.sourceId
    }
    adjacency.values.forEach { it.sort() }
    reverseAdjacency.values.forEach { it.sort() }
    val visited = mutableSetOf<String>()
    val finishOrder = mutableListOf<String>()
    memberIds.forEach { memberId -> depthFirstOrder(memberId, adjacency, visited, finishOrder) }
    visited.clear()
    return finishOrder
        .asReversed()
        .mapNotNull { memberId ->
            if (memberId in visited) return@mapNotNull null
            val component = mutableListOf<String>()
            collectComponent(memberId, reverseAdjacency, visited, component)
            component.sorted().takeIf { it.size > 1 }
        }.sortedBy { it.joinToString("\u0000") }
}

private fun cycleRoutes(
    members: List<String>,
    edges: List<ObservedRelationshipEdge>,
): List<ObservedCycleRoute> {
    val memberSet = members.toSet()
    val componentEdges = edges.filter { it.sourceId in memberSet && it.targetId in memberSet }
    val adjacency = componentEdges.groupBy(ObservedRelationshipEdge::sourceId)
    return componentEdges.map { edge ->
        val returnPath = shortestPath(edge.targetId, edge.sourceId, memberSet, adjacency)
        ObservedCycleRoute(
            symbolIds = listOf(edge.sourceId) + returnPath.symbolIds,
            relationshipIds = listOf(edge.relationshipId) + returnPath.relationshipIds,
        ).canonical()
    }
}

private fun shortestPath(
    start: String,
    target: String,
    members: Set<String>,
    adjacency: Map<String, List<ObservedRelationshipEdge>>,
): ObservedCyclePath {
    val queue = ArrayDeque<String>()
    val visited = mutableSetOf(start)
    val predecessors = mutableMapOf<String, ObservedCyclePredecessor>()
    queue.addLast(start)
    while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        adjacency[current]
            .orEmpty()
            .sortedWith(OBSERVED_EDGE_ORDER)
            .filter { edge -> edge.targetId in members && visited.add(edge.targetId) }
            .forEach { edge ->
                predecessors[edge.targetId] = ObservedCyclePredecessor(current, edge.relationshipId)
                queue.addLast(edge.targetId)
            }
        if (target in predecessors) return cyclePath(start, target, predecessors)
    }
    error("Strongly connected relationship edge has no return path: $start -> $target")
}

private fun cyclePath(
    start: String,
    target: String,
    predecessors: Map<String, ObservedCyclePredecessor>,
): ObservedCyclePath {
    val reversedSymbols = mutableListOf(target)
    val reversedRelationships = mutableListOf<String>()
    var current = target
    while (current != start) {
        val predecessor = requireNotNull(predecessors[current])
        reversedRelationships += predecessor.relationshipId
        current = predecessor.symbolId
        reversedSymbols += current
    }
    return ObservedCyclePath(reversedSymbols.asReversed(), reversedRelationships.asReversed())
}

private fun ObservedCycleRoute.canonical(): ObservedCycleRoute {
    val openSymbols = symbolIds.dropLast(1)
    return relationshipIds.indices
        .map { offset ->
            val symbols = openSymbols.drop(offset) + openSymbols.take(offset)
            val relationships = relationshipIds.drop(offset) + relationshipIds.take(offset)
            ObservedCycleRoute(symbols + symbols.first(), relationships)
        }.minBy(ObservedCycleRoute::key)
}

private fun depthFirstOrder(
    memberId: String,
    adjacency: Map<String, List<String>>,
    visited: MutableSet<String>,
    finishOrder: MutableList<String>,
) {
    if (!visited.add(memberId)) return
    adjacency.getValue(memberId).forEach { neighbor -> depthFirstOrder(neighbor, adjacency, visited, finishOrder) }
    finishOrder += memberId
}

private fun collectComponent(
    memberId: String,
    adjacency: Map<String, List<String>>,
    visited: MutableSet<String>,
    component: MutableList<String>,
) {
    if (!visited.add(memberId)) return
    component += memberId
    adjacency.getValue(memberId).forEach { neighbor -> collectComponent(neighbor, adjacency, visited, component) }
}

private data class ObservedRelationshipEdge(
    val sourceId: String,
    val targetId: String,
    val relationshipId: String,
)

private data class ObservedCyclePath(
    val symbolIds: List<String>,
    val relationshipIds: List<String>,
)

private data class ObservedCyclePredecessor(
    val symbolId: String,
    val relationshipId: String,
)

private data class ObservedCycleRoute(
    val symbolIds: List<String>,
    val relationshipIds: List<String>,
) {
    val key: String =
        symbolIds.joinToString("\u0000") + "\u0001" + relationshipIds.joinToString("\u0000")
}

private val OBSERVED_EDGE_ORDER: Comparator<ObservedRelationshipEdge> =
    compareBy(
        ObservedRelationshipEdge::sourceId,
        ObservedRelationshipEdge::targetId,
        ObservedRelationshipEdge::relationshipId,
    )
