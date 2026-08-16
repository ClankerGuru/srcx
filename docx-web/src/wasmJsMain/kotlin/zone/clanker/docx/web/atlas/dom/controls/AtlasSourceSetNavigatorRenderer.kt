package zone.clanker.docx.web.atlas.dom.controls

import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLDivElement
import zone.clanker.docx.web.atlas.AtlasGraphController
import zone.clanker.docx.web.atlas.dom.AtlasDomSurfaceModel
import zone.clanker.docx.web.atlas.dom.clearContent
import zone.clanker.docx.web.atlas.dom.requiredElement
import zone.clanker.docx.web.atlas.dom.requiredHtmlElement
import zone.clanker.docx.web.catalog.orderedSourceSets

internal class AtlasSourceSetNavigatorRenderer {
    private val virtualRail = AtlasVirtualizedChoiceRail("source-set")

    fun render(
        root: HTMLDivElement,
        model: AtlasDomSurfaceModel,
        controller: AtlasGraphController,
        onSourceSetsSelected: (Set<String>) -> Unit,
    ) {
        val rail = root.requiredHtmlElement("[data-srcx-source-set-filter]")
        val choices = sourceSetChoices(model)
        val clearHost = root.requiredHtmlElement("[data-srcx-source-set-filter-clear]")
        clearHost.clearContent()
        val clearButton =
            allSourceSetsButton(controller, choices.size, ownershipLabel(model), onSourceSetsSelected)
        clearHost.appendChild(clearButton)
        virtualRail.render(
            root = root,
            choices = choices.map { choice -> sourceSetFilterChoice(controller, choice, onSourceSetsSelected) },
            clearButton = clearButton,
        )
        rail.setAttribute("aria-label", "Filter ${ownershipLabel(model)} by source-set name")
        root.requiredElement("[data-srcx-source-set-filter-summary]").textContent =
            sourceSetSummary(choices, controller.sourceSetIds)
        updateScopeSelectionStatus(
            root = root,
            kind = "source-set",
            selectedCount = choices.count { choice -> choice.hasSelectedId(controller.sourceSetIds) },
            availableCount = choices.size,
        )
    }

    private fun allSourceSetsButton(
        controller: AtlasGraphController,
        sourceSetCount: Int,
        scopeLabel: String,
        onSourceSetsSelected: (Set<String>) -> Unit,
    ): HTMLButtonElement =
        filterButton(
            FilterButtonSpec(
                identity = FilterIdentity(kind = "source-set", value = null),
                copy =
                    FilterCopy(
                        label = "All source sets",
                        detail = "$sourceSetCount source-set names in $scopeLabel",
                    ),
                state =
                    FilterButtonState(
                        color = null,
                        selected = controller.sourceSetIds.isEmpty(),
                        selectionMode = FilterSelectionMode.MULTIPLE,
                    ),
            ),
        ) { onSourceSetsSelected(emptySet()) }

    private fun sourceSetFilterChoice(
        controller: AtlasGraphController,
        choice: SourceSetChoice,
        onSourceSetsSelected: (Set<String>) -> Unit,
    ): VirtualFilterChoice {
        val selected = choice.sourceSetIds.all(controller.sourceSetIds::contains)
        val partiallySelected = !selected && choice.hasSelectedId(controller.sourceSetIds)
        return VirtualFilterChoice(
            key = choice.name,
            spec =
                FilterButtonSpec(
                    identity = FilterIdentity(kind = "source-set", value = choice.name),
                    copy =
                        FilterCopy(
                            label = choice.name,
                            detail =
                                "${choice.fileCount} ${choice.fileCount.sourceFilesLabel()} / " +
                                    "${choice.projectCount} ${choice.projectCount.projectsLabel()}",
                        ),
                    state =
                        FilterButtonState(
                            color = null,
                            selected = selected,
                            selectionMode = FilterSelectionMode.MULTIPLE,
                        ),
                ),
            selectionState =
                when {
                    selected -> FilterSelectionState.SELECTED
                    partiallySelected -> FilterSelectionState.PARTIAL
                    else -> FilterSelectionState.UNSELECTED
                },
            attributes = mapOf("data-srcx-source-set-id-count" to choice.sourceSetIds.size.toString()),
        ) {
            onSourceSetsSelected(
                if (selected) {
                    controller.sourceSetIds - choice.sourceSetIds
                } else {
                    controller.sourceSetIds + choice.sourceSetIds
                },
            )
        }
    }

    private fun sourceSetChoices(model: AtlasDomSurfaceModel): List<SourceSetChoice> {
        val effectiveProjectIds = effectiveSourceProjectIds(model)
        return orderedSourceSets(model.summary)
            .filter { sourceSet -> sourceSet.projectId in effectiveProjectIds }
            .groupBy { sourceSet -> sourceSet.name }
            .map { (name, matches) ->
                SourceSetChoice(
                    name = name,
                    sourceSetIds = matches.map { sourceSet -> sourceSet.id }.toSet(),
                    fileCount = matches.sumOf { sourceSet -> sourceSet.fileCount },
                    projectCount = matches.map { sourceSet -> sourceSet.projectId }.toSet().size,
                )
            }.sortedWith(sourceSetChoiceComparator)
    }

    private fun effectiveSourceProjectIds(model: AtlasDomSurfaceModel): Set<String> =
        when {
            model.selectedProjectIds.isNotEmpty() -> model.selectedProjectIds
            model.selectedBuildIds.isNotEmpty() ->
                model.summary.projects
                    .filter { project -> project.buildId in model.selectedBuildIds }
                    .map { project -> project.id }
                    .toSet()
            else ->
                model.summary.projects
                    .map { project -> project.id }
                    .toSet()
        }
}

private data class SourceSetChoice(
    val name: String,
    val sourceSetIds: Set<String>,
    val fileCount: Int,
    val projectCount: Int,
)

private fun SourceSetChoice.hasSelectedId(selectedIds: Set<String>): Boolean =
    sourceSetIds.any(selectedIds::contains)

private val sourceSetChoiceComparator =
    compareBy<SourceSetChoice>(
        { choice -> choice.name.standardSourceSetRank() },
        { choice -> choice.name.lowercase() },
        SourceSetChoice::name,
    )

private fun String.standardSourceSetRank(): Int =
    when (lowercase()) {
        "main" -> 0
        "test" -> 1
        else -> 2
    }

private fun Int.sourceFilesLabel(): String = if (this == 1) "source file" else "source files"

private fun sourceSetSummary(
    choices: List<SourceSetChoice>,
    selectedIds: Set<String>,
): String {
    val selectedChoices = choices.filter { choice -> choice.hasSelectedId(selectedIds) }
    return when {
        selectedIds.isEmpty() -> "All source sets"
        selectedChoices.size == 1 -> selectedChoices.single().name
        selectedChoices.isNotEmpty() -> "${selectedChoices.size} source-set names"
        else -> "${selectedIds.size} selected sources"
    }
}

private fun ownershipLabel(model: AtlasDomSurfaceModel): String =
    when {
        model.selectedProjectIds.size == 1 -> model.selectedProject?.path ?: "the selected project"
        model.selectedProjectIds.isNotEmpty() -> "${model.selectedProjectIds.size} selected projects"
        model.selectedBuildIds.size == 1 -> selectedBuild(model)?.name ?: "the selected build"
        model.selectedBuildIds.isNotEmpty() -> "${model.selectedBuildIds.size} selected builds"
        else -> "all builds and projects"
    }
