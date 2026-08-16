package zone.clanker.docx.web.application

import androidx.compose.ui.geometry.Offset
import zone.clanker.docx.web.atlas.AtlasController
import zone.clanker.docx.web.atlas.GraphLens
import zone.clanker.docx.web.atlas.updateFocusMap
import zone.clanker.docx.web.probe.DocxSmokeCommand
import zone.clanker.docx.web.state.ViewerState
import zone.clanker.report.model.ProjectGraphShard

internal fun dispatchAtlasCommand(
    command: DocxSmokeCommand,
    state: ViewerState,
    controller: AtlasController,
): AtlasCommandResult =
    when (command.name) {
        in workspaceCommandNames -> dispatchWorkspaceCommand(command, state, controller)
        in graphScopeCommandNames -> dispatchGraphScopeCommand(command, state, controller)
        in graphViewportCommandNames -> dispatchGraphViewportCommand(command, controller)
        in graphSelectionCommandNames -> dispatchGraphSelectionCommand(command, state, controller)
        else -> AtlasCommandResult.Rejected("Unknown DOCX smoke command: ${command.name}")
    }

internal fun selectProject(
    projectId: String,
    state: ViewerState.Ready?,
): AtlasCommandResult =
    if (
        state
            ?.site
            ?.summary
            ?.projects
            ?.any { project -> project.id == projectId } == true
    ) {
        AtlasCommandResult.SelectProject(projectId)
    } else {
        AtlasCommandResult.Rejected("Project is not present in the workspace catalog: $projectId")
    }

internal fun selectBuild(
    buildId: String,
    state: ViewerState.Ready?,
): AtlasCommandResult {
    if (buildId.isEmpty()) {
        return if (state?.site?.atlasOverview != null) {
            AtlasCommandResult.SelectBuild(null)
        } else {
            AtlasCommandResult.Rejected("The workspace overview is not available")
        }
    }
    return if (state
            ?.site
            ?.summary
            ?.builds
            ?.any { build -> build.id == buildId } == true
    ) {
        AtlasCommandResult.SelectBuild(buildId)
    } else {
        AtlasCommandResult.Rejected("Build is not present in the workspace catalog: $buildId")
    }
}

internal fun updateLens(
    value: String,
    controller: AtlasController,
): AtlasCommandResult =
    when (value) {
        "files" -> completed { controller.graph.updateLens(GraphLens.FILES) }
        "symbols" -> completed { controller.graph.updateLens(GraphLens.SYMBOLS) }
        "problems" -> completed { controller.graph.updateLens(GraphLens.PROBLEMS) }
        "cycles" -> completed { controller.graph.updateLens(GraphLens.CYCLES) }
        else -> AtlasCommandResult.Rejected("Graph lens must be files, symbols, problems, or cycles: $value")
    }

internal fun updateSourceSet(
    sourceSetId: String,
    state: ViewerState.Ready?,
    controller: AtlasController,
): AtlasCommandResult {
    if (sourceSetId.isEmpty()) return completed { controller.graph.updateSourceSet(null) }
    val sourceSetExists =
        state?.loadedProjects()?.any { project ->
            project.sourceSets.any { sourceSet -> sourceSet.id == sourceSetId }
        } == true
    return if (sourceSetExists) {
        completed { controller.graph.updateSourceSet(sourceSetId) }
    } else {
        AtlasCommandResult.Rejected("Source set is not present in the selected build/project scope: $sourceSetId")
    }
}

internal fun updateZoom(
    value: String,
    controller: AtlasController,
): AtlasCommandResult =
    when (value) {
        "in" -> completed { controller.graph.zoomBy(SMOKE_ZOOM_IN) }
        "out" -> completed { controller.graph.zoomBy(SMOKE_ZOOM_OUT) }
        else -> AtlasCommandResult.Rejected("Graph zoom must be in or out: $value")
    }

internal fun updatePan(
    value: String,
    controller: AtlasController,
): AtlasCommandResult {
    val coordinates = value.split(',').map(String::trim)
    val x = coordinates.getOrNull(0)?.toFloatOrNull()
    val y = coordinates.getOrNull(1)?.toFloatOrNull()
    return if (coordinates.size == 2 && x != null && y != null) {
        completed { controller.graph.panBy(Offset(x, y)) }
    } else {
        AtlasCommandResult.Rejected("Graph pan must be <x>,<y>: $value")
    }
}

internal fun selectNode(
    nodeId: String,
    state: ViewerState.Ready?,
    controller: AtlasController,
): AtlasCommandResult {
    val project =
        state
            ?.loadedProjects()
            ?.firstOrNull { project ->
                project.files.any { file -> file.id == nodeId } ||
                    project.symbols.any { symbol -> symbol.id == nodeId }
            } ?: return AtlasCommandResult.Rejected("Node is not present in the loaded build/project scope: $nodeId")
    return when (controller.graph.lens) {
        GraphLens.FILES ->
            if (project.files.any { file -> file.id == nodeId }) {
                completed {
                    controller.graph.focusFile(project, nodeId)
                    controller.inspectNode(nodeId)
                }
            } else {
                AtlasCommandResult.Rejected("File is not present in the selected project: $nodeId")
            }

        GraphLens.SYMBOLS ->
            if (project.symbols.any { symbol -> symbol.id == nodeId }) {
                completed {
                    controller.graph.focusNode(project, nodeId)
                    controller.inspectNode(nodeId)
                }
            } else {
                AtlasCommandResult.Rejected("Symbol is not present in the selected project: $nodeId")
            }

        GraphLens.PROBLEMS, GraphLens.CYCLES ->
            if (
                project.files.any { file -> file.id == nodeId } ||
                project.symbols.any { symbol -> symbol.id == nodeId }
            ) {
                completed {
                    controller.graph.focusCurrentLensNode(nodeId)
                    controller.inspectNode(nodeId)
                }
            } else {
                AtlasCommandResult.Rejected("Node is not present in the selected project: $nodeId")
            }
    }
}

internal fun selectEdge(
    edgeId: String,
    state: ViewerState.Ready?,
    controller: AtlasController,
): AtlasCommandResult {
    val project =
        state
            ?.loadedProjects()
            ?.firstOrNull { project -> project.relationships.any { relationship -> relationship.id == edgeId } }
    return if (project != null) {
        completed {
            controller.graph.focusEdge(project, edgeId)
            controller.inspectRelation(edgeId)
        }
    } else {
        AtlasCommandResult.Rejected("Relationship is not present in the loaded build/project scope: $edgeId")
    }
}

internal fun updateFocusMap(
    value: String,
    controller: AtlasController,
): AtlasCommandResult =
    when (value) {
        "on" -> completed { controller.updateFocusMap(true) }
        "off" -> completed { controller.updateFocusMap(false) }
        "toggle", "" -> completed(controller::toggleFocusMap)
        else -> AtlasCommandResult.Rejected("Focus map must be on, off, or toggle: $value")
    }

internal inline fun completed(action: () -> Unit): AtlasCommandResult {
    action()
    return AtlasCommandResult.Completed
}

private fun ViewerState.Ready.loadedProjects(): List<ProjectGraphShard> =
    (listOfNotNull(project) + buildProjects).distinctBy(ProjectGraphShard::projectId)

private const val SMOKE_ZOOM_IN = 1.16f
private const val SMOKE_ZOOM_OUT = 0.86f
