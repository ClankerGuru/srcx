package zone.clanker.docx.web.atlas.projection

import zone.clanker.report.model.RelationshipSnapshot

internal fun AtlasRelationshipCountFilter?.matches(
    nodeId: String,
    relationships: List<RelationshipSnapshot>,
    endpoints: (RelationshipSnapshot) -> Pair<String, String>?,
): Boolean {
    val filter = this ?: return true
    val count =
        relationships.count { relationship ->
            endpoints(relationship)?.let { pair -> filter.direction.matches(nodeId, pair) } == true
        }
    return count > filter.moreThan
}

internal fun AtlasRelationshipCountFilter?.matches(
    nodeId: String,
    relationships: List<AtlasBuildRelationshipFact>,
): Boolean {
    val filter = this ?: return true
    val count =
        relationships.count { relationship ->
            filter.direction.matches(nodeId, relationship.sourceId to relationship.targetId)
        }
    return count > filter.moreThan
}

private fun AtlasRelationshipDirection.matches(
    nodeId: String,
    endpoints: Pair<String, String>,
): Boolean =
    when (this) {
        AtlasRelationshipDirection.ANY -> nodeId == endpoints.first || nodeId == endpoints.second
        AtlasRelationshipDirection.OUTGOING -> nodeId == endpoints.first
        AtlasRelationshipDirection.INCOMING -> nodeId == endpoints.second
    }
