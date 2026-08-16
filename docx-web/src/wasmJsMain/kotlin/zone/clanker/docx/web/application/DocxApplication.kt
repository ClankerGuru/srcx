@file:Suppress("FunctionNaming")

package zone.clanker.docx.web.application

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import zone.clanker.docx.web.atlas.AtlasController
import zone.clanker.docx.web.atlas.clearBuilds
import zone.clanker.docx.web.atlas.clearProjects
import zone.clanker.docx.web.atlas.clearSecondaryNodes
import zone.clanker.docx.web.atlas.dom.AtlasDomActions
import zone.clanker.docx.web.atlas.dom.AtlasDomEvidenceActions
import zone.clanker.docx.web.atlas.dom.AtlasDomSurface
import zone.clanker.docx.web.atlas.dom.AtlasDomSurfaceBindings
import zone.clanker.docx.web.atlas.dom.search.AtlasGlobalSearchActions
import zone.clanker.docx.web.atlas.flyToSearchEntry
import zone.clanker.docx.web.atlas.replaceSourceSets
import zone.clanker.docx.web.atlas.session.AtlasOverlay
import zone.clanker.docx.web.atlas.session.AtlasOverlayChanged
import zone.clanker.docx.web.atlas.toggleBuild
import zone.clanker.docx.web.atlas.toggleProject
import zone.clanker.docx.web.atlas.toggleSecondaryNode
import zone.clanker.docx.web.evidence.WorkspaceEvidenceGatewayProvider
import zone.clanker.docx.web.evidence.browserWorkspaceEvidenceGateway
import zone.clanker.docx.web.finding.FindingEvidenceEffects
import zone.clanker.docx.web.finding.PendingFindingEvidence
import zone.clanker.docx.web.finding.requestFindingEvidence
import zone.clanker.docx.web.probe.DocxInteractionProbe
import zone.clanker.docx.web.probe.DocxSmokeCommandSource
import zone.clanker.docx.web.probe.DocxViewerProbe
import zone.clanker.docx.web.probe.browserProbe
import zone.clanker.docx.web.probe.interactionProbe
import zone.clanker.docx.web.site.GenerationProjectCache
import zone.clanker.docx.web.site.WorkspaceSiteLoader
import zone.clanker.docx.web.state.ViewerState
import zone.clanker.docx.web.state.WorkspaceGlobalSearchState
import zone.clanker.docx.web.state.selectScope
import zone.clanker.docx.web.ui.AtlasStatusTone
import zone.clanker.docx.web.ui.atlasStatusSurface
import zone.clanker.report.model.WorkspaceSearchEntry

@Composable
internal fun DocxApplication(
    loader: WorkspaceSiteLoader,
    commandSource: DocxSmokeCommandSource? = null,
    onProbeChanged: (DocxViewerProbe) -> Unit = {},
    onInteractionChanged: (DocxInteractionProbe?) -> Unit = {},
) {
    val application = remember { DocxApplicationState() }
    val projectCache = remember(loader) { GenerationProjectCache() }
    BindDocxApplicationEffects(loader, commandSource, application, projectCache)
    BindDocxApplicationProbes(application, onProbeChanged, onInteractionChanged)
    ViewerStateContent(
        state = application.viewerState,
        controller = application.controller,
        callbacks = viewerCallbacks(application, loader),
    )
}

@Composable
private fun BindDocxApplicationEffects(
    loader: WorkspaceSiteLoader,
    commandSource: DocxSmokeCommandSource?,
    application: DocxApplicationState,
    projectCache: GenerationProjectCache,
) {
    val controller = application.controller
    WorkspaceLoadEffects(loader, application.viewerState, controller) {
        application.viewerState = it
    }
    WorkspaceGraphEffects(loader, projectCache, application.viewerState, controller)
    val ready = application.ready
    LaunchedEffect(ready?.site?.manifest?.generationId) {
        ready?.site?.manifest?.generationId?.let { generationId ->
            application.globalSearch = application.globalSearch.attachGeneration(generationId)
        }
    }
    workspaceGlobalSearchEffects(loader, ready?.site, application.globalSearch) { completion ->
        application.globalSearch = application.globalSearch.accept(completion)
    }
    WorkspaceSearchFlyToEffects(application.pendingSearchTarget, controller) {
        application.pendingSearchTarget = null
    }
    EvidenceProjectEffects(loader, projectCache, application::ready) { generationId, evidence ->
        val current = application.ready
        if (current?.site?.manifest?.generationId == generationId && current.evidence != evidence) {
            application.viewerState = current.copy(evidence = evidence)
        }
    }
    SourceContentEffects(loader, application::ready) { generationId, source ->
        val current = application.ready
        if (current?.site?.manifest?.generationId == generationId) {
            val evidence = current.evidence.acceptSource(source)
            if (evidence != current.evidence) application.viewerState = current.copy(evidence = evidence)
        }
    }
    SmokeCommandEffects(commandSource, application.viewerState, controller) { application.viewerState = it }
    FindingEvidenceEffects(application.pendingFindingEvidence, application.viewerState, controller) {
        application.pendingFindingEvidence = it
    }
}

@Composable
private fun BindDocxApplicationProbes(
    application: DocxApplicationState,
    onProbeChanged: (DocxViewerProbe) -> Unit,
    onInteractionChanged: (DocxInteractionProbe?) -> Unit,
) {
    val currentOnProbeChanged by rememberUpdatedState(onProbeChanged)
    val currentOnInteractionChanged by rememberUpdatedState(onInteractionChanged)
    LaunchedEffect(application) {
        snapshotFlow { application.viewerState.browserProbe(application.contentHeight, application.controller) }
            .collect(currentOnProbeChanged)
    }
    LaunchedEffect(application) {
        snapshotFlow { application.viewerState.interactionProbe(application.controller) }
            .collect(currentOnInteractionChanged)
    }
}

private class DocxApplicationState {
    var viewerState by mutableStateOf<ViewerState>(ViewerState.Loading)
    var contentHeight by mutableStateOf(0)
    var pendingFindingEvidence by mutableStateOf<PendingFindingEvidence?>(null)
    var pendingSearchTarget by mutableStateOf<WorkspaceSearchEntry?>(null)
    var globalSearch by mutableStateOf(WorkspaceGlobalSearchState())
    val controller = AtlasController()

    val ready: ViewerState.Ready?
        get() = viewerState as? ViewerState.Ready
}

private fun viewerCallbacks(
    application: DocxApplicationState,
    loader: WorkspaceSiteLoader,
): ViewerCallbacks =
    ViewerCallbacks(
        onStateChange = { application.viewerState = it },
        onFindingEvidenceSelected = findingSelectionAction(application),
        evidence =
            evidenceActions(
                currentState = { application.ready },
                onStateChange = { application.viewerState = it },
                loader = loader,
            ),
        globalSearch = { application.globalSearch },
        search = globalSearchActions(application),
        onContentHeightChanged = { application.contentHeight = it },
    )

private fun findingSelectionAction(application: DocxApplicationState): (String) -> Unit = { findingId ->
    application.ready?.let { ready ->
        requestFindingEvidence(
            dashboardFindingId = findingId,
            state = ready,
            controller = application.controller,
            onStateChange = { application.viewerState = it },
            onPendingChange = { application.pendingFindingEvidence = it },
        )
    }
}

private fun globalSearchActions(application: DocxApplicationState): AtlasGlobalSearchActions =
    AtlasGlobalSearchActions(
        onQueryChanged = { query ->
            application.globalSearch = application.globalSearch.updateQuery(query)
            application.controller.graph.previewNode(null)
            application.controller.dispatch(AtlasOverlayChanged(AtlasOverlay.Search))
        },
        onActiveResultChanged = { key ->
            application.globalSearch = application.globalSearch.activate(key)
            application.controller.graph.previewNode(application.globalSearch.activeResult?.id)
        },
        onResultToggled = { entry ->
            application.controller.toggleSecondaryNode(entry.id)
        },
        onSelectionCleared = application.controller::clearSecondaryNodes,
        onTargetRequested = { entry ->
            if (application.ready != null) {
                application.controller.graph.previewNode(null)
                application.controller.flyToSearchEntry(entry)
                application.pendingSearchTarget = entry
            }
        },
        onOpened = { application.controller.dispatch(AtlasOverlayChanged(AtlasOverlay.Search)) },
        onDismissed = {
            application.controller.graph.previewNode(null)
            application.controller.dispatch(AtlasOverlayChanged(AtlasOverlay.Closed))
        },
    )

private fun evidenceActions(
    currentState: () -> ViewerState.Ready?,
    onStateChange: (ViewerState.Ready) -> Unit,
    loader: WorkspaceSiteLoader,
): AtlasDomEvidenceActions =
    AtlasDomEvidenceActions(
        onProjectRequested = { projectId ->
            val current = currentState()
            if (current != null) {
                val evidence = current.evidence.request(projectId)
                if (evidence != current.evidence) onStateChange(current.copy(evidence = evidence))
            }
        },
        onSourceRequested = { projectId, fileId ->
            val current = currentState()
            if (current != null) {
                val evidence = current.evidence.requestSource(projectId, fileId)
                if (evidence != current.evidence) onStateChange(current.copy(evidence = evidence))
            }
        },
        onLocationRequested = { projectId, fileId ->
            val current = currentState()
            if (current != null) {
                val projectIsLoaded =
                    (listOfNotNull(current.project, current.evidence.project) + current.buildProjects)
                        .any { project -> project.projectId == projectId }
                val evidence =
                    if (projectIsLoaded) {
                        current.evidence.requestSource(projectId, fileId)
                    } else {
                        current.evidence.request(projectId)
                    }
                if (evidence != current.evidence) onStateChange(current.copy(evidence = evidence))
            }
        },
        gatewayProvider =
            WorkspaceEvidenceGatewayProvider {
                currentState()?.let { current -> browserWorkspaceEvidenceGateway(loader, current.site) }
            },
    )

private data class ViewerCallbacks(
    val onStateChange: (ViewerState) -> Unit,
    val onFindingEvidenceSelected: (String) -> Unit,
    val evidence: AtlasDomEvidenceActions,
    val globalSearch: () -> WorkspaceGlobalSearchState,
    val search: AtlasGlobalSearchActions,
    val onContentHeightChanged: (Int) -> Unit,
)

@Composable
private fun ViewerStateContent(
    state: ViewerState,
    controller: AtlasController,
    callbacks: ViewerCallbacks,
) {
    Box(Modifier.fillMaxSize()) {
        when (state) {
            ViewerState.Loading ->
                atlasStatusSurface(
                    title = "Loading workspace catalog",
                    detail = "Reading the typed generation manifest and navigation-sized workspace summary…",
                    tone = AtlasStatusTone.LOADING,
                )

            is ViewerState.Failed ->
                atlasStatusSurface(
                    title = "Workspace catalog failed",
                    detail = state.message,
                    tone = AtlasStatusTone.ERROR,
                )

            is ViewerState.Ready ->
                ReadyViewer(
                    state,
                    controller,
                    callbacks,
                )
        }
    }
}

@Composable
private fun ReadyViewer(
    state: ViewerState.Ready,
    controller: AtlasController,
    callbacks: ViewerCallbacks,
) {
    val selectBuild: (String?) -> Unit = { buildId ->
        if (buildId == null) {
            controller.clearBuilds()
        } else {
            controller.toggleBuild(buildId)
        }
        callbacks.onStateChange(state.selectScope(controller.selectedBuildIds, controller.selectedProjectIds))
    }
    val selectProject: (String?) -> Unit = { projectId ->
        if (projectId == null) {
            controller.clearProjects()
        } else {
            controller.toggleProject(projectId)
        }
        callbacks.onStateChange(state.selectScope(controller.selectedBuildIds, controller.selectedProjectIds))
    }
    val selectSourceSets: (Set<String>) -> Unit = { sourceSetIds ->
        controller.replaceSourceSets(sourceSetIds)
    }
    val navigateHistory: (() -> Unit) -> Unit = { navigate ->
        navigate()
        callbacks.onStateChange(state.selectScope(controller.selectedBuildIds, controller.selectedProjectIds))
    }
    AtlasDomSurface(
        state = state,
        controller = controller,
        bindings =
            AtlasDomSurfaceBindings(
                globalSearch = callbacks.globalSearch,
                actions =
                    AtlasDomActions(
                        onBuildSelected = selectBuild,
                        onProjectSelected = selectProject,
                        onSourceSetsSelected = selectSourceSets,
                        onNavigateBack = { navigateHistory(controller::navigateBack) },
                        onNavigateForward = { navigateHistory(controller::navigateForward) },
                        onFindingEvidenceSelected = callbacks.onFindingEvidenceSelected,
                        evidence = callbacks.evidence,
                        search = callbacks.search,
                    ),
                onContentHeightChanged = callbacks.onContentHeightChanged,
            ),
        modifier = Modifier.fillMaxSize(),
    )
}
