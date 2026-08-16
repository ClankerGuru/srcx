package zone.clanker.docx.web.atlas.projection

import zone.clanker.report.model.AtlasFrame
import zone.clanker.report.model.AtlasLens
import zone.clanker.report.model.AtlasNode
import zone.clanker.report.model.RelationshipSnapshot

internal class AtlasMixedFrameProjector(
    private val context: AtlasProjectionContext,
) {
    private val edges = AtlasEdgeProjector(context)
    private val nodes = AtlasNodeProjector(context)
    private val frames = AtlasFrameAssembler(context)

    fun problems(
        request: AtlasProjectFrameRequest,
        eligibleRelationships: List<RelationshipSnapshot>,
    ): AtlasFrame {
        val allNodeIds = context.problemNodeIds()
        val relationshipScope = MixedRelationshipScope(eligibleRelationships) { true }
        return project(
            scope = mixedScope(AtlasLens.PROBLEMS, allNodeIds, relationshipScope, request),
            relationshipScope = relationshipScope,
            request = request,
        )
    }

    fun cycles(
        request: AtlasProjectFrameRequest,
        eligibleRelationships: List<RelationshipSnapshot>,
    ): AtlasFrame {
        val allNodeIds = context.cycleNodeIds()
        val relationshipScope =
            MixedRelationshipScope(eligibleRelationships) { relationship ->
                relationship.id in context.cycles.observedRelationshipIds
            }
        return project(
            scope = mixedScope(AtlasLens.CYCLES, allNodeIds, relationshipScope, request),
            relationshipScope = relationshipScope,
            request = request,
        )
    }

    private fun mixedScope(
        lens: AtlasLens,
        allNodeIds: List<String>,
        relationshipScope: MixedRelationshipScope,
        request: AtlasProjectFrameRequest,
    ): MixedLensScope {
        val scopedRelationships = relationshipScope.relationships.filter(relationshipScope.filter)
        val primaryNodeIds =
            allNodeIds.filter { nodeId ->
                context.mixedNodeMatches(nodeId, request) &&
                    request.relationshipCountFilter.matches(nodeId, scopedRelationships) { relationship ->
                        countEndpoints(nodeId, relationship)
                    }
            }
        return MixedLensScope(lens, allNodeIds, primaryNodeIds)
    }

    private fun project(
        scope: MixedLensScope,
        relationshipScope: MixedRelationshipScope,
        request: AtlasProjectFrameRequest,
    ): AtlasFrame {
        val projection = relationships(scope.primaryNodeIds, relationshipScope)
        val primaryNodeIds = scope.primaryNodeIds.toSet()
        val endpointSelector: (RelationshipSnapshot) -> Pair<String, String> = projection::endpoints
        val projectedEdges =
            edges.aggregate(
                relationships =
                    projection.relationships.filter { relationship ->
                        endpointSelector(relationship).let { (sourceId, targetId) -> sourceId != targetId }
                    },
                endpoints = endpointSelector,
                analysisSteps = context.cycles.analysisSymbolSteps + context.cycles.analysisFileSteps,
            )
        val projectedNodes =
            projection.visibleNodeIds
                .map { nodeId ->
                    nodes.mixedNode(
                        nodeId = nodeId,
                        primary = nodeId in primaryNodeIds,
                        relationships = projection.relationships,
                        endpoints = endpointSelector,
                    )
                }.sortedBy(AtlasNode::id)
        return frames.frame(
            lens = scope.lens,
            nodes = projectedNodes,
            edges = projectedEdges,
            page =
                AtlasFramePage(
                    totalNodeCount = scope.allNodeIds.size,
                    matchingNodeCount = scope.primaryNodeIds.size,
                    pageIndex = 0,
                    pageCount = scope.primaryNodeIds.singleFramePageCount(),
                    totalRelationshipRecordCount = totalRelationships(scope.allNodeIds, relationshipScope),
                ),
            selection = AtlasRequestedSelection(request.selectedNodeIds, request.selectedRelationshipId),
        )
    }

    private fun relationships(
        primaryNodeIds: List<String>,
        scope: MixedRelationshipScope,
    ): MixedRelationshipProjection {
        val primaryIds = primaryNodeIds.toSet()
        val endpointsByRelationshipId =
            scope.relationships
                .filter(scope.filter)
                .mapNotNull { relationship ->
                    closureEndpoints(relationship, primaryIds)?.let { endpoints -> relationship.id to endpoints }
                }.toMap()
        val visibleRelationships =
            scope.relationships.filter { relationship -> relationship.id in endpointsByRelationshipId }
        val visibleNodeIds =
            buildSet {
                addAll(primaryNodeIds)
                endpointsByRelationshipId.values.forEach { (sourceId, targetId) ->
                    add(sourceId)
                    add(targetId)
                }
            }.sorted()
        return MixedRelationshipProjection(visibleRelationships, visibleNodeIds, endpointsByRelationshipId)
    }

    private fun totalRelationships(
        allNodeIds: List<String>,
        scope: MixedRelationshipScope,
    ): Int = relationships(allNodeIds, scope).relationships.size

    private fun countEndpoints(
        nodeId: String,
        relationship: RelationshipSnapshot,
    ): Pair<String, String>? =
        if (nodeId in context.symbolsById) {
            context.symbolEndpoints(relationship)
        } else {
            context.fileEndpoints(relationship)
        }

    private fun closureEndpoints(
        relationship: RelationshipSnapshot,
        primaryNodeIds: Set<String>,
    ): Pair<String, String>? =
        context.symbolEndpoints(relationship)?.takeIf { endpoints -> endpoints.touches(primaryNodeIds) }
            ?: context.fileEndpoints(relationship)?.takeIf { endpoints -> endpoints.touches(primaryNodeIds) }

    private fun Pair<String, String>.touches(nodeIds: Set<String>): Boolean = first in nodeIds || second in nodeIds
}
