@file:Suppress("FunctionNaming")

package zone.clanker.docx.web.application

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import zone.clanker.docx.web.atlas.AtlasController
import zone.clanker.docx.web.atlas.atlasLens
import zone.clanker.docx.web.atlas.selectBuild
import zone.clanker.docx.web.atlas.selectProject
import zone.clanker.docx.web.atlas.session.AtlasRequestState
import zone.clanker.docx.web.atlas.session.workspaceGraphRequest
import zone.clanker.docx.web.atlas.settledGraphSlice
import zone.clanker.docx.web.probe.DocxSmokeCommand
import zone.clanker.docx.web.probe.DocxSmokeCommandSource
import zone.clanker.docx.web.site.WorkspaceSiteLoader
import zone.clanker.docx.web.site.selectAtlasOverview
import zone.clanker.docx.web.state.ViewerState
import zone.clanker.docx.web.state.selectScope
import zone.clanker.report.model.WorkspaceGraphFacet

@Composable
internal fun WorkspaceLoadEffects(
    loader: WorkspaceSiteLoader,
    state: ViewerState,
    controller: AtlasController,
    onStateChange: (ViewerState) -> Unit,
) {
    LaunchedEffect(Unit) {
        val loaded = loadWorkspaceCatalogState(loader)
        if (loaded is ViewerState.Ready) {
            controller.attachGeneration(loaded.site.manifest.generationId, loaded.site.summary)
        }
        onStateChange(loaded)
    }
    val readyState = state as? ViewerState.Ready
    val generationId = readyState?.site?.manifest?.generationId
    val selection = controller.scopeSelection
    val overviewLens = controller.graph.lens.atlasLens
    LaunchedEffect(generationId, selection, overviewLens) {
        val ready = readyState ?: return@LaunchedEffect
        if (selection.isOverview) {
            val overviewState =
                ready
                    .selectScope(selection.buildIds, selection.projectIds)
                    .copy(site = ready.site.selectAtlasOverview(overviewLens))
            if (overviewState != ready) onStateChange(overviewState)
            return@LaunchedEffect
        }
        val settled = settleSelectedScopeWithoutProjectShards(ready, selection)
        if (settled != ready) onStateChange(settled)
    }
}

@Composable
internal fun SmokeCommandEffects(
    commandSource: DocxSmokeCommandSource?,
    state: ViewerState,
    controller: AtlasController,
    onStateChange: (ViewerState) -> Unit,
) {
    var pendingProjectCommand by remember { mutableStateOf<PendingProjectCommand?>(null) }
    var pendingBuildCommand by remember { mutableStateOf<PendingBuildCommand?>(null) }
    val latestState by rememberUpdatedState(state)
    LaunchedEffect(commandSource) {
        if (commandSource != null) {
            pollSmokeCommands(
                source = commandSource,
                latestState = { latestState },
                controller = controller,
                onStateChange = onStateChange,
                pendingChanges =
                    PendingCommandChanges(
                        project = { pendingProjectCommand = it },
                        build = { pendingBuildCommand = it },
                    ),
            )
        }
    }
    SettleProjectCommandEffect(pendingProjectCommand, state, controller) {
        pendingProjectCommand = null
    }
    SettleBuildCommandEffect(pendingBuildCommand, state, controller) {
        pendingBuildCommand = null
    }
}

private suspend fun pollSmokeCommands(
    source: DocxSmokeCommandSource,
    latestState: () -> ViewerState,
    controller: AtlasController,
    onStateChange: (ViewerState) -> Unit,
    pendingChanges: PendingCommandChanges,
) {
    while (!source.isComplete()) {
        val command = source.poll()
        if (command == null) {
            delay(COMMAND_POLL_DELAY_MILLIS)
        } else {
            settleSmokeCommand(command, latestState(), controller, onStateChange, pendingChanges)
        }
    }
}

private suspend fun settleSmokeCommand(
    command: DocxSmokeCommand,
    state: ViewerState,
    controller: AtlasController,
    onStateChange: (ViewerState) -> Unit,
    pendingChanges: PendingCommandChanges,
) {
    when (val result = dispatchAtlasCommand(command, state, controller)) {
        AtlasCommandResult.Completed -> {
            if (command.changesGraphFrame) {
                awaitGraphFrameSettlement(controller)
            }
            delay(COMMAND_SETTLE_DELAY_MILLIS)
            controller.completeCommand(command.revision)
        }

        is AtlasCommandResult.Rejected -> controller.completeCommand(command.revision, result.reason)
        is AtlasCommandResult.SelectProject -> {
            val ready = state as? ViewerState.Ready
            when {
                ready?.selectedProjectId == result.projectId && controller.settledGraphSlice != null -> {
                    delay(COMMAND_SETTLE_DELAY_MILLIS)
                    controller.completeCommand(command.revision)
                }

                ready != null -> {
                    controller.selectProject(result.projectId)
                    pendingChanges.project(PendingProjectCommand(command.revision, result.projectId))
                    onStateChange(
                        ready.selectScope(controller.selectedBuildIds, controller.selectedProjectIds),
                    )
                }

                else -> controller.completeCommand(command.revision, "Workspace catalog is not ready")
            }
        }

        is AtlasCommandResult.SelectBuild -> {
            val ready = state as? ViewerState.Ready
            if (ready == null) {
                controller.completeCommand(command.revision, "Workspace catalog is not ready")
            } else {
                controller.selectBuild(result.buildId)
                pendingChanges.build(PendingBuildCommand(command.revision, result.buildId))
                onStateChange(
                    ready.selectScope(controller.selectedBuildIds, controller.selectedProjectIds),
                )
            }
        }
    }
}

@Composable
private fun SettleProjectCommandEffect(
    pending: PendingProjectCommand?,
    state: ViewerState,
    controller: AtlasController,
    onSettled: () -> Unit,
) {
    val readyState = state as? ViewerState.Ready
    LaunchedEffect(pending, readyState) {
        if (pending != null && readyState?.isSettledFor(pending) == true) {
            val error = readyState.projectError
            if (error == null) awaitGraphFrameSettlement(controller)
            delay(COMMAND_SETTLE_DELAY_MILLIS)
            controller.completeCommand(pending.revision, error)
            onSettled()
        }
    }
}

@Composable
private fun SettleBuildCommandEffect(
    pending: PendingBuildCommand?,
    state: ViewerState,
    controller: AtlasController,
    onSettled: () -> Unit,
) {
    val readyState = state as? ViewerState.Ready
    LaunchedEffect(pending, readyState) {
        if (pending != null && readyState?.isSettledFor(pending, controller) == true) {
            val error = readyState.buildError
            if (error == null) awaitGraphFrameSettlement(controller)
            delay(COMMAND_SETTLE_DELAY_MILLIS)
            controller.completeCommand(pending.revision, error)
            onSettled()
        }
    }
}

@Suppress("TooGenericExceptionCaught")
private suspend fun loadWorkspaceCatalogState(loader: WorkspaceSiteLoader): ViewerState =
    try {
        val site = loader.loadWorkspace()
        ViewerState.Ready(site = site)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        ViewerState.Failed(error.message ?: "The workspace report could not be loaded")
    }

private fun ViewerState.Ready.isSettledFor(command: PendingProjectCommand): Boolean =
    selectedProjectId == command.projectId && !projectLoading

private fun ViewerState.Ready.isSettledFor(
    command: PendingBuildCommand,
    controller: AtlasController,
): Boolean =
    selectedProjectId == null &&
        controller.selectedBuildIds == setOfNotNull(command.buildId) &&
        !buildLoading &&
        if (command.buildId == null) {
            site.atlasOverview != null
        } else {
            loadedBuildId == command.buildId || buildError != null
        }

private val DocxSmokeCommand.changesGraphFrame: Boolean
    get() = name in graphScopeCommandNames || name in graphSelectionCommandNames

private suspend fun awaitGraphFrameSettlement(controller: AtlasController) {
    snapshotFlow {
        val session = controller.session ?: return@snapshotFlow false
        val settled = session.request as? AtlasRequestState.Settled ?: return@snapshotFlow false
        settled.request == session.workspaceGraphRequest(WorkspaceGraphFacet.SOURCE)
    }.first { settled -> settled }
}

private const val COMMAND_POLL_DELAY_MILLIS = 32L
private const val COMMAND_SETTLE_DELAY_MILLIS = 64L

private data class PendingCommandChanges(
    val project: (PendingProjectCommand) -> Unit,
    val build: (PendingBuildCommand) -> Unit,
)
