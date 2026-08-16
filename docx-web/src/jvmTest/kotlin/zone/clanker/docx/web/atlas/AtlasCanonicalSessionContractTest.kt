package zone.clanker.docx.web.atlas

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AtlasCanonicalSessionContractTest {
    @Test
    fun keepsNavigationDomainsInTheSessionAndRendererStateInTheGraphAdapter() {
        val controller = source("atlas/AtlasController.kt")
        val graph = source("atlas/AtlasGraphController.kt")
        val filters = source("atlas/AtlasGraphFilters.kt")
        val queryFilters = source("atlas/AtlasGraphQueryFilters.kt")
        val selection = source("atlas/AtlasGraphSelection.kt")
        val viewport = source("atlas/AtlasGraphViewport.kt")

        assertTrue(graph.contains("session: () -> AtlasSession?"))
        assertTrue(graph.contains("dispatch: (AtlasEvent) -> Unit"))
        assertTrue(graph.contains("fun onSessionChanged("))
        assertTrue(filters.contains("dispatch(AtlasFiltersChanged(updated, recordHistory = false))"))
        assertTrue(filters.contains("dispatch(AtlasScopeSelectionReplaced("))
        assertTrue(selection.contains("session()"))
        assertTrue(selection.contains("?.inspection"))
        assertTrue(selection.contains("dispatch(AtlasPrimaryInspectionChanged("))
        assertTrue(viewport.contains("session()?.camera"))
        assertTrue(viewport.contains("dispatch(AtlasCameraChanged("))
        assertFalse(filters.contains("mutableStateOf"))
        assertFalse(queryFilters.contains("mutableStateOf"))
        assertFalse(selection.contains("mutableStateOf"))
        assertFalse(viewport.contains("mutableStateOf"))
        assertFalse(controller.contains("var projectFilter"))
        assertTrue(controller.contains("session?.filters?.projectQuery"))
        assertFalse(controller.contains("graph.selectNode"))
        assertFalse(controller.contains("graph.selectEdge"))
        assertFalse(controller.contains("graph.clearSelection"))
        assertFalse(controller.contains("reconcileGraphInspection"))
    }

    @Test
    fun removesCompatibilityAndScopeMirrorsFromTheApplicationAndBridge() {
        val application = source("application/DocxApplication.kt")
        val effects = source("application/DocxApplicationEffects.kt")
        val runtime = source("atlas/dom/AtlasDomRuntime.kt")
        val bridge = source("atlas/dom/AtlasGraphBridgeRuntime.kt")
        val flyTo = source("application/WorkspaceSearchFlyToEffects.kt")
        val scopeCommands = source("atlas/AtlasScopeCommands.kt")
        val inspectionCommands = source("atlas/AtlasInspectionCommands.kt")
        val interactionProbe = source("probe/DocxInteractionProbe.kt")

        assertFalse(sourceFile("application/AtlasSessionCompatibilityEffects.kt").exists())
        assertFalse(application.contains("AtlasSessionCompatibilityEffects"))
        assertFalse(application.contains("controller.graph.attachSelection"))
        assertFalse(application.contains("controller.graph.updateSourceSets"))
        assertFalse(effects.contains("controller.graph.attachSelection"))
        assertFalse(runtime.contains("reconcileGraphInspection"))
        assertFalse(bridge.contains("applyBridgeViewport"))
        assertTrue(bridge.contains("controller.bridgePrimarySelection"))
        assertTrue(bridge.contains("controller.recordBridgeViewport("))
        assertTrue(bridge.contains("val canonicalCamera = controller.bridgeCamera"))
        assertTrue(bridge.contains("val requestChanged = lastSemanticRequestRevision != delivery.requestRevision"))
        assertTrue(bridge.contains("if (!wasMounted || requestChanged)"))
        assertTrue(bridge.contains("!awaitingCanvasFit"))
        assertTrue(bridge.contains("camera.coordinateSpace == AtlasCameraCoordinateSpace.SEMANTIC_MAP"))
        assertTrue(bridge.contains("if (applyingSessionState || !host.hasSettledCanvasViewport()) return"))
        assertTrue(bridge.contains("optionalAttribute(BRIDGE_RENDERER_ATTRIBUTE) == CANVAS_RENDERER"))
        assertTrue(bridge.contains("optionalAttribute(BRIDGE_STATE_ATTRIBUTE) == READY_STATE"))
        assertTrue(bridge.contains("val pendingCamera = pendingCanvasCamera"))
        assertTrue(bridge.contains("coordinateSpace = host.cameraCoordinateSpace()"))
        assertTrue(bridge.contains("(wasMounted || controller.bridgeCameraHasMeasuredViewport)"))
        assertTrue(bridge.contains("if (restoreSessionCamera) applyCamera(host, canonicalCamera)"))
        assertTrue(runtime.contains("if (!bridge.acceptsViewportEvents) return"))
        assertFalse(flyTo.contains("focusSemanticNode"))
        assertFalse(scopeCommands.contains("scope = AtlasScope"))
        assertTrue(scopeCommands.contains("revealAncestorId"))
        assertTrue(inspectionCommands.contains("filter { target -> target.kind == AtlasInspectionKind.NODE }"))
        assertTrue(interactionProbe.contains("InteractionProbeScopeReadiness.Kind.BOUNDED_GRAPH"))
        assertTrue(interactionProbe.contains("canonicalGraphSettled = controller.settledGraphSlice != null"))
        assertTrue(interactionProbe.contains("state.interactionScopeIsSettled(controller)"))
        assertFalse(interactionProbe.contains("!selection.isOverview && state.loadedProjectIds == expectedProjectIds"))
        assertTrue(
            application.contains(
                "snapshotFlow { application.viewerState.interactionProbe(application.controller) }",
            ),
        )

        val controllerStateCommands = source("atlas/AtlasControllerStateCommands.kt")
        assertTrue(controllerStateCommands.contains("current.copy("))
        assertTrue(controllerStateCommands.contains("projectQuery ="))
        assertTrue(
            controllerStateCommands.contains("dispatch(AtlasFiltersChanged(updated, recordHistory = false))"),
        )
    }

    private fun source(relativePath: String): String =
        requireNotNull(sourceFile(relativePath).takeIf(File::isFile)) {
            "Unable to locate DOCX web source $relativePath from ${File(".").absolutePath}"
        }.readText()

    private fun sourceFile(relativePath: String): File {
        val path = "src/wasmJsMain/kotlin/zone/clanker/docx/web/$relativePath"
        return listOf(File(path), File("docx-web/$path")).firstOrNull(File::exists) ?: File(path)
    }
}
