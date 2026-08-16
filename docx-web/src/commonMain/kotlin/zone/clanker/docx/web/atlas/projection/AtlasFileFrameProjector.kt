package zone.clanker.docx.web.atlas.projection

import zone.clanker.report.model.AtlasFrame
import zone.clanker.report.model.AtlasLens
import zone.clanker.report.model.AtlasNode
import zone.clanker.report.model.RelationshipKind
import zone.clanker.report.model.RelationshipSnapshot
import zone.clanker.report.model.SourceFileSnapshot

internal class AtlasFileFrameProjector(
    private val context: AtlasProjectionContext,
) {
    private val edges = AtlasEdgeProjector(context)
    private val nodes = AtlasNodeProjector(context)
    private val frames = AtlasFrameAssembler(context)

    fun complete(
        request: AtlasProjectFrameRequest,
        relationships: List<RelationshipSnapshot>,
    ): BoundedFileGraphFrame {
        val primaryIds =
            context.project.files
                .filter { file -> context.sourceFileMatches(file, request) }
                .filter { file ->
                    request.relationshipCountFilter.matches(file.id, relationships, context::fileEndpoints)
                }.map(SourceFileSnapshot::id)
                .sorted()
        val closure = relationshipClosure(primaryIds, relationships, context::fileEndpoints)
        return BoundedFileGraphFrame(
            primaryFileIds = primaryIds,
            visibleFileIds = closure.visibleNodeIds,
            visibleRelationshipIds = closure.relationshipIds,
            totalFileCount = context.project.files.size,
            matchingFileCount = primaryIds.size,
            pageIndex = 0,
            pageCount = primaryIds.singleFramePageCount(),
        )
    }

    fun project(
        frame: BoundedFileGraphFrame,
        selection: AtlasRequestedSelection,
        totalRelationshipRecordCount: Int? = null,
    ): AtlasFrame {
        val visibleIds = frame.visibleFileIds.toSet()
        val relationships =
            edges.visibleRelationships(frame.visibleRelationshipIds) { relationship ->
                relationship.kind != RelationshipKind.IMPORT &&
                    context.fileEndpoints(relationship)?.let { (sourceId, targetId) ->
                        sourceId in visibleIds && targetId in visibleIds
                    } == true
            }
        val retainedNodeIds =
            frame.primaryFileIds.toMutableSet().apply {
                relationships.flatMapTo(this) { relationship -> edges.requiredFileEndpoints(relationship).toList() }
            }
        val projectedEdges =
            edges.aggregate(
                relationships.filter { relationship ->
                    edges.requiredFileEndpoints(relationship).let { (sourceId, targetId) -> sourceId != targetId }
                },
                edges::requiredFileEndpoints,
                context.cycles.analysisFileSteps,
            )
        val projectedNodes =
            frame.visibleFileIds
                .filter(retainedNodeIds::contains)
                .map { fileId ->
                    nodes.fileNode(context.filesById.getValue(fileId), fileId in frame.primaryFileIds, relationships)
                }.sortedBy(AtlasNode::id)
        return frames.frame(
            lens = AtlasLens.FILES,
            nodes = projectedNodes,
            edges = projectedEdges,
            page =
                AtlasFramePage(
                    totalNodeCount = frame.totalFileCount,
                    matchingNodeCount = frame.matchingFileCount,
                    pageIndex = frame.pageIndex,
                    pageCount = frame.pageCount,
                    totalRelationshipRecordCount =
                        totalRelationshipRecordCount
                            ?: context.project.relationships.count { it.kind != RelationshipKind.IMPORT },
                ),
            selection = selection,
        )
    }
}
