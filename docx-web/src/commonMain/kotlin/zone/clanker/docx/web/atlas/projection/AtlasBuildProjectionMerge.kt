package zone.clanker.docx.web.atlas.projection

import zone.clanker.report.model.AtlasNode
import zone.clanker.report.model.SymbolKind

internal fun mergeAtlasBuildProjection(
    frames: List<AtlasProjectBuildFrames>,
    request: AtlasProjectFrameRequest,
    evidence: AtlasBuildRawEvidence,
): AtlasBuildMergedProjection {
    val currentFrames = frames.map { projection -> projection.project to projection.current }
    val completeFrames = frames.map { projection -> projection.project to projection.complete }
    val completeRequest =
        request.copy(
            query = "",
            searchTargets = emptySet(),
            sourceSetIds = emptySet(),
            symbolKinds = SymbolKind.entries.toSet(),
            relationshipCountFilter = null,
            selectedNodeIds = emptyList(),
            selectedRelationshipId = null,
        )
    val unfilteredRelationships = projectedRelationshipFacts(currentFrames, request, evidence)
    val completeRelationships = projectedRelationshipFacts(completeFrames, completeRequest, evidence)
    val unfilteredNodes = mergeAtlasBuildNodes(frames, unfilteredRelationships)
    val matchingNodeIds =
        unfilteredNodes
            .filter(AtlasNode::primary)
            .filter { node -> request.relationshipCountFilter.matches(node.id, unfilteredRelationships) }
            .mapTo(mutableSetOf(), AtlasNode::id)
    val relationships =
        unfilteredRelationships.filter { relationship ->
            relationship.sourceId in matchingNodeIds || relationship.targetId in matchingNodeIds
        }
    val visibleNodeIds =
        matchingNodeIds.toMutableSet().apply {
            relationships.forEach { relationship ->
                add(relationship.sourceId)
                add(relationship.targetId)
            }
        }
    val nodes =
        mergeAtlasBuildNodes(frames, relationships)
            .filter { node -> node.id in visibleNodeIds }
            .map { node -> node.copy(primary = node.id in matchingNodeIds) }
    val edges = atlasBuildEdges(relationships, nodes)
    val totalNodeCount =
        completeFrames
            .flatMap { (_, frame) -> frame.nodes }
            .filter(AtlasNode::primary)
            .mapTo(mutableSetOf(), AtlasNode::id)
            .size
    return AtlasBuildMergedProjection(
        nodes = nodes,
        edges = edges,
        totalNodeCount = totalNodeCount,
        matchingNodeCount = matchingNodeIds.size,
        totalRelationshipRecordCount =
            totalBuildRelationshipCount(frames.map(AtlasProjectBuildFrames::project), request, completeRelationships),
        shownRelationshipRecordCount = relationships.size,
    )
}
