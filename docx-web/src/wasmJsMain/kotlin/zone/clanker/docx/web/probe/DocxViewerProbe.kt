package zone.clanker.docx.web.probe

import zone.clanker.docx.web.atlas.AtlasController
import zone.clanker.docx.web.atlas.settledGraphSlice
import zone.clanker.docx.web.state.ViewerState
import zone.clanker.report.model.AtlasLens

internal data class DocxViewerProbe(
    val state: String,
    val contentHeight: Int = 0,
    val catalogContent: String? = null,
    val selectedProjectContent: String? = null,
)

internal fun ViewerState.browserProbe(
    contentHeight: Int,
    controller: AtlasController,
): DocxViewerProbe {
    val status = browserStatus(contentHeight, controller)
    if (status != "ready" || this !is ViewerState.Ready) {
        return DocxViewerProbe(state = status)
    }

    return DocxViewerProbe(
        state = status,
        contentHeight = contentHeight,
        catalogContent = site.summary.workspace.name,
        selectedProjectContent = selectedContent(controller),
    )
}

private fun ViewerState.Ready.selectedContent(controller: AtlasController): String {
    val projectId = selectedProjectId
    if (projectId == null) {
        val selectedBuildIds = controller.selectedBuildIds
        if (selectedBuildIds.isNotEmpty()) {
            val projects = site.summary.projects.filter { project -> project.buildId in selectedBuildIds }
            val projectIds = projects.mapTo(mutableSetOf()) { project -> project.id }
            val dashboardProjects =
                site.dashboard
                    .projects
                    .filter { project -> project.projectId in projectIds }
            return "Selected scope|projects=${projects.size}|" +
                "symbols=${dashboardProjects.sumOf { project -> project.symbolCount }}|" +
                "relationships=${controller.settledGraphSlice?.counts?.matchingRelationCount ?: 0}"
        }
        val overview = requireNotNull(site.atlasOverview)
        return "All workspace|nodes=${overview.nodes.size}|relationships=${overview.shownRelationshipRecordCount}|" +
            "findings=${overview.nodes.sumOf { node -> node.findingCount }}"
    }
    val selectedProject = site.summary.projects.single { project -> project.id == projectId }
    val dashboardProject = site.dashboard.projects.single { project -> project.projectId == projectId }
    return "${selectedProject.path}|symbols=${dashboardProject.symbolCount}|" +
        "relationships=${overviewRelationshipCount(projectId)}|" +
        "findings=${dashboardProject.findingIds.size}"
}

private fun ViewerState.Ready.overviewRelationshipCount(projectId: String): Int {
    val overview = requireNotNull(site.atlasOverviews?.frame(AtlasLens.FILES) ?: site.atlasOverview)
    val projectByNodeId = overview.nodes.associate { node -> node.id to node.projectId }
    val routed =
        overview.edges
            .filter { edge ->
                projectByNodeId[edge.sourceId] == projectId || projectByNodeId[edge.targetId] == projectId
            }.sumOf { edge -> edge.recordCount }
    val internal =
        overview.nodes
            .filter { node -> node.projectId == projectId }
            .sumOf { node -> node.internalRecordCount }
    return routed + internal
}

private fun ViewerState.browserStatus(
    contentHeight: Int,
    controller: AtlasController,
): String =
    when (this) {
        ViewerState.Loading -> "loading-catalog"
        is ViewerState.Failed -> "catalog-failed"
        is ViewerState.Ready -> browserStatus(contentHeight, controller)
    }

private fun ViewerState.Ready.browserStatus(
    contentHeight: Int,
    controller: AtlasController,
): String =
    when {
        projectError != null -> "project-failed"
        buildError != null -> "build-failed"
        buildLoading -> "loading-project-scope"
        selectedProjectId != null && (projectLoading || controller.settledGraphSlice == null) -> "loading-project"
        selectedProjectId == null && loadedProjectIds.isEmpty() && site.atlasOverview == null ->
            "atlas-overview-failed"
        contentHeight <= 0 -> "loading-layout"
        else -> "ready"
    }
