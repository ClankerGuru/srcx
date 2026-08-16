package zone.clanker.docx.web.atlas.projection

import zone.clanker.report.model.AtlasBuild
import zone.clanker.report.model.AtlasCount
import zone.clanker.report.model.AtlasEdgeCategory
import zone.clanker.report.model.BuildSnapshot
import zone.clanker.report.model.RelationshipKind
import zone.clanker.report.model.RelationshipSnapshot

internal fun BuildSnapshot.projectedAtlasBuild(): AtlasBuild =
    AtlasBuild(
        id = id,
        name = name,
        context = kind.label,
        color = stableBuildColor(name),
    )

private fun stableBuildColor(name: String): String {
    val rawHue = name.hashCode().toLong() * BUILD_COLOR_HUE_STEP
    val hue = ((rawHue % BUILD_COLOR_HUE_COUNT) + BUILD_COLOR_HUE_COUNT) % BUILD_COLOR_HUE_COUNT
    return "hsl($hue 58% 66%)"
}

private fun RelationshipKind.atlasCategory(): AtlasEdgeCategory =
    when (this) {
        RelationshipKind.IMPORT -> AtlasEdgeCategory.IMPORTS
        RelationshipKind.EXTENDS, RelationshipKind.IMPLEMENTS -> AtlasEdgeCategory.INHERITANCE
        RelationshipKind.CALL, RelationshipKind.CONSTRUCTOR -> AtlasEdgeCategory.CALLS
        RelationshipKind.NAME_REFERENCE,
        RelationshipKind.TYPE_REFERENCE,
        RelationshipKind.PROPERTY_TYPE,
        RelationshipKind.PARAMETER_TYPE,
        RelationshipKind.RETURN_TYPE,
        -> AtlasEdgeCategory.REFERENCES
    }

internal fun List<RelationshipSnapshot>.projectedCategory(): AtlasEdgeCategory =
    groupBy { relationship -> relationship.kind.atlasCategory() }
        .entries
        .sortedWith(
            compareByDescending<Map.Entry<AtlasEdgeCategory, List<RelationshipSnapshot>>> { (_, records) ->
                records.size
            }.thenBy { entry -> ATLAS_CATEGORY_PRIORITY.getValue(entry.key) },
        ).first()
        .key

internal fun <T : Enum<T>> List<RelationshipSnapshot>.projectedCounts(
    selector: (RelationshipSnapshot) -> T,
    label: (T) -> String,
): List<AtlasCount> =
    groupBy(selector)
        .entries
        .sortedBy { (value) -> value.name }
        .map { (value, records) -> AtlasCount(value.name, label(value), records.size) }

private const val BUILD_COLOR_HUE_COUNT = 360L
private const val BUILD_COLOR_HUE_STEP = 137L
private val ATLAS_CATEGORY_PRIORITY =
    mapOf(
        AtlasEdgeCategory.INHERITANCE to 0,
        AtlasEdgeCategory.CALLS to 1,
        AtlasEdgeCategory.REFERENCES to 2,
        AtlasEdgeCategory.IMPORTS to 3,
        AtlasEdgeCategory.STRUCTURAL to 4,
        AtlasEdgeCategory.MIXED to 5,
    )
