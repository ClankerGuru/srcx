package zone.clanker.docx.web.atlas

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AtlasCanvasWorkerContractTest {
    @Test
    fun loadsTheSharedLayoutCoreBeforeTheCanvasAndWorker() {
        val template = resource("index.html")
        val core = resource("docx-atlas-layout.js")
        val worker = resource("docx-atlas-layout-worker.js")
        val coreScript = "<script src=\"assets/docx-atlas-layout.js\"></script>"
        val mapScript = "<script src=\"assets/docx-atlas-map.js\"></script>"

        assertTrue(template.contains(coreScript))
        assertTrue(template.indexOf(coreScript) < template.indexOf(mapScript))
        assertTrue(worker.contains("importScripts(\"docx-atlas-layout.js\")"))
        assertTrue(worker.contains("self.docxAtlasLayout.compute(request)"))
        assertEquals(1, JSON_PARSE.findAll(core).count())
        assertEquals(0, JSON_PARSE.findAll(resource("docx-atlas-map.js")).count())
    }

    @Test
    fun resolvesTheWorkerFromTheCapturedMapScriptAndGuardsEveryRevision() {
        val source = resource("docx-atlas-map.js")

        assertMarkers(
            source,
            "const mapScriptUrl = document.currentScript?.src || \"\"",
            "new URL(\"docx-atlas-layout-worker.js\", mapScriptUrl).href",
            "state.layoutRevision += 1",
            "cancelLayoutWorker(state)",
            "state.layoutWorker === worker",
            "state.layoutWorker?.terminate()",
            "message.revision === request.revision",
            "states.get(state.host) === state",
        )
        assertFalse(source.substringAfter("function mount").contains("document.currentScript?.src"))
    }

    @Test
    fun publishesWorkerLifecycleAndFallsBackWithoutBlockingMount() {
        val source = resource("docx-atlas-map.js")

        assertMarkers(
            source,
            "layoutStatus: \"loading\"",
            "state.layoutStatus = \"ready\"",
            "state.layoutStatus = \"error\"",
            "data-docx-atlas-layout-runtime",
            "data-docx-atlas-layout-revision",
            "data-docx-atlas-layout-error",
            "typeof global.Worker === \"function\"",
            "worker.addEventListener(\"error\", (event) => {",
            "event.preventDefault()",
            "currentLayoutRequest(state, request.revision, worker)",
            "function runMainThreadLayout(state, request, failedWorker)",
            "global.setTimeout(() =>",
        )
    }

    @Test
    fun publishesTheCurrentPaintRevisionOnlyAfterAVisibleSemanticPixelIsDrawn() {
        val source = resource("docx-atlas-map.js")
        val draw = source.substringAfter("function draw(state)").substringBefore("function drawGrid")
        val proof =
            source
                .substringAfter("function publishPaintedSemanticNode")
                .substringBefore("function createSecondaryOrdinals")
        val request = source.substringAfter("function requestLayout").substringBefore("function layoutRequest")
        val schedule = source.substringAfter("function scheduleDraw").substringBefore("function hitNode")

        assertMarkers(
            draw,
            "if (drawNode(state, context, node, secondaryOrdinals)) paintedNodes.push(node)",
            "context.restore()",
            "publishPaintedSemanticNode(state, paintedNodes)",
        )
        assertTrue(draw.indexOf("context.restore()") < draw.indexOf("publishPaintedSemanticNode"))
        assertMarkers(
            proof,
            "candidate.x >= 0 && candidate.y >= 0",
            "data-docx-atlas-paint-revision",
            "data-docx-atlas-painted-node-id",
            "data-docx-atlas-painted-node-x",
            "data-docx-atlas-painted-node-y",
            "state.paintRevision = state.layoutRevision",
        )
        assertFalse(proof.contains("getImageData"))
        assertTrue(request.contains("invalidatePaint(state)"))
        assertTrue(schedule.contains("invalidatePaint(state)"))
        assertMarkers(
            source,
            "requestAnimationFrame(() => drawSafely(state))",
            "data-docx-atlas-draw-error",
            "data-docx-atlas-geometry-diagnostic",
            "transformedFirstRect",
        )
    }

    @Test
    fun retainsPreReadyCommandsAndMovesEveryHierarchyRelayoutOffThread() {
        val source = resource("docx-atlas-map.js")

        assertMarkers(
            source,
            "frame: null",
            "sliceJson: state.sliceJson",
            "offsets: Array.from(state.offsets.entries())",
            "minimumSizes: Array.from(state.minimumSizes.entries())",
            "if (state.layoutStatus !== \"ready\" || !state.frame) return null",
            "state.layoutStatus !== \"ready\" || state.nodesById.has(nodeId)",
            "retainInteractionState(state)",
            "publishRetainedTargets(state)",
            "if (request.fitWhenReady && !state.userNavigated) fit(state)",
            "function relayoutWithState(state, fitWhenReady)",
            "requestLayout(state, state.sliceJson",
            "preserveResolvedLayoutState: hasAcceptedCurrentSlice(state)",
        )
        assertFalse(source.contains(".layoutFrame("))
    }

    @Test
    fun routesCameraSelectionSecondaryAndPreviewCommandsWhileLoading() {
        val source = resource("docx-atlas-map.js")
        val command = source.substringAfter("function command").substringBefore("function snapshot")
        val camera = source.substringAfter("function applyCamera").substringBefore("function applyLayers")

        assertMarkers(
            command,
            "action.startsWith(\"camera:\")",
            "action.startsWith(\"preview-node:\")",
            "action.startsWith(\"select-node:\")",
            "action.startsWith(\"secondary-nodes:\")",
            "action.startsWith(\"secondary-edges:\")",
        )
        assertFalse(camera.contains("state.frame"))
        assertFalse(camera.contains("state.nodes"))
        assertTrue(source.contains("(!available || available.has(id))"))
    }

    @Test
    fun keepsTheWorkerProtocolRevisionedAndSerializable() {
        val worker = resource("docx-atlas-layout-worker.js")
        val core = resource("docx-atlas-layout.js")

        assertMarkers(
            worker,
            "request.type !== \"layout\"",
            "type: \"layout-result\", revision: request.revision, result",
            "type: \"layout-error\", revision: request.revision, message",
        )
        assertMarkers(
            core,
            "preserveResolvedLayoutState",
            "resolveLayoutState(frame, request.layoutState || {})",
            "retainLayoutState(frame, request.layoutState || {})",
            "relationIds",
            "worldBounds",
        )
    }

    private fun assertMarkers(source: String, vararg markers: String) {
        markers.forEach { marker ->
            assertTrue(source.contains(marker), "Worker boundary is missing contract marker: $marker")
        }
    }

    private fun resource(name: String): String {
        val relativePath = "src/wasmJsMain/resources/$name"
        val candidates = listOf(File(relativePath), File("docx-web/$relativePath"))
        return requireNotNull(candidates.firstOrNull(File::isFile)) {
            "Unable to locate $relativePath from ${File(".").absolutePath}"
        }.readText()
    }

    private companion object {
        val JSON_PARSE = Regex("""\bJSON\.parse\(""")
    }
}
