package zone.clanker.docx.web.atlas.dom.controls

import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLDivElement
import zone.clanker.docx.web.atlas.dom.AtlasDomSurfaceModel
import zone.clanker.docx.web.atlas.dom.atlasBuildColor
import zone.clanker.docx.web.atlas.dom.clearContent
import zone.clanker.docx.web.atlas.dom.requiredElement
import zone.clanker.docx.web.atlas.dom.requiredHtmlElement
import zone.clanker.docx.web.catalog.orderedBuilds
import zone.clanker.report.model.BuildSnapshot

internal class AtlasBuildNavigatorRenderer {
    private val virtualRail = AtlasVirtualizedChoiceRail("build")

    fun render(
        root: HTMLDivElement,
        model: AtlasDomSurfaceModel,
        onBuildSelected: (String?) -> Unit,
        onFirstBuildSelected: () -> Unit,
    ) {
        val builds = orderedBuilds(model.summary)
        val clearHost = root.requiredHtmlElement("[data-srcx-build-filter-clear]")
        clearHost.clearContent()
        val clearButton = allBuildsButton(model, builds, onBuildSelected)
        clearHost.appendChild(clearButton)
        virtualRail.render(
            root = root,
            choices = buildFilterChoices(model, builds, onBuildSelected, onFirstBuildSelected),
            clearButton = clearButton,
        )
        root.requiredElement("[data-srcx-build-filter-summary]").textContent = buildSummary(model, builds)
        updateScopeSelectionStatus(
            root = root,
            kind = "build",
            selectedCount = model.selectedBuildIds.count { buildId -> builds.any { build -> build.id == buildId } },
            availableCount = builds.size,
        )
    }

    private fun buildFilterChoices(
        model: AtlasDomSurfaceModel,
        builds: List<BuildSnapshot>,
        onBuildSelected: (String?) -> Unit,
        onFirstBuildSelected: () -> Unit,
    ): List<VirtualFilterChoice> =
        builds.map { build ->
            val projectCount = model.summary.projects.count { project -> project.buildId == build.id }
            val selected = build.id in model.selectedBuildIds
            VirtualFilterChoice(
                key = build.id,
                spec =
                    FilterButtonSpec(
                        identity = FilterIdentity(kind = "build", value = build.name),
                        copy =
                            FilterCopy(
                                label = build.name,
                                detail = "${build.kind.label} / $projectCount ${projectCount.projectsLabel()}",
                                compactDetail = null,
                            ),
                        state =
                            FilterButtonState(
                                color = atlasBuildColor(build.name),
                                selected = selected,
                                selectionMode = FilterSelectionMode.MULTIPLE,
                            ),
                    ),
                selectionState =
                    if (selected) FilterSelectionState.SELECTED else FilterSelectionState.UNSELECTED,
            ) {
                onBuildSelected(build.id)
                if (
                    model.selectedBuildIds.isEmpty() &&
                    model.summary.projects.any { project -> project.buildId == build.id }
                ) {
                    onFirstBuildSelected()
                }
            }
        }

    private fun allBuildsButton(
        model: AtlasDomSurfaceModel,
        builds: List<BuildSnapshot>,
        onBuildSelected: (String?) -> Unit,
    ): HTMLButtonElement =
        filterButton(
            FilterButtonSpec(
                identity = FilterIdentity(kind = "build", value = null),
                copy =
                    FilterCopy(
                        label = "All builds",
                        detail = "${builds.size} builds / ${model.summary.projects.size} projects",
                        compactDetail = null,
                    ),
                state =
                    FilterButtonState(
                        color = null,
                        selected = model.selectedBuildIds.isEmpty() && model.selectedProjectIds.isEmpty(),
                        selectionMode = FilterSelectionMode.MULTIPLE,
                    ),
            ),
        ) {
            onBuildSelected(null)
        }
}

private fun buildSummary(
    model: AtlasDomSurfaceModel,
    builds: List<BuildSnapshot>,
): String =
    builds.filter { build -> build.id in model.selectedBuildIds }.let { selected ->
        when (selected.size) {
            0 -> "All builds"
            1 -> selected.single().name
            else -> "${selected.size} builds"
        }
    }
