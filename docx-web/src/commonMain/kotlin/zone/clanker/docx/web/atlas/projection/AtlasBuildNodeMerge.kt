package zone.clanker.docx.web.atlas.projection

import zone.clanker.report.model.AtlasNode

internal fun mergeAtlasBuildNodes(
    frames: List<AtlasProjectBuildFrames>,
    relationships: List<AtlasBuildRelationshipFact>,
): List<AtlasNode> {
    val fileFindingIds =
        frames
            .flatMap { projection -> projection.fileEvidence.nodes }
            .groupBy(AtlasNode::id)
            .mapValues { (_, nodes) -> nodes.flatMapTo(mutableSetOf(), AtlasNode::findingIds) }
    return frames
        .flatMap { projection -> projection.current.nodes }
        .groupBy(AtlasNode::id)
        .entries
        .sortedBy(Map.Entry<String, List<AtlasNode>>::key)
        .map { (_, nodes) -> mergeNodeGroup(nodes, fileFindingIds, relationships) }
}

private fun mergeNodeGroup(
    nodes: List<AtlasNode>,
    fileFindingIds: Map<String, Set<String>>,
    relationships: List<AtlasBuildRelationshipFact>,
): AtlasNode {
    require(nodes.map(AtlasNode::stableIdentity).distinct().size == 1) {
        "Duplicate Atlas build nodes must agree on their serialized source identity"
    }
    val first = nodes.first()
    val findingIds = nodes.flatMapTo(mutableSetOf(), AtlasNode::findingIds).sorted()
    val sourceFindingCount =
        maxOf(
            findingIds.size,
            first.sourceFileId
                ?.let(fileFindingIds::get)
                .orEmpty()
                .size,
            nodes.maxOf(AtlasNode::sourceFileFindingCount),
        )
    val incident = relationships.count { fact -> first.id == fact.sourceId || first.id == fact.targetId }
    val internal = relationships.count { fact -> first.id == fact.sourceId && first.id == fact.targetId }
    return first.copy(
        primary = nodes.any(AtlasNode::primary),
        findingIds = findingIds,
        findingCount = findingIds.size,
        sourceFileFindingCount = sourceFindingCount,
        hasObservedCycle = nodes.any(AtlasNode::hasObservedCycle),
        hasAnalysisCycle = nodes.any(AtlasNode::hasAnalysisCycle),
        hasAnalyzerFinding = sourceFindingCount > 0,
        relationshipRecordCount = incident,
        internalRecordCount = internal,
    )
}

private fun AtlasNode.stableIdentity(): AtlasNode =
    copy(
        primary = false,
        findingIds = emptyList(),
        findingCount = 0,
        sourceFileFindingCount = 0,
        hasObservedCycle = false,
        hasAnalysisCycle = false,
        hasAnalyzerFinding = false,
        relationshipRecordCount = 0,
        internalRecordCount = 0,
    )
