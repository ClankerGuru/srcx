package zone.clanker.docx.web.atlas.dom

import org.w3c.dom.HTMLDivElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.EventTarget
import org.w3c.dom.events.KeyboardEvent
import zone.clanker.docx.web.atlas.AtlasController
import zone.clanker.docx.web.atlas.GraphViewportMode
import zone.clanker.docx.web.atlas.bridgePrimarySelection
import zone.clanker.docx.web.atlas.bridgeSecondarySelection
import zone.clanker.docx.web.atlas.dom.detail.AtlasDomDetailRenderer
import zone.clanker.docx.web.atlas.dom.detail.AtlasPagedEvidenceActions
import zone.clanker.docx.web.atlas.dom.detail.renderAtlasDetail
import zone.clanker.docx.web.atlas.dom.search.AtlasGlobalSearchRenderModel
import zone.clanker.docx.web.atlas.dom.search.AtlasGlobalSearchRenderer
import zone.clanker.docx.web.atlas.recordBridgeLayout
import zone.clanker.docx.web.atlas.searchOverlayIsOpen
import zone.clanker.docx.web.atlas.toggleBridgeSecondary
import zone.clanker.docx.web.evidence.WorkspaceEvidenceInspectionRuntime
import kotlin.js.ExperimentalWasmJsInterop

internal class AtlasDomRuntime(
    private val renderer: AtlasDomRenderer = AtlasDomRenderer(),
    private val searchRenderer: AtlasGlobalSearchRenderer = AtlasGlobalSearchRenderer(),
    private val bridge: AtlasGraphBridgeRuntime = AtlasGraphBridgeRuntime(),
) {
    private val runtimeOwnerId = nextAtlasDomRuntimeOwnerId()
    private val exactEvidence = WorkspaceEvidenceInspectionRuntime()
    private val pagedEvidenceActions =
        AtlasPagedEvidenceActions(
            onPrevious = exactEvidence::previous,
            onNext = exactEvidence::next,
            onSelect = exactEvidence::selectOccurrence,
        )
    private val detailEvidence: AtlasDomDetailRenderer
        get() = AtlasDomDetailRenderer(exactEvidence.state, pagedEvidenceActions)
    private var interopHost: HTMLDivElement? = null
    private var root: HTMLDivElement? = null
    private var host: HTMLElement? = null
    private var controller: AtlasController? = null
    private var model: AtlasDomSurfaceModel? = null
    private var actions: AtlasDomActions? = null
    private val interactionScroll = AtlasInteractionScrollRuntime(root = { root }, host = { host })
    private var pendingDetailFocus = false
    private var detailVisible = false
    private val detailRetention = AtlasNodeDetailRetention()
    private val renderCurrentDetail: () -> Unit = {
        val currentRoot = root
        val currentModel = model
        val currentController = controller
        if (currentRoot != null && currentModel != null && currentController != null) {
            val preservedScrollTop = interactionScroll.preservedScrollTop
            renderAtlasDetail(
                currentRoot,
                currentModel,
                currentController.graph,
                detailEvidence,
            ) {
                currentController.clearInspection()
                exactEvidence.select(null)
                issueGraphCommand(CLEAR_SELECTION_COMMAND)
            }
            detailVisible = currentController.hasDetail
            detailRetention.record(currentModel, currentController.graphSelection, exactEvidence.state)
            preservedScrollTop?.let { scrollTop ->
                currentRoot.let { interactionScroll.restorePreserved(it, "detail-state") }
            }
        }
    }

    private val selectionListener: (Event) -> Unit = ::handleSelectionEvent
    private val viewportListener: (Event) -> Unit = ::handleViewportEvent
    private val openListener: (Event) -> Unit = ::handleOpenEvent
    private val layoutListener: (Event) -> Unit = ::handleLayoutEvent
    private val semanticZoomListener: (Event) -> Unit = ::handleSemanticZoomEvent
    private val searchShortcutListener: (Event) -> Unit = ::handleSearchShortcut
    private val pointerDownListener: (Event) -> Unit = interactionScroll::handleGraphPointerDown
    private val surfacePointerDownListener: (Event) -> Unit = interactionScroll::handleSurfacePointerDown
    private val surfaceWheelListener: (Event) -> Unit = interactionScroll::handleSurfaceWheel
    private val surfaceScrollListener: (Event) -> Unit = interactionScroll::handleSurfaceScroll
    private val fullscreenTransitionListener: (Event) -> Unit = interactionScroll::handleFullscreenTransition

    fun update(
        nextInteropHost: HTMLDivElement,
        nextModel: AtlasDomSurfaceModel,
        nextController: AtlasController,
        nextActions: AtlasDomActions,
    ) {
        val nextRoot = attachRoot(nextInteropHost)
        val preservedScrollTop = interactionScroll.preservedScrollTop
        controller = nextController
        model = nextModel
        actions = nextActions
        exactEvidence.attach(
            provider = nextActions.evidence.gatewayProvider,
            onStateChanged = { renderCurrentDetail() },
            onLocationRequested = nextActions.evidence.onLocationRequested,
        )
        val graphHost = requireNotNull(host)
        graphHost.setAttribute(RUNTIME_OWNER_ATTRIBUTE, runtimeOwnerId)
        graphHost.publishSessionRequestState(nextController)
        nextController.graph.bindViewportCommands(::issueGraphCommand)
        val timing = renderCore(nextRoot, nextModel, nextController, nextActions, graphHost)
        nextRoot.setAttribute("data-docx-atlas-snapshot-ready", bridge.hasSnapshot.toString())
        updateDetail(nextRoot, nextModel, nextController, nextActions)
        timing.detailReady()
        AtlasDomStatusRenderer.render(nextRoot, nextModel)
        if (pendingDetailFocus) {
            focusWithoutScroll(nextRoot.requiredButton("[data-srcx-detail-close]"))
            pendingDetailFocus = false
        }
        interactionScroll.finishDetailFocus(nextRoot)
        preservedScrollTop?.let {
            interactionScroll.restorePreserved(nextRoot, "surface-update", clearDetached = true)
        }
        timing.publish(graphHost)
    }

    private fun renderCore(
        nextRoot: HTMLDivElement,
        nextModel: AtlasDomSurfaceModel,
        nextController: AtlasController,
        nextActions: AtlasDomActions,
        graphHost: HTMLElement,
    ): AtlasDomRuntimeTiming =
        AtlasDomRuntimeTiming().also { timing ->
            renderer.render(nextRoot, nextModel, nextController, nextActions, ::issueGraphCommand)
            timing.rendererReady()
            searchRenderer.render(
                graphHost,
                AtlasGlobalSearchRenderModel(
                    state = nextModel.globalSearch,
                    selectedNodeIds = nextController.bridgeSecondarySelection.nodeIds,
                    actions = nextActions.search,
                    expanded = nextController.searchOverlayIsOpen,
                ),
            )
            timing.searchReady()
            bridge.update(graphHost, nextModel, nextController)
            timing.bridgeReady()
        }

    private fun updateDetail(
        nextRoot: HTMLDivElement,
        nextModel: AtlasDomSurfaceModel,
        nextController: AtlasController,
        nextActions: AtlasDomActions,
    ) {
        val hasDetail = nextController.hasDetail
        if (!hasDetail && !detailVisible) {
            if (exactEvidence.state != null) exactEvidence.select(null)
            return
        }
        val inspectionRequest =
            AtlasEvidenceRequestRuntime.inspectionRequest(nextModel, nextController.graphSelection)
        exactEvidence.select(inspectionRequest)
        if (hasDetail) {
            AtlasEvidenceRequestRuntime.requestSource(
                nextModel,
                nextController.graphSelection,
                policy =
                    AtlasEvidenceRequestRuntime.SourceRequestPolicy(
                        retryFailure = false,
                        hasExactInspection = inspectionRequest != null,
                    ),
                nextActions.evidence,
            )
        }
        if (detailRetention.matches(nextModel, nextController.graphSelection, exactEvidence.state)) return
        renderAtlasDetail(
            nextRoot,
            nextModel,
            nextController.graph,
            detailEvidence,
        ) {
            nextController.clearInspection()
            exactEvidence.select(null)
            issueGraphCommand(CLEAR_SELECTION_COMMAND)
        }
        detailVisible = hasDetail
        detailRetention.record(nextModel, nextController.graphSelection, exactEvidence.state)
    }

    fun release(releasedInteropHost: HTMLDivElement) {
        val detachingSurface = interopHost === releasedInteropHost
        interactionScroll.release(detachingSurface)
        val graphHost = host
        val ownsRenderedSurface = graphHost?.getAttribute(RUNTIME_OWNER_ATTRIBUTE) == runtimeOwnerId
        if (ownsRenderedSurface) {
            root?.let(renderer::release)
            searchRenderer.release()
        }
        graphHost?.removeEventListener(SELECTION_EVENT, selectionListener)
        graphHost?.removeEventListener(VIEWPORT_EVENT, viewportListener)
        graphHost?.removeEventListener(OPEN_EVENT, openListener)
        graphHost?.removeEventListener(LAYOUT_EVENT, layoutListener)
        graphHost?.removeEventListener(SEMANTIC_ZOOM_EVENT, semanticZoomListener)
        graphHost?.removeEventListener("keydown", searchShortcutListener)
        graphHost?.removeEventListener("pointerdown", pointerDownListener, true)
        root?.removeEventListener("pointerdown", surfacePointerDownListener, true)
        root?.removeEventListener("wheel", surfaceWheelListener, true)
        root?.removeEventListener("scroll", surfaceScrollListener)
        root?.removeEventListener(FULLSCREEN_TRANSITION_EVENT, fullscreenTransitionListener)
        if (ownsRenderedSurface) controller?.graph?.unbindViewportCommands()
        exactEvidence.release()
        if (ownsRenderedSurface) {
            bridge.release(graphHost)
            graphHost.removeAttribute(RUNTIME_OWNER_ATTRIBUTE)
        }
        if (detachingSurface) {
            interopHost = null
            root = null
            host = null
            detailVisible = false
            detailRetention.clear()
        }
        controller = null
        model = null
        actions = null
    }

    private fun attachRoot(nextInteropHost: HTMLDivElement): HTMLDivElement {
        root?.takeIf { interopHost === nextInteropHost }?.let { return it }
        check(root == null && interopHost == null) { "DOCX Atlas DOM runtime cannot attach two surfaces" }
        interopHost = nextInteropHost
        val nextRoot =
            nextInteropHost.requiredElement("[data-docx-atlas-surface]") as? HTMLDivElement
                ?: error("DOCX Atlas surface must be a div")
        root = nextRoot
        nextRoot.id = ATLAS_ROOT_ID
        interactionScroll.attach(nextRoot)
        val graphHost = nextRoot.requiredHtmlElement("[data-docx-atlas-host]")
        host = graphHost
        graphHost.addEventListener(SELECTION_EVENT, selectionListener)
        graphHost.addEventListener(VIEWPORT_EVENT, viewportListener)
        graphHost.addEventListener(OPEN_EVENT, openListener)
        graphHost.addEventListener(LAYOUT_EVENT, layoutListener)
        graphHost.addEventListener(SEMANTIC_ZOOM_EVENT, semanticZoomListener)
        graphHost.addEventListener("keydown", searchShortcutListener)
        graphHost.addEventListener("pointerdown", pointerDownListener, true)
        nextRoot.addEventListener("pointerdown", surfacePointerDownListener, true)
        nextRoot.addEventListener("wheel", surfaceWheelListener, true)
        nextRoot.addEventListener("scroll", surfaceScrollListener)
        nextRoot.addEventListener(FULLSCREEN_TRANSITION_EVENT, fullscreenTransitionListener)
        return nextRoot
    }

    private fun issueGraphCommand(action: String) {
        val graphHost = host ?: return
        bridge.issue(graphHost, action, controller)
    }

    private fun handleSelectionEvent(
        @Suppress("UnusedParameter") event: Event,
    ) {
        val graphHost = host ?: return
        val currentController = controller ?: return
        if (graphHost.optionalAttribute(SELECTION_MODE_ATTRIBUTE) == "additive") {
            handleAdditiveSelection(graphHost)
            return
        }
        val selection = graphHost.selection()
        val shouldFocusDetail = interactionScroll.selectionShouldFocusDetail
        interactionScroll.preserveSelection(selection, currentController.graphSelection)
        root?.let(interactionScroll::restorePendingSelection)
        currentController.inspect(selection)
        val currentModel = model
        val currentActions = actions
        if (currentModel != null && currentActions != null) {
            val inspectionRequest = AtlasEvidenceRequestRuntime.inspectionRequest(currentModel, selection)
            AtlasEvidenceRequestRuntime.requestProject(currentModel, selection, currentActions.evidence)
            AtlasEvidenceRequestRuntime.requestSource(
                currentModel,
                selection,
                policy =
                    AtlasEvidenceRequestRuntime.SourceRequestPolicy(
                        retryFailure = true,
                        hasExactInspection = inspectionRequest != null,
                    ),
                currentActions.evidence,
            )
            exactEvidence.select(inspectionRequest)
        }
        renderSelectedDetail(selection, currentController, shouldFocusDetail)
    }

    private fun handleAdditiveSelection(graphHost: HTMLElement) {
        val target =
            graphHost
                .optionalAttribute(INTERACTION_NODE_ATTRIBUTE)
                ?.let { id -> id to true }
                ?: graphHost
                    .optionalAttribute(INTERACTION_EDGE_ATTRIBUTE)
                    ?.let { id -> id to false }
                ?: return
        controller?.toggleBridgeSecondary(target.first, target.second)
    }

    private fun handleOpenEvent(
        @Suppress("UnusedParameter") event: Event,
    ) {
        val graphHost = host ?: return
        val currentController = controller ?: return
        val nodeId = graphHost.optionalAttribute(OPEN_NODE_ATTRIBUTE) ?: return
        graphHost.openCanonicalNode(currentController, nodeId)
    }

    private fun handleLayoutEvent(
        @Suppress("UnusedParameter") event: Event,
    ) {
        val currentController = controller ?: return
        val interaction = host?.readLayoutInteraction() ?: return
        currentController.recordBridgeLayout(interaction)
    }

    private fun handleSemanticZoomEvent(
        @Suppress("UnusedParameter") event: Event,
    ) {
        val graphHost = host ?: return
        val nodeId = graphHost.optionalAttribute(SEMANTIC_NODE_ATTRIBUTE) ?: return
        val action = graphHost.optionalAttribute(SEMANTIC_ACTION_ATTRIBUTE) ?: return
        controller?.updateSemanticExpansion(nodeId, action == "expand")
    }

    private fun handleViewportEvent(
        @Suppress("UnusedParameter") event: Event,
    ) {
        if (!bridge.acceptsViewportEvents) return
        val graphHost = host ?: return
        val currentController = controller ?: return
        bridge.syncViewport(graphHost, currentController, GraphViewportMode.MANUAL)
    }

    private fun handleSearchShortcut(event: Event) {
        val keyEvent = event as? KeyboardEvent ?: return
        if (!keyEvent.isUnmodifiedSearchShortcut || keyEvent.target.isTextEditor) return
        keyEvent.preventDefault()
        root
            ?.requiredHtmlElement("[data-srcx-global-search-input]")
            ?.let(::focusWithoutScroll)
    }

    private fun renderSelectedDetail(
        selection: GraphSelection,
        currentController: AtlasController,
        shouldFocusDetail: Boolean,
    ) {
        val currentRoot = root ?: return
        val currentModel = model ?: return
        val shouldFocus =
            shouldFocusDetail &&
                selection != GraphSelection() &&
                currentRoot.requiredHtmlElement("[data-srcx-detail]").hidden
        renderAtlasDetail(
            currentRoot,
            currentModel,
            currentController.graph,
            detailEvidence,
        ) {
            controller?.clearInspection()
            exactEvidence.select(null)
            issueGraphCommand(CLEAR_SELECTION_COMMAND)
        }
        detailVisible = currentController.hasDetail
        detailRetention.record(currentModel, currentController.graphSelection, exactEvidence.state)
        if (shouldFocus) {
            pendingDetailFocus = true
            focusWithoutScroll(currentRoot.requiredButton("[data-srcx-detail-close]"))
        }
        interactionScroll.restorePreserved(currentRoot, "selection-detail")
        interactionScroll.finishDetailFocus(currentRoot)
    }
}

private fun HTMLElement.selection(): GraphSelection =
    GraphSelection(
        nodeId = optionalAttribute(NODE_SELECTION_ATTRIBUTE),
        edgeId = optionalAttribute(EDGE_SELECTION_ATTRIBUTE),
    )

private fun AtlasController.inspect(selection: GraphSelection) {
    when {
        selection.nodeId != null -> inspectNode(selection.nodeId)
        selection.edgeId != null -> inspectRelation(selection.edgeId)
        else -> clearInspection()
    }
}

private val AtlasController.graphSelection: GraphSelection
    get() = bridgePrimarySelection.let { selection -> GraphSelection(selection.nodeId, selection.relationId) }

private val AtlasController.hasDetail: Boolean
    get() = graphSelection != GraphSelection() || graph.findingEvidenceMessage != null

private fun HTMLElement.optionalAttribute(name: String): String? =
    getAttribute(name)?.takeIf(String::isNotBlank)

private val KeyboardEvent.isUnmodifiedSearchShortcut: Boolean
    get() = key == "/" && !metaKey && !ctrlKey && !altKey

private val EventTarget?.isTextEditor: Boolean
    get() {
        val element = this as? HTMLElement ?: return false
        return element.tagName == "INPUT" || element.tagName == "TEXTAREA" || element.isContentEditable
    }

@OptIn(ExperimentalWasmJsInterop::class)
@Suppress("UnusedParameter")
private fun focusWithoutScroll(element: HTMLElement): Unit =
    js(
        """queueMicrotask(() => {
            const focus = () => { if (element.isConnected) element.focus({ preventScroll: true }); };
            focus();
            requestAnimationFrame(focus);
        })""",
    )

private const val ATLAS_ROOT_ID = "docx-atlas-root"
private const val SELECTION_EVENT = "docx-atlas-selection"
private const val VIEWPORT_EVENT = "docx-atlas-viewport"
private const val OPEN_EVENT = "docx-atlas-open"
private const val LAYOUT_EVENT = "docx-atlas-layout"
private const val SEMANTIC_ZOOM_EVENT = "docx-atlas-semantic-zoom"
private const val NODE_SELECTION_ATTRIBUTE = "data-docx-atlas-selected-node-id"
private const val EDGE_SELECTION_ATTRIBUTE = "data-docx-atlas-selected-edge-id"
private const val SELECTION_MODE_ATTRIBUTE = "data-docx-atlas-selection-mode"
private const val INTERACTION_NODE_ATTRIBUTE = "data-docx-atlas-interaction-node-id"
private const val INTERACTION_EDGE_ATTRIBUTE = "data-docx-atlas-interaction-edge-id"
private const val OPEN_NODE_ATTRIBUTE = "data-docx-atlas-open-node-id"
private const val SEMANTIC_NODE_ATTRIBUTE = "data-docx-atlas-semantic-node-id"
private const val SEMANTIC_ACTION_ATTRIBUTE = "data-docx-atlas-semantic-action"
private const val CLEAR_SELECTION_COMMAND = "clear-selection"
private const val FULLSCREEN_TRANSITION_EVENT = "docx-atlas-fullscreen-transition"
private const val RUNTIME_OWNER_ATTRIBUTE = "data-docx-atlas-runtime-owner"

private var atlasDomRuntimeOwnerSequence: Int = 0

private fun nextAtlasDomRuntimeOwnerId(): String = "atlas-dom-runtime-${atlasDomRuntimeOwnerSequence++}"
