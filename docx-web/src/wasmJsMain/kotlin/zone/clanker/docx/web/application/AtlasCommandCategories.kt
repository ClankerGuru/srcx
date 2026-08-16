package zone.clanker.docx.web.application

import zone.clanker.docx.web.atlas.AtlasController
import zone.clanker.docx.web.atlas.updateProjectFilter
import zone.clanker.docx.web.probe.DocxSmokeCommand
import zone.clanker.docx.web.state.ViewerState

internal val workspaceCommandNames =
    setOf("project-filter", "build-select", "project-select", "focus-map", "atlas-destroy", "atlas-remount")
internal val graphScopeCommandNames =
    setOf(
        "graph-lens",
        "graph-source-set",
        "graph-kind",
        "graph-search",
        "graph-search-target",
        "graph-relationship-count",
        "graph-relationship-direction",
    )
internal val graphViewportCommandNames = setOf("graph-fit", "graph-reset", "graph-zoom", "graph-pan")
internal val graphSelectionCommandNames = setOf("node-select", "edge-select")

internal fun dispatchWorkspaceCommand(
    command: DocxSmokeCommand,
    state: ViewerState,
    controller: AtlasController,
): AtlasCommandResult =
    when (command.name) {
        "project-filter" -> completed { controller.updateProjectFilter(command.argument) }
        "build-select" -> selectBuild(command.argument, state as? ViewerState.Ready)
        "project-select" -> selectProject(command.argument, state as? ViewerState.Ready)
        "focus-map" -> updateFocusMap(command.argument, controller)
        "atlas-destroy" -> completed { controller.graph.updateBridgeEnabled(false) }
        else -> completed { controller.graph.updateBridgeEnabled(true) }
    }

internal fun dispatchGraphScopeCommand(
    command: DocxSmokeCommand,
    state: ViewerState,
    controller: AtlasController,
): AtlasCommandResult {
    val readyState = state as? ViewerState.Ready
    if (
        readyState == null ||
        controller.scopeSelection.isOverview
    ) {
        return AtlasCommandResult.Rejected("Select a build or project before changing graph scope")
    }
    return when (command.name) {
        "graph-lens" -> updateLens(command.argument, controller)
        "graph-source-set" -> updateSourceSet(command.argument, readyState, controller)
        "graph-kind" -> updateRelationshipFilter(command.argument, controller)
        "graph-search" -> completed { controller.graph.updateSearch(command.argument) }
        "graph-search-target" -> updateSearchTarget(command.argument, controller)
        "graph-relationship-count" -> updateRelationshipCount(command.argument, controller)
        else -> updateRelationshipDirection(command.argument, controller)
    }
}

internal fun dispatchGraphViewportCommand(
    command: DocxSmokeCommand,
    controller: AtlasController,
): AtlasCommandResult =
    when (command.name) {
        "graph-fit" -> completed(controller.graph::fit)
        "graph-reset" -> completed(controller.graph::reset)
        "graph-zoom" -> updateZoom(command.argument, controller)
        else -> updatePan(command.argument, controller)
    }

internal fun dispatchGraphSelectionCommand(
    command: DocxSmokeCommand,
    state: ViewerState,
    controller: AtlasController,
): AtlasCommandResult =
    if (command.name == "node-select") {
        selectNode(command.argument, state as? ViewerState.Ready, controller)
    } else {
        selectEdge(command.argument, state as? ViewerState.Ready, controller)
    }
