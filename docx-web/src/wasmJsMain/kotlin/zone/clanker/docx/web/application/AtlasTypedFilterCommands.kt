package zone.clanker.docx.web.application

import zone.clanker.docx.web.atlas.AtlasController
import zone.clanker.docx.web.atlas.GraphRelationshipFilter
import zone.clanker.docx.web.atlas.projection.AtlasRelationshipDirection
import zone.clanker.docx.web.atlas.projection.AtlasSearchTarget

internal fun updateRelationshipFilter(
    value: String,
    controller: AtlasController,
): AtlasCommandResult {
    val filter =
        when (value) {
            "all" -> GraphRelationshipFilter.ALL
            "inheritance" -> GraphRelationshipFilter.INHERITANCE
            "calls" -> GraphRelationshipFilter.CALLS
            "references" -> GraphRelationshipFilter.REFERENCES
            "imports" -> null
            else -> null
        }
    return if (filter == null) {
        AtlasCommandResult.Rejected("Unknown or unavailable graph relationship filter: $value")
    } else {
        completed { controller.graph.updateRelationshipFilter(filter) }
    }
}

internal fun updateSearchTarget(
    value: String,
    controller: AtlasController,
): AtlasCommandResult {
    if (value == "all" || value.isEmpty()) {
        return completed(controller.graph::selectAllSearchTargets)
    }
    val target = AtlasSearchTarget.entries.singleOrNull { target -> target.name.equals(value, ignoreCase = true) }
    return if (target == null) {
        AtlasCommandResult.Rejected("Unknown graph search target: $value")
    } else {
        completed { controller.graph.toggleSearchTarget(target) }
    }
}

internal fun updateRelationshipCount(
    value: String,
    controller: AtlasController,
): AtlasCommandResult {
    if (value.isEmpty()) return completed { controller.graph.updateRelationshipCountMoreThan(null) }
    val moreThan = value.toIntOrNull()?.takeIf { it >= 0 }
    return if (moreThan == null) {
        AtlasCommandResult.Rejected("Relationship count must be a non-negative integer or empty: $value")
    } else {
        completed { controller.graph.updateRelationshipCountMoreThan(moreThan) }
    }
}

internal fun updateRelationshipDirection(
    value: String,
    controller: AtlasController,
): AtlasCommandResult {
    val direction =
        AtlasRelationshipDirection.entries.singleOrNull { direction ->
            direction.name.equals(value, ignoreCase = true)
        }
    return if (direction == null) {
        AtlasCommandResult.Rejected("Relationship direction must be any, outgoing, or incoming: $value")
    } else {
        completed { controller.graph.updateRelationshipDirection(direction) }
    }
}
