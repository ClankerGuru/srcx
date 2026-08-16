package zone.clanker.docx.web.atlas.dom.controls

import org.w3c.dom.HTMLDivElement
import zone.clanker.docx.web.atlas.AtlasGraphController
import zone.clanker.docx.web.atlas.GraphRelationshipFilter
import zone.clanker.docx.web.atlas.dom.AtlasDomSurfaceModel
import zone.clanker.docx.web.atlas.dom.buttonElement
import zone.clanker.docx.web.atlas.dom.clearContent
import zone.clanker.docx.web.atlas.dom.htmlElement
import zone.clanker.docx.web.atlas.dom.requiredButton
import zone.clanker.docx.web.atlas.dom.requiredElement
import zone.clanker.docx.web.atlas.dom.requiredHtmlElement
import zone.clanker.docx.web.atlas.selectableRelationshipFilters
import zone.clanker.report.model.AtlasFrame
import zone.clanker.report.model.ProjectGraphShard

internal class AtlasRelationshipControlsRenderer {
    fun render(
        root: HTMLDivElement,
        model: AtlasDomSurfaceModel,
        controller: AtlasGraphController,
        enabled: Boolean,
    ) {
        val rail = root.requiredHtmlElement("[data-srcx-relationship-kind-options]")
        rail.clearContent()
        val allSelected = controller.allRelationshipFiltersSelected
        val buttons =
            (listOf(GraphRelationshipFilter.ALL) + selectableRelationshipFilters)
                .map { filter -> relationshipButton(model, controller, filter, allSelected, enabled) }
        buttons.forEach { button -> rail.appendChild(button) }
        wireControlChipGroup(root, buttons)
        root.requiredButton("[data-srcx-relationship-kind-clear]").also { clear ->
            clear.disabled = !enabled || controller.relationshipFilters.isEmpty()
            clear.onclick = {
                controller.clearRelationshipFilters()
                null
            }
        }
        root.requiredElement("[data-srcx-relationship-kind-summary]").textContent =
            controller.relationshipFilterSummary()
        root.requiredHtmlElement("[data-srcx-relationship-kind-filter]").hidden = model.frame == null
    }

    private fun relationshipButton(
        model: AtlasDomSurfaceModel,
        controller: AtlasGraphController,
        filter: GraphRelationshipFilter,
        allSelected: Boolean,
        enabled: Boolean,
    ) =
        buttonElement("", filter.isSelected(controller, allSelected)) {
            if (filter == GraphRelationshipFilter.ALL) {
                controller.selectAllRelationshipFilters()
            } else {
                controller.toggleRelationshipFilter(filter)
            }
        }.also { button ->
            val stats = model.relationshipStats(filter)
            val category = filter.attributeValue
            button.className = "srcx-dashboard__architecture-kind-filter-button is-kind-$category"
            button.setAttribute("data-srcx-relationship-kind", category)
            button.disabled = !enabled
            button.title = "${filter.canonicalLabel}: ${stats.records} records available in the loaded scope"
            button.appendChild(htmlElement("i").also { swatch -> swatch.setAttribute("aria-hidden", "true") })
            button.appendChild(htmlElement("span", text = filter.canonicalLabel))
            button.appendChild(
                htmlElement(
                    "strong",
                    "srcx-dashboard__architecture-kind-filter-count",
                    stats.records.toString(),
                ).also { count ->
                    count.setAttribute(
                        "aria-label",
                        "${stats.records} relationship records available in the loaded scope",
                    )
                },
            )
        }
}

private data class RelationshipStats(
    val records: Int,
)

private val GraphRelationshipFilter.attributeValue: String
    get() = name.lowercase()

private val GraphRelationshipFilter.canonicalLabel: String
    get() =
        when (this) {
            GraphRelationshipFilter.ALL -> "All relationships"
            GraphRelationshipFilter.INHERITANCE -> "Inheritance & implementation"
            GraphRelationshipFilter.CALLS -> "Call / construct records"
            GraphRelationshipFilter.REFERENCES -> "Type & member references"
            GraphRelationshipFilter.IMPORTS -> "Imports"
        }

private fun GraphRelationshipFilter.isSelected(
    controller: AtlasGraphController,
    allSelected: Boolean,
): Boolean =
    if (this == GraphRelationshipFilter.ALL) {
        allSelected
    } else {
        this in controller.relationshipFilters
    }

private fun AtlasDomSurfaceModel.relationshipStats(filter: GraphRelationshipFilter): RelationshipStats {
    val relationships = loadedProjects().flatMap(ProjectGraphShard::relationships).distinctBy { item -> item.id }
    if (relationships.isNotEmpty()) {
        val allowedKinds =
            if (filter == GraphRelationshipFilter.ALL) {
                selectableRelationshipFilters.flatMapTo(mutableSetOf(), GraphRelationshipFilter::allowedKinds)
            } else {
                filter.allowedKinds
            }
        return RelationshipStats(relationships.count { relationship -> relationship.kind in allowedKinds })
    }
    return frame.relationshipStats(filter)
}

private fun AtlasFrame?.relationshipStats(filter: GraphRelationshipFilter): RelationshipStats {
    if (this == null) return RelationshipStats(records = 0)
    val internalRecords = if (filter == GraphRelationshipFilter.ALL) nodes.sumOf { it.internalRecordCount } else 0
    val allowedKinds = filter.allowedKinds.mapTo(mutableSetOf()) { kind -> kind.name }
    val matchingEdges =
        if (filter == GraphRelationshipFilter.ALL) {
            edges
        } else {
            edges.filter { edge -> edge.kindCounts.any { count -> count.key in allowedKinds } }
        }
    val edgeRecords =
        if (filter == GraphRelationshipFilter.ALL) {
            matchingEdges.sumOf { edge -> edge.recordCount }
        } else {
            matchingEdges.sumOf { edge ->
                edge.kindCounts.filter { count -> count.key in allowedKinds }.sumOf { count -> count.count }
            }
        }
    return RelationshipStats(records = edgeRecords + internalRecords)
}

private fun AtlasGraphController.relationshipFilterSummary(): String =
    when {
        allRelationshipFiltersSelected -> "All relationships"
        relationshipFilters.isEmpty() -> "No relationships"
        relationshipFilters.size == 1 -> relationshipFilters.single().canonicalLabel
        else -> "${relationshipFilters.size} relationship kinds"
    }
