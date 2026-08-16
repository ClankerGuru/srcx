package zone.clanker.docx.web.atlas.projection

import zone.clanker.report.model.AtlasLens
import zone.clanker.report.model.RelationshipSnapshot

internal data class AtlasFramePage(
    val totalNodeCount: Int,
    val matchingNodeCount: Int,
    val pageIndex: Int,
    val pageCount: Int,
    val totalRelationshipRecordCount: Int,
)

internal data class AtlasRequestedSelection(
    val nodeIds: List<String>,
    val relationshipId: String?,
)

internal data class MixedLensScope(
    val lens: AtlasLens,
    val allNodeIds: List<String>,
    val primaryNodeIds: List<String>,
)

internal data class MixedRelationshipScope(
    val relationships: List<RelationshipSnapshot>,
    val filter: (RelationshipSnapshot) -> Boolean,
)

internal data class MixedRelationshipProjection(
    val relationships: List<RelationshipSnapshot>,
    val visibleNodeIds: List<String>,
    private val endpointsByRelationshipId: Map<String, Pair<String, String>>,
) {
    fun endpoints(relationship: RelationshipSnapshot): Pair<String, String> =
        endpointsByRelationshipId.getValue(relationship.id)
}

internal fun List<*>.singleFramePageCount(): Int = if (isEmpty()) 0 else 1
