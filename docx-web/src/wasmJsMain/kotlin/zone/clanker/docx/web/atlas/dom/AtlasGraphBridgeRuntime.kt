package zone.clanker.docx.web.atlas.dom

import org.w3c.dom.HTMLElement
import zone.clanker.docx.web.atlas.AtlasBridgeCamera
import zone.clanker.docx.web.atlas.AtlasBridgeViewport
import zone.clanker.docx.web.atlas.AtlasController
import zone.clanker.docx.web.atlas.AtlasSemanticMapDelivery
import zone.clanker.docx.web.atlas.GraphViewportMode
import zone.clanker.docx.web.atlas.bridgeCamera
import zone.clanker.docx.web.atlas.bridgeCameraHasMeasuredViewport
import zone.clanker.docx.web.atlas.bridgeLayerNames
import zone.clanker.docx.web.atlas.bridgePrimarySelection
import zone.clanker.docx.web.atlas.bridgeSecondarySelection
import zone.clanker.docx.web.atlas.recordBridgeViewport
import zone.clanker.docx.web.atlas.session.AtlasCameraCoordinateSpace

internal class AtlasGraphBridgeRuntime {
    private var graphMounted = false
    private var mapMounted = false
    private var lastFrameJson: String? = null
    private var lastSemanticRequestRevision: Long? = null
    private var lastSliceJson: String? = null
    private var lastSelection = GraphSelection()
    private var lastSecondaryNodes: Set<String> = emptySet()
    private var lastSecondaryRelations: Set<String> = emptySet()
    private var applyingSessionState = false
    private var awaitingCanvasFit = false
    private var pendingCanvasCamera: AtlasBridgeCamera? = null

    val acceptsViewportEvents: Boolean
        get() = !applyingSessionState

    val hasSnapshot: Boolean
        get() = isMounted

    fun update(
        host: HTMLElement,
        model: AtlasDomSurfaceModel,
        controller: AtlasController,
    ) {
        applyingSessionState = true
        try {
            if (!model.bridgeEnabled) {
                release(host)
                controller.graph.updateViewportHeight(0f)
            } else {
                updateEnabledBridge(host, model, controller)
            }
        } finally {
            applyingSessionState = false
        }
    }

    private fun updateEnabledBridge(
        host: HTMLElement,
        model: AtlasDomSurfaceModel,
        controller: AtlasController,
    ) {
        when {
            model.preserveDetailedOverview ->
                updateLegacyGraph(host, model.frameJson, controller)
            model.semanticMapDelivery != null -> updateSemanticMap(host, model.semanticMapDelivery, controller)
            mapMounted -> {
                syncSelection(host, controller)
                syncSecondarySelection(host, controller)
            }
        }
    }

    private fun updateSemanticMap(
        host: HTMLElement,
        delivery: AtlasSemanticMapDelivery,
        controller: AtlasController,
    ) {
        val wasMounted = mapMounted
        val requestChanged = lastSemanticRequestRevision != delivery.requestRevision
        val canonicalCamera = controller.bridgeCamera
        if (!wasMounted || requestChanged) {
            awaitingCanvasFit = true
            pendingCanvasCamera =
                canonicalCamera?.takeIf { camera ->
                    !requestChanged &&
                        camera.coordinateSpace == AtlasCameraCoordinateSpace.SEMANTIC_MAP
                }
        }
        val restoreSessionCamera =
            !awaitingCanvasFit &&
                canonicalCamera?.coordinateSpace == AtlasCameraCoordinateSpace.SEMANTIC_MAP &&
                (wasMounted || controller.bridgeCameraHasMeasuredViewport)
        updateMap(host, delivery)
        syncLayers(host, controller)
        if (restoreSessionCamera) applyCamera(host, canonicalCamera)
        syncSelection(host, controller)
        syncSecondarySelection(host, controller)
        syncViewport(host, controller, controller.graph.mode)
    }

    private fun updateLegacyGraph(
        host: HTMLElement,
        frameJson: String?,
        controller: AtlasController,
    ) {
        awaitingCanvasFit = false
        pendingCanvasCamera = null
        if (mapMounted) {
            AtlasMapInterop.destroy(host)
            mapMounted = false
            lastSemanticRequestRevision = null
            lastSliceJson = null
        }
        host.removeAttribute("data-docx-atlas-detailed-overview-parked")
        if (frameJson == null) {
            host.setAttribute("data-docx-atlas-detailed-overview-pending", "true")
            if (graphMounted) {
                syncSelection(host, controller)
                syncViewport(host, controller, controller.graph.mode)
            }
            return
        }
        host.removeAttribute("data-docx-atlas-detailed-overview-pending")
        if (!graphMounted) {
            AtlasD3Interop.mount(host, frameJson)
            graphMounted = true
        } else if (lastFrameJson != frameJson) {
            AtlasD3Interop.update(host, frameJson)
        }
        lastFrameJson = frameJson
        syncSelection(host, controller)
        syncViewport(host, controller, controller.graph.mode)
    }

    fun issue(
        host: HTMLElement,
        action: String,
        controller: AtlasController?,
    ) {
        if (!isMounted) return
        command(host, action)
        controller?.let { current -> syncViewport(host, current, current.graph.mode) }
    }

    fun syncViewport(
        host: HTMLElement,
        controller: AtlasController,
        fallbackMode: GraphViewportMode,
    ) {
        if (awaitingCanvasFit) {
            if (applyingSessionState || !host.hasSettledCanvasViewport()) return
            awaitingCanvasFit = false
            val pendingCamera = pendingCanvasCamera
            pendingCanvasCamera = null
            if (pendingCamera != null) {
                applyCamera(host, pendingCamera)
                return
            }
        }
        val camera = controller.bridgeCamera
        val scale = host.floatAttribute(BRIDGE_SCALE_ATTRIBUTE)?.toDouble() ?: camera?.scale ?: 1.0
        val panX = host.floatAttribute(BRIDGE_PAN_X_ATTRIBUTE)?.toDouble() ?: camera?.x ?: 0.0
        val panY = host.floatAttribute(BRIDGE_PAN_Y_ATTRIBUTE)?.toDouble() ?: camera?.y ?: 0.0
        val bounds = host.getBoundingClientRect()
        val mode = host.optionalAttribute(BRIDGE_VIEWPORT_MODE_ATTRIBUTE)?.viewportMode() ?: fallbackMode
        controller.recordBridgeViewport(
            AtlasBridgeViewport(
                x = panX,
                y = panY,
                scale = scale,
                width = bounds.width,
                height = bounds.height,
                mode = mode,
                coordinateSpace = host.cameraCoordinateSpace(),
            ),
        )
    }

    fun snapshot(host: HTMLElement): String =
        when {
            mapMounted -> AtlasMapInterop.snapshot(host)
            graphMounted -> AtlasD3Interop.snapshot(host)
            else -> ""
        }

    fun release(host: HTMLElement) {
        if (graphMounted) AtlasD3Interop.destroy(host)
        if (mapMounted) AtlasMapInterop.destroy(host)
        graphMounted = false
        mapMounted = false
        lastFrameJson = null
        lastSemanticRequestRevision = null
        lastSliceJson = null
        lastSelection = GraphSelection()
        lastSecondaryNodes = emptySet()
        lastSecondaryRelations = emptySet()
        awaitingCanvasFit = false
        pendingCanvasCamera = null
        host.removeAttribute("data-docx-atlas-detailed-overview-parked")
        host.removeAttribute("data-docx-atlas-detailed-overview-pending")
    }

    private fun updateMap(
        host: HTMLElement,
        delivery: AtlasSemanticMapDelivery,
    ) {
        if (graphMounted) {
            AtlasD3Interop.destroy(host)
            graphMounted = false
            lastFrameJson = null
        }
        host.removeAttribute("data-docx-atlas-detailed-overview-parked")
        if (mapMounted && !host.hasActiveMapRuntime()) {
            mapMounted = false
            lastSemanticRequestRevision = null
            lastSliceJson = null
        }
        if (!mapMounted) {
            AtlasMapInterop.mount(host, delivery.payload)
            mapMounted = true
        } else if (
            lastSemanticRequestRevision != delivery.requestRevision ||
            lastSliceJson != delivery.payload
        ) {
            AtlasMapInterop.update(host, delivery.payload)
        }
        lastSemanticRequestRevision = delivery.requestRevision
        lastSliceJson = delivery.payload
    }

    private fun applyCamera(
        host: HTMLElement,
        camera: AtlasBridgeCamera?,
    ) {
        val current = host.bridgeCamera()
        if (camera == null || current == null || current.matches(camera)) return
        AtlasMapInterop.command(host, "camera:${camera.x}:${camera.y}:${camera.scale}")
    }

    private fun syncLayers(
        host: HTMLElement,
        controller: AtlasController,
    ) {
        val layers = controller.bridgeLayerNames.joinToString(",")
        AtlasMapInterop.command(host, "layers:$layers")
    }

    private fun syncSelection(
        host: HTMLElement,
        controller: AtlasController,
    ) {
        val primary = controller.bridgePrimarySelection
        val selection = GraphSelection(primary.nodeId, primary.relationId)
        if (selection == lastSelection) return
        lastSelection = selection
        val action =
            when {
                selection.nodeId != null -> "select-node:${selection.nodeId}"
                selection.edgeId != null -> "select-edge:${selection.edgeId}"
                else -> CLEAR_SELECTION_COMMAND
            }
        command(host, action)
    }

    private fun syncSecondarySelection(
        host: HTMLElement,
        controller: AtlasController,
    ) {
        if (!mapMounted) return
        val secondary = controller.bridgeSecondarySelection
        val nodes = secondary.nodeIds
        val relations = secondary.relationIds
        val hadSecondarySelection = lastSecondaryNodes.isNotEmpty() || lastSecondaryRelations.isNotEmpty()
        if (nodes.isEmpty() && relations.isEmpty() && hadSecondarySelection) {
            AtlasMapInterop.command(host, CLEAR_SECONDARY_COMMAND)
            lastSecondaryNodes = emptySet()
            lastSecondaryRelations = emptySet()
            return
        }
        if (nodes != lastSecondaryNodes) {
            AtlasMapInterop.command(host, "$SECONDARY_NODES_COMMAND${nodes.sorted().joinToString(",")}")
            lastSecondaryNodes = nodes
        }
        if (relations != lastSecondaryRelations) {
            AtlasMapInterop.command(host, "$SECONDARY_RELATIONS_COMMAND${relations.sorted().joinToString(",")}")
            lastSecondaryRelations = relations
        }
    }

    private fun command(
        host: HTMLElement,
        action: String,
    ) {
        if (mapMounted) AtlasMapInterop.command(host, action) else AtlasD3Interop.command(host, action)
    }

    private val isMounted: Boolean
        get() = graphMounted || mapMounted
}

internal data class GraphSelection(
    val nodeId: String? = null,
    val edgeId: String? = null,
)

private fun HTMLElement.optionalAttribute(name: String): String? =
    getAttribute(name)?.takeIf(String::isNotBlank)

private fun HTMLElement.floatAttribute(name: String): Float? = getAttribute(name)?.toFloatOrNull()

private fun HTMLElement.hasActiveMapRuntime(): Boolean =
    getAttribute(BRIDGE_RENDERER_ATTRIBUTE) == CANVAS_RENDERER &&
        runCatching { AtlasMapInterop.snapshot(this) }
            .getOrDefault(EMPTY_MAP_SNAPSHOT) != EMPTY_MAP_SNAPSHOT

private fun HTMLElement.cameraCoordinateSpace(): AtlasCameraCoordinateSpace =
    if (getAttribute(BRIDGE_RENDERER_ATTRIBUTE) == CANVAS_RENDERER) {
        AtlasCameraCoordinateSpace.SEMANTIC_MAP
    } else {
        AtlasCameraCoordinateSpace.DETAILED_OVERVIEW
    }

private fun HTMLElement.hasSettledCanvasViewport(): Boolean =
    optionalAttribute(BRIDGE_RENDERER_ATTRIBUTE) == CANVAS_RENDERER &&
        optionalAttribute(BRIDGE_STATE_ATTRIBUTE) == READY_STATE &&
        optionalAttribute(BRIDGE_LAYOUT_REVISION_ATTRIBUTE)?.toLongOrNull()?.let { revision -> revision > 0L } == true

private fun Double.closeTo(other: Double): Boolean = kotlin.math.abs(this - other) < CAMERA_EPSILON

private fun HTMLElement.bridgeCamera(): BridgeCamera? =
    doubleAttribute(BRIDGE_SCALE_ATTRIBUTE)?.let { scale ->
        doubleAttribute(BRIDGE_PAN_X_ATTRIBUTE)?.let { x ->
            doubleAttribute(BRIDGE_PAN_Y_ATTRIBUTE)?.let { y -> BridgeCamera(x, y, scale) }
        }
    }

private fun HTMLElement.doubleAttribute(name: String): Double? = getAttribute(name)?.toDoubleOrNull()

private data class BridgeCamera(
    val x: Double,
    val y: Double,
    val scale: Double,
) {
    fun matches(camera: AtlasBridgeCamera): Boolean =
        scale.closeTo(camera.scale) && x.closeTo(camera.x) && y.closeTo(camera.y)
}

private fun String.viewportMode(): GraphViewportMode =
    when (this) {
        "fit" -> GraphViewportMode.FIT
        "reset" -> GraphViewportMode.RESET
        else -> GraphViewportMode.MANUAL
    }

private const val CAMERA_EPSILON: Double = 0.0001
private const val BRIDGE_SCALE_ATTRIBUTE = "data-docx-atlas-scale"
private const val BRIDGE_PAN_X_ATTRIBUTE = "data-docx-atlas-pan-x"
private const val BRIDGE_PAN_Y_ATTRIBUTE = "data-docx-atlas-pan-y"
private const val BRIDGE_VIEWPORT_MODE_ATTRIBUTE = "data-docx-atlas-viewport-mode"
private const val BRIDGE_RENDERER_ATTRIBUTE = "data-docx-atlas-renderer"
private const val BRIDGE_STATE_ATTRIBUTE = "data-docx-atlas-state"
private const val BRIDGE_LAYOUT_REVISION_ATTRIBUTE = "data-docx-atlas-layout-revision"
private const val CANVAS_RENDERER = "canvas"
private const val READY_STATE = "ready"
private const val EMPTY_MAP_SNAPSHOT = "{}"
private const val CLEAR_SELECTION_COMMAND = "clear-selection"
private const val CLEAR_SECONDARY_COMMAND = "clear-secondary"
private const val SECONDARY_NODES_COMMAND = "secondary-nodes:"
private const val SECONDARY_RELATIONS_COMMAND = "secondary-edges:"
