package zone.clanker.docx.web.atlas.projection

import zone.clanker.docx.web.atlas.session.AtlasFilters
import zone.clanker.docx.web.atlas.session.AtlasRelationshipDirection
import zone.clanker.report.model.AtlasCount
import zone.clanker.report.model.AtlasEdge
import zone.clanker.report.model.AtlasFrame
import zone.clanker.report.model.AtlasNode
import zone.clanker.report.model.AtlasNodeType

internal fun AtlasFrame.filteredOverview(filters: AtlasFilters): AtlasFrame {
    val filteredEdges = edges.mapNotNull { edge -> edge.filteredBy(filters) }
    val declarationFiltering = nodes.any { node -> node.type == AtlasNodeType.SYMBOL }
    val nodeFiltering = filters.hasOverviewNodeFilter(declarationFiltering)
    val primaryIds =
        nodes
            .filter { node -> node.matchesOverviewFilters(filters, filteredEdges, declarationFiltering) }
            .mapTo(mutableSetOf(), AtlasNode::id)
    val visibleEdges = filteredEdges.visibleFor(primaryIds, nodeFiltering, filters.relationships.keepInverseContext)
    val visibleIds =
        if (nodeFiltering) {
            primaryIds + visibleEdges.flatMap { edge -> listOf(edge.sourceId, edge.targetId) }
        } else {
            nodes.mapTo(mutableSetOf(), AtlasNode::id)
        }
    val visibleNodes =
        nodes
            .filter { node -> node.id in visibleIds }
            .map { node -> node.filteredCounts(visibleEdges, nodeFiltering, primaryIds, filters) }
    val shownRecords = visibleEdges.sumOf(AtlasEdge::recordCount) + visibleNodes.sumOf(AtlasNode::internalRecordCount)
    val visibleRelationshipIds =
        visibleEdges.flatMapTo(mutableSetOf()) { edge -> edge.relationshipIds + edge.id }
    return copy(
        frameId = "$frameId:filtered",
        nodes = visibleNodes,
        edges = visibleEdges,
        matchingNodeCount = primaryIds.size,
        pageIndex = 0,
        pageCount = if (visibleNodes.isEmpty()) 0 else 1,
        shownRelationshipRecordCount = shownRecords,
        selectedNodeIds = selectedNodeIds.filter(primaryIds::contains),
        selectedEdgeId = selectedEdgeId?.takeIf(visibleRelationshipIds::contains),
    )
}

private fun AtlasEdge.filteredBy(filters: AtlasFilters): AtlasEdge? {
    val allowedKinds = filters.relationships.kinds.mapTo(mutableSetOf()) { kind -> kind.name }
    val selectedCounts = kindCounts.filter { count -> count.key in allowedKinds }
    val selectedRecordCount = selectedCounts.sumOf(AtlasCount::count)
    if (selectedRecordCount == 0) return null
    if (selectedRecordCount == recordCount) return this
    return copy(
        recordCount = selectedRecordCount,
        kindCounts = selectedCounts,
        evidenceCounts = listOf(AtlasCount("FILTERED", "Filtered relationship records", selectedRecordCount)),
        hasHeuristic = false,
    )
}

private fun List<AtlasEdge>.visibleFor(
    primaryIds: Set<String>,
    nodeFiltering: Boolean,
    keepInverseContext: Boolean,
): List<AtlasEdge> =
    when {
        !nodeFiltering -> this
        keepInverseContext -> filter { edge -> edge.sourceId in primaryIds || edge.targetId in primaryIds }
        else -> filter { edge -> edge.sourceId in primaryIds && edge.targetId in primaryIds }
    }

private fun AtlasNode.filteredCounts(
    visibleEdges: List<AtlasEdge>,
    nodeFiltering: Boolean,
    primaryIds: Set<String>,
    filters: AtlasFilters,
): AtlasNode {
    val retainInternalRecords = filters.relationships.kinds.isNotEmpty() && !nodeFiltering
    val visibleInternalRecords = internalRecordCount.takeIf { retainInternalRecords } ?: 0
    val visibleEdgeRecords =
        visibleEdges
            .filter { edge -> edge.sourceId == id || edge.targetId == id }
            .sumOf(AtlasEdge::recordCount)
    return copy(
        primary = !nodeFiltering || id in primaryIds,
        relationshipRecordCount = visibleInternalRecords + visibleEdgeRecords,
        internalRecordCount = visibleInternalRecords,
    )
}

private fun AtlasNode.matchesOverviewFilters(
    filters: AtlasFilters,
    visibleEdges: List<AtlasEdge>,
    declarationFiltering: Boolean,
): Boolean =
    matchesOverviewDeclaration(filters, declarationFiltering) &&
        matchesOverviewSearch(filters.search) &&
        matchesOverviewRelationshipCount(filters.relationships, visibleEdges)

private fun AtlasNode.matchesOverviewRelationshipCount(
    filter: zone.clanker.docx.web.atlas.session.AtlasRelationshipFilter,
    visibleEdges: List<AtlasEdge>,
): Boolean {
    if (filter.minimumCountExclusive == 0L) return true
    val records =
        visibleEdges
            .filter { edge ->
                when (filter.direction) {
                    AtlasRelationshipDirection.ANY -> edge.sourceId == id || edge.targetId == id
                    AtlasRelationshipDirection.INCOMING -> edge.targetId == id
                    AtlasRelationshipDirection.OUTGOING -> edge.sourceId == id
                }
            }.sumOf(AtlasEdge::recordCount)
    return records.toLong() > filter.minimumCountExclusive
}
