package zone.clanker.docx.web.atlas.dom.controls

import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLDivElement
import zone.clanker.docx.web.atlas.AtlasGraphController
import zone.clanker.docx.web.atlas.GraphDeclarationFilter
import zone.clanker.docx.web.atlas.dom.AtlasDomSurfaceModel
import zone.clanker.docx.web.atlas.dom.buttonElement
import zone.clanker.docx.web.atlas.dom.clearContent
import zone.clanker.docx.web.atlas.dom.htmlElement
import zone.clanker.docx.web.atlas.dom.requiredElement
import zone.clanker.docx.web.atlas.dom.requiredHtmlElement
import zone.clanker.report.model.AtlasNodeType
import zone.clanker.report.model.ProjectGraphShard
import zone.clanker.report.model.SymbolKind
import zone.clanker.report.model.SymbolSnapshot

internal class AtlasDeclarationControlsRenderer {
    fun render(
        root: HTMLDivElement,
        model: AtlasDomSurfaceModel,
        controller: AtlasGraphController,
        enabled: Boolean,
    ) {
        val rail = root.requiredHtmlElement("[data-srcx-declaration-kind-options]")
        rail.clearContent()
        val symbolKinds = model.visibleSymbolKinds()
        val declarationsEnabled = enabled && (symbolKinds.isNotEmpty() || model.slice != null)
        val allButton =
            declarationButton(
                spec =
                    DeclarationButtonSpec(
                        value = "all",
                        label = "All declarations",
                        count = symbolKinds.size,
                        selected = controller.allDeclarationFiltersSelected,
                        enabled = declarationsEnabled,
                    ),
                onClick = controller::showAllDeclarations,
            )
        val buttons = mutableListOf(allButton)
        GraphDeclarationFilter.entries.forEach { filter ->
            buttons +=
                declarationButton(
                    spec =
                        DeclarationButtonSpec(
                            value = filter.name.lowercase(),
                            label = filter.label,
                            count = symbolKinds.count(filter.symbolKinds::contains),
                            selected = filter in controller.declarationFilters,
                            enabled = declarationsEnabled,
                        ),
                    onClick = { controller.toggleDeclarationFilter(filter) },
                ).also { button ->
                    if (filter == GraphDeclarationFilter.FUNCTIONS) {
                        button.title =
                            "Functions & methods: SRCX currently serializes both as Function declarations"
                    }
                }
        }
        buttons.forEach { button -> rail.appendChild(button) }
        wireControlChipGroup(root, buttons)
        root.requiredElement("[data-srcx-declaration-kind-summary]").textContent =
            controller.declarationFilterSummary()
    }

    private fun declarationButton(
        spec: DeclarationButtonSpec,
        onClick: () -> Unit,
    ): HTMLButtonElement =
        buttonElement("", spec.selected, onClick).also { button ->
            button.className = "srcx-dashboard__architecture-declaration-filter-button"
            button.setAttribute("data-srcx-declaration-kind", spec.value)
            button.disabled = !spec.enabled
            button.appendChild(htmlElement("span", text = spec.label))
            button.appendChild(
                htmlElement(
                    "strong",
                    "srcx-dashboard__architecture-kind-filter-count",
                    spec.count.toString(),
                ).also { badge -> badge.setAttribute("aria-hidden", "true") },
            )
            button.setAttribute("aria-label", "${spec.label}, ${spec.count} declarations")
        }
}

private fun AtlasDomSurfaceModel.visibleSymbolKinds(): List<SymbolKind> {
    val loaded =
        loadedProjects()
            .flatMap(ProjectGraphShard::symbols)
            .distinctBy(SymbolSnapshot::id)
            .map(SymbolSnapshot::kind)
    if (loaded.isNotEmpty()) return loaded
    return frame
        ?.nodes
        .orEmpty()
        .filter { node -> node.type == AtlasNodeType.SYMBOL }
        .mapNotNull { node -> node.kind?.let(SymbolKind::valueOf) }
}

private data class DeclarationButtonSpec(
    val value: String,
    val label: String,
    val count: Int,
    val selected: Boolean,
    val enabled: Boolean,
)

private fun AtlasGraphController.declarationFilterSummary(): String =
    when {
        allDeclarationFiltersSelected -> "All declarations"
        declarationFilters.isEmpty() -> "No declarations"
        declarationFilters.size == 1 -> declarationFilters.single().label
        else -> "${declarationFilters.size} declaration types"
    }
