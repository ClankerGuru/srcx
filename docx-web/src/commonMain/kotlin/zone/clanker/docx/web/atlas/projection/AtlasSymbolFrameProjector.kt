package zone.clanker.docx.web.atlas.projection

import zone.clanker.report.model.AtlasFrame
import zone.clanker.report.model.AtlasLens
import zone.clanker.report.model.AtlasNode
import zone.clanker.report.model.RelationshipKind
import zone.clanker.report.model.RelationshipSnapshot
import zone.clanker.report.model.SymbolSnapshot

internal class AtlasSymbolFrameProjector(
    private val context: AtlasProjectionContext,
) {
    private val edges = AtlasEdgeProjector(context)
    private val nodes = AtlasNodeProjector(context)
    private val frames = AtlasFrameAssembler(context)

    fun complete(
        request: AtlasProjectFrameRequest,
        relationships: List<RelationshipSnapshot>,
    ): BoundedGraphFrame {
        val primaryIds =
            context.project.symbols
                .filter { symbol -> context.symbolMatches(symbol, request) }
                .filter { symbol ->
                    request.relationshipCountFilter.matches(symbol.id, relationships, context::symbolEndpoints)
                }.map(SymbolSnapshot::id)
                .sorted()
        val closure = relationshipClosure(primaryIds, relationships, context::symbolEndpoints)
        return BoundedGraphFrame(
            primaryNodeIds = primaryIds,
            visibleNodeIds = closure.visibleNodeIds,
            visibleRelationshipIds = closure.relationshipIds,
            totalNodeCount = context.project.symbols.size,
            matchingNodeCount = primaryIds.size,
            pageIndex = 0,
            pageCount = primaryIds.singleFramePageCount(),
        )
    }

    fun project(
        frame: BoundedGraphFrame,
        selection: AtlasRequestedSelection,
        totalRelationshipRecordCount: Int? = null,
    ): AtlasFrame {
        val visibleIds = frame.visibleNodeIds.toSet()
        val relationships =
            edges.visibleRelationships(frame.visibleRelationshipIds) { relationship ->
                relationship.kind != RelationshipKind.IMPORT &&
                    relationship.sourceSymbolId != null &&
                    relationship.sourceSymbolId in visibleIds &&
                    relationship.targetSymbolId in visibleIds
            }
        val retainedNodeIds =
            frame.primaryNodeIds.toMutableSet().apply {
                relationships.flatMapTo(this) { relationship -> edges.requiredSymbolEndpoints(relationship).toList() }
            }
        val projectedEdges =
            edges.aggregate(
                relationships.filter { relationship ->
                    edges.requiredSymbolEndpoints(relationship).let { (sourceId, targetId) -> sourceId != targetId }
                },
                edges::requiredSymbolEndpoints,
                context.cycles.analysisSymbolSteps,
            )
        val projectedNodes =
            frame.visibleNodeIds
                .filter(retainedNodeIds::contains)
                .map { symbolId ->
                    nodes.symbolNode(
                        context.symbolsById.getValue(symbolId),
                        symbolId in frame.primaryNodeIds,
                        relationships,
                    )
                }.sortedBy(AtlasNode::id)
        return frames.frame(
            lens = AtlasLens.SYMBOLS,
            nodes = projectedNodes,
            edges = projectedEdges,
            page =
                AtlasFramePage(
                    totalNodeCount = frame.totalNodeCount,
                    matchingNodeCount = frame.matchingNodeCount,
                    pageIndex = frame.pageIndex,
                    pageCount = frame.pageCount,
                    totalRelationshipRecordCount =
                        totalRelationshipRecordCount
                            ?: context.project.relationships.count {
                                it.kind != RelationshipKind.IMPORT && it.sourceSymbolId != null
                            },
                ),
            selection = selection,
        )
    }
}
