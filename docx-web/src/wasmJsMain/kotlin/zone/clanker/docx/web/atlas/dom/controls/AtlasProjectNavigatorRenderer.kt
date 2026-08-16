package zone.clanker.docx.web.atlas.dom.controls

import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLDivElement
import zone.clanker.docx.web.atlas.dom.AtlasDomActions
import zone.clanker.docx.web.atlas.dom.AtlasDomSurfaceModel
import zone.clanker.docx.web.atlas.dom.atlasBuildColor
import zone.clanker.docx.web.atlas.dom.clearContent
import zone.clanker.docx.web.atlas.dom.requiredElement
import zone.clanker.docx.web.atlas.dom.requiredHtmlElement
import zone.clanker.docx.web.catalog.orderedBuilds
import zone.clanker.docx.web.catalog.orderedProjects
import zone.clanker.report.model.BuildSnapshot
import zone.clanker.report.model.ProjectSnapshot

internal class AtlasProjectNavigatorRenderer {
    private val virtualRail = AtlasVirtualizedChoiceRail("project")

    fun render(
        root: HTMLDivElement,
        model: AtlasDomSurfaceModel,
        actions: AtlasDomActions,
        onFirstProjectSelected: () -> Unit,
    ) {
        val rail = root.requiredHtmlElement("[data-srcx-project-filter]")
        val selectedBuilds = orderedBuilds(model.summary).filter { build -> build.id in model.selectedBuildIds }
        val projects =
            orderedProjects(model.summary)
                .filter { project -> selectedBuilds.isEmpty() || project.buildId in model.selectedBuildIds }
        val clearHost = root.requiredHtmlElement("[data-srcx-project-filter-clear]")
        clearHost.clearContent()
        val clearButton = allProjectsButton(model, actions, selectedBuilds, projects.size)
        clearHost.appendChild(clearButton)
        virtualRail.render(
            root = root,
            choices = projectFilterChoices(model, actions, selectedBuilds, projects, onFirstProjectSelected),
            clearButton = clearButton,
        )
        rail.setAttribute(
            "aria-label",
            if (selectedBuilds.size == 1) {
                "Filter ${selectedBuilds.single().name} by project"
            } else {
                "Filter atlas by projects in the selected builds"
            },
        )
        val selectedProjects = projects.filter { project -> project.id in model.selectedProjectIds }
        root.requiredElement("[data-srcx-project-filter-summary]").textContent =
            when (selectedProjects.size) {
                0 -> "All projects"
                1 -> selectedProjects.single().path
                else -> "${selectedProjects.size} projects"
            }
        updateScopeSelectionStatus(
            root = root,
            kind = "project",
            selectedCount =
                model.selectedProjectIds.count { projectId ->
                    projects.any { project -> project.id == projectId }
                },
            availableCount = projects.size,
        )
    }

    private fun projectFilterChoices(
        model: AtlasDomSurfaceModel,
        actions: AtlasDomActions,
        selectedBuilds: List<BuildSnapshot>,
        projects: List<ProjectSnapshot>,
        onFirstProjectSelected: () -> Unit,
    ): List<VirtualFilterChoice> =
        projects.map { project ->
            val ownerBuild = model.summary.builds.single { build -> build.id == project.buildId }
            val label = if (selectedBuilds.size == 1) project.path else "${ownerBuild.name} / ${project.path}"
            val selected = project.id in model.selectedProjectIds
            VirtualFilterChoice(
                key = project.id,
                spec =
                    FilterButtonSpec(
                        identity = FilterIdentity(kind = "project", value = project.path),
                        copy =
                            FilterCopy(
                                label = label,
                                detail =
                                    "${project.sourceDirectories.size} source directories; " +
                                        "toggle this project in the complete selected-scope frame",
                            ),
                        state =
                            FilterButtonState(
                                color = atlasBuildColor(ownerBuild.name),
                                selected = selected,
                                selectionMode = FilterSelectionMode.MULTIPLE,
                            ),
                    ),
                selectionState =
                    if (selected) FilterSelectionState.SELECTED else FilterSelectionState.UNSELECTED,
            ) {
                actions.onProjectSelected(project.id)
                if (
                    model.selectedProjectIds.isEmpty() &&
                    model.summary.sourceSets.any { sourceSet -> sourceSet.projectId == project.id }
                ) {
                    onFirstProjectSelected()
                }
            }
        }

    private fun allProjectsButton(
        model: AtlasDomSurfaceModel,
        actions: AtlasDomActions,
        selectedBuilds: List<BuildSnapshot>,
        projectCount: Int,
    ): HTMLButtonElement =
        filterButton(
            FilterButtonSpec(
                identity = FilterIdentity(kind = "project", value = null),
                copy =
                    FilterCopy(
                        label = "All projects",
                        detail =
                            if (selectedBuilds.isEmpty()) {
                                "$projectCount projects across every build"
                            } else if (selectedBuilds.size == 1) {
                                "$projectCount ${projectCount.projectsLabel()} in ${selectedBuilds.single().name}"
                            } else {
                                "$projectCount ${projectCount.projectsLabel()} in ${selectedBuilds.size} builds"
                            },
                    ),
                state =
                    FilterButtonState(
                        color = selectedBuilds.singleOrNull()?.let { build -> atlasBuildColor(build.name) },
                        selected = model.selectedProjectIds.isEmpty(),
                        selectionMode = FilterSelectionMode.MULTIPLE,
                    ),
            ),
        ) {
            actions.onProjectSelected(null)
        }
}
