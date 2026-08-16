package zone.clanker.docx.web.atlas.projection

import zone.clanker.report.model.RelationshipSnapshot

internal data class AtlasRelationshipClosure(
    val visibleNodeIds: List<String>,
    val relationshipIds: List<String>,
)

internal fun relationshipClosure(
    primaryNodeIds: List<String>,
    relationships: List<RelationshipSnapshot>,
    endpoints: (RelationshipSnapshot) -> Pair<String, String>?,
): AtlasRelationshipClosure {
    val primaryIds = primaryNodeIds.toSet()
    val visibleRelationships =
        relationships.mapNotNull { relationship ->
            endpoints(relationship)
                ?.takeIf { (sourceId, targetId) -> sourceId in primaryIds || targetId in primaryIds }
                ?.let { endpointPair -> relationship to endpointPair }
        }
    val visibleNodeIds =
        buildSet {
            addAll(primaryNodeIds)
            visibleRelationships.forEach { (_, endpointPair) ->
                add(endpointPair.first)
                add(endpointPair.second)
            }
        }.sorted()
    return AtlasRelationshipClosure(
        visibleNodeIds = visibleNodeIds,
        relationshipIds = visibleRelationships.map { (relationship, _) -> relationship.id }.sorted(),
    )
}
