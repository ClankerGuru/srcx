package zone.clanker.docx.web.atlas

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private data class LifecycleSources(
    val surface: String,
    val model: String,
    val runtime: String,
    val scrollRuntime: String,
    val bridge: String,
    val sessionBridge: String,
    val payload: String,
    val delivery: String,
    val viewerSelection: String,
    val applicationEffects: String,
)

class AtlasD3BridgeContractTest {
    @Test
    fun withholdsTheHydratedAtlasUntilAllShadowStylesAreReady() {
        val source = sourceFile("atlas/dom/AtlasDomElements.kt")

        listOf(
            "surface.style.setProperty(\"visibility\", \"hidden\")",
            "surface.style.setProperty(\"pointer-events\", \"none\")",
            "surface.querySelectorAll('link[rel=\"stylesheet\"]')",
            "fetch(link.href, { credentials: \"same-origin\" })",
            "document.createElement(\"style\")",
            "style.textContent = await response.text()",
            "link.replaceWith(style)",
            "rootSheets.includes(style.sheet)",
            "activeStyleCount === styles.length",
            "if (visible) conceal()",
            "stableFrames < 3",
            "requestAnimationFrame(waitForRegistration)",
            "surface.setAttribute(\"data-docx-atlas-styles-ready\", \"true\")",
            "surface.setAttribute(\"data-docx-atlas-active-styles\", String(activeStyleCount))",
            "loading.style.setProperty(\"display\", \"none\")",
        ).forEach { marker ->
            assertTrue(source.contains(marker), "Atlas style readiness boundary is missing: $marker")
        }
        assertTrue(
            source.indexOf("surface.style.setProperty(\"visibility\", \"hidden\")") <
                source.indexOf("host.appendChild(surface)"),
        )
    }

    @Test
    fun observesBridgeLifecycleThroughTheComposeSurfaceModel() {
        val sources = lifecycleSources()

        assertSurfaceObservation(sources)
        assertSemanticDelivery(sources)
        assertInteractionScroll(sources)
        assertFrameSettlement(sources)
    }

    private fun assertSurfaceObservation(sources: LifecycleSources) {
        val surface = sources.surface
        val model = sources.model
        val runtime = sources.runtime
        val bridge = sources.bridge

        assertTrue(model.contains("val bridgeEnabled: Boolean"))
        assertTrue(surface.contains("bridgeEnabled = controller.graph.bridgeEnabled"))
        assertTrue(
            surface.indexOf("update = { root ->") <
                surface.indexOf("val globalSearch = bindings.globalSearch()"),
        )
        assertTrue(surface.contains("val detailedOverview ="))
        assertTrue(surface.contains("state.site.atlasOverview?.let { overview ->"))
        assertTrue(surface.contains("overview.filteredOverview(filters)"))
        assertTrue(surface.contains("remember(state.site.manifest.generationId, detailedOverview)"))
        assertTrue(surface.contains("detailedOverview?.let(AtlasFrameJson::encode)"))
        assertTrue(
            surface.contains("atlasDomSurfaceModel(state, globalSearch, detailedOverviewJson, controller)"),
        )
        assertTrue(surface.contains("frameJson = detailedOverviewJson.takeIf { preserveDetailedOverview }"))
        assertTrue(surface.contains("runtime.update(root, observedModel, controller, bindings.actions)"))
        assertTrue(surface.contains("val semanticMapDelivery = controller.semanticMapDelivery()"))
        assertTrue(model.contains("val semanticMapDelivery: AtlasSemanticMapDelivery?"))
        assertTrue(model.contains("val preserveDetailedOverview: Boolean"))
        assertTrue(surface.contains("preserveDetailedOverview = controller.scopeSelection.isOverview"))
        assertTrue(bridge.contains("!model.bridgeEnabled"))
        assertTrue(bridge.contains("lastSemanticRequestRevision != delivery.requestRevision"))
        assertTrue(bridge.contains("lastSliceJson != delivery.payload"))
        assertTrue(bridge.contains("mapMounted && !host.hasActiveMapRuntime()"))
        assertTrue(runtime.contains("graphHost.setAttribute(RUNTIME_OWNER_ATTRIBUTE, runtimeOwnerId)"))
        assertTrue(runtime.contains("if (ownsRenderedSurface)"))
        assertTrue(runtime.contains("graphHost.openCanonicalNode(currentController, nodeId)"))
        assertFalse(runtime.contains("bridge.issue(graphHost, \"fly-to-node:\$nodeId\""))
    }

    private fun assertSemanticDelivery(sources: LifecycleSources) {
        val delivery = sources.delivery
        val payload = sources.payload
        val sessionBridge = sources.sessionBridge
        val bridge = sources.bridge

        assertTrue(delivery.contains("val requestRevision: Long"))
        assertTrue(delivery.contains("val currentSession = session ?: return null"))
        assertTrue(delivery.contains("layoutOverrides = currentSession.layout.overrides"))
        assertTrue(delivery.contains("automaticExpandedIds = currentSession.focus.automaticExpandedIds"))
        assertTrue(payload.contains("put(\"layoutOverrides\", layoutOverridesJson(layoutOverrides))"))
        assertTrue(payload.contains("\"automaticExpandedIds\""))
        assertTrue(sessionBridge.contains("takeUnless(AtlasLayoutOverride::isCalculatedDefault)"))
        assertTrue(sessionBridge.contains("point.x.closeTo(0.0) && point.y.closeTo(0.0)"))
        assertTrue(sessionBridge.contains("size.width.closeTo(0.0) && size.height.closeTo(0.0)"))
        assertTrue(
            bridge.indexOf("model.preserveDetailedOverview ->") <
                bridge.indexOf("model.semanticMapDelivery != null"),
            "A settled semantic slice must not replace the detailed bounded Atlas frame",
        )
        val detailedUpdate =
            bridge
                .substringAfter("private fun updateLegacyGraph(")
                .substringBefore("fun issue(")
        assertTrue(detailedUpdate.contains("frameJson: String?"))
        assertTrue(detailedUpdate.contains("if (mapMounted)"))
        assertTrue(detailedUpdate.contains("AtlasMapInterop.destroy(host)"))
        assertTrue(detailedUpdate.contains("data-docx-atlas-detailed-overview-pending"))
        assertFalse(detailedUpdate.contains("updateSemanticMap"))
        assertFalse(detailedUpdate.contains("AtlasMapInterop.mount"))
        assertTrue(bridge.contains("if (mapMounted) {\n            AtlasMapInterop.destroy(host)"))
        val mapUpdate = bridge.substringAfter("private fun updateMap(").substringBefore("private fun applyCamera(")
        assertTrue(mapUpdate.contains("AtlasD3Interop.destroy(host)"))
        assertTrue(mapUpdate.contains("graphMounted = false"))
        assertTrue(mapUpdate.contains("lastFrameJson = null"))
        assertFalse(bridge.contains("!controller.graph.bridgeEnabled"))
    }

    private fun assertInteractionScroll(sources: LifecycleSources) {
        val runtime = sources.runtime
        val scrollRuntime = sources.scrollRuntime

        assertTrue(scrollRuntime.contains("private var detachedSurfaceScrollTop: Double? = null"))
        assertTrue(runtime.contains("graphHost.addEventListener(\"pointerdown\", pointerDownListener, true)"))
        assertTrue(runtime.contains("nextRoot.addEventListener(\"scroll\", surfaceScrollListener)"))
        assertTrue(scrollRuntime.contains("restore(currentRoot, anchor, \"surface-scroll\")"))
        assertTrue(runtime.contains("val shouldFocusDetail = interactionScroll.selectionShouldFocusDetail"))
        assertTrue(runtime.contains("renderSelectedDetail(selection, currentController, shouldFocusDetail)"))
        assertTrue(
            scrollRuntime.contains("pendingSelectionScrollTop = pointerInteractionScrollTop ?: currentScrollTop"),
        )
        assertTrue(
            runtime.indexOf("root?.let(interactionScroll::restorePendingSelection)") <
                runtime.indexOf("currentController.inspect(selection)"),
        )
        assertTrue(
            runtime.indexOf("currentController.inspect(selection)") <
                runtime.indexOf("AtlasEvidenceRequestRuntime.requestProject"),
        )
        assertTrue(
            scrollRuntime.contains(
                "get() = interactionScrollAnchor ?: pendingSelectionScrollTop ?: detachedSurfaceScrollTop",
            ),
        )
        assertTrue(
            scrollRuntime.contains(
                "if (detach) detachedSurfaceScrollTop = preservedScrollTop ?: root()?.scrollTop",
            ),
        )
        assertTrue(scrollRuntime.contains("restore(currentRoot, anchor, \"stale-pointer-hit\")"))
        assertTrue(scrollRuntime.contains("event.stopPropagation()"))
        assertTrue(
            scrollRuntime.contains(
                "detachedSurfaceScrollTop?.let { scrollTop -> " +
                    "restore(nextRoot, scrollTop, \"surface-attach\") }",
            ),
        )
        assertTrue(runtime.contains("interactionScroll.restorePreserved(nextRoot, \"surface-update\""))
        assertTrue(runtime.contains("interactionScroll.restorePreserved(it, \"detail-state\")"))
        assertTrue(runtime.contains("bridge.update(graphHost, nextModel, nextController)"))
        assertTrue(runtime.contains("bridge.hasSnapshot.toString()"))
        assertFalse(runtime.contains("bridge.snapshot(graphHost)"))
        assertTrue(runtime.contains("if (!hasDetail && !detailVisible) {"))
        assertTrue(runtime.contains("detailRetention.matches(nextModel, nextController.graphSelection"))
        assertTrue(scrollRuntime.contains("private var interactionGraphTopAnchor: Double? = null"))
        assertTrue(scrollRuntime.contains("private fun interactionScrollTarget("))
        assertTrue(scrollRuntime.contains("val scrollTop = pendingSelectionScrollTop ?: return"))
        assertTrue(scrollRuntime.contains("remainingFrames: Int = SCROLL_RESTORE_FRAME_LIMIT"))
        assertTrue(scrollRuntime.contains("continueRestoration(element, scrollTop, revision, remainingFrames - 1)"))
        assertTrue(scrollRuntime.contains("private const val SCROLL_RESTORE_FRAME_LIMIT = 4"))
    }

    private fun assertFrameSettlement(sources: LifecycleSources) {
        val viewerSelection = sources.viewerSelection
        val applicationEffects = sources.applicationEffects

        assertTrue(viewerSelection.contains("projectLoading = false"))
        assertTrue(viewerSelection.contains("buildLoading = false"))
        assertTrue(applicationEffects.contains("settleSelectedScopeWithoutProjectShards(ready, selection)"))
        assertFalse(applicationEffects.contains("loadSelectedProjectState"))
        assertTrue(applicationEffects.contains("awaitGraphFrameSettlement(controller: AtlasController)"))
        assertTrue(
            applicationEffects.contains(
                "settled.request == session.workspaceGraphRequest(WorkspaceGraphFacet.SOURCE)",
            ),
        )
        assertFalse(applicationEffects.contains("awaitGraphFrameSettlement(controller.graph)"))
    }

    private fun lifecycleSources(): LifecycleSources =
        LifecycleSources(
            surface = sourceFile("atlas/dom/AtlasDomSurface.kt"),
            model = sourceFile("atlas/dom/AtlasDomSurfaceModel.kt"),
            runtime = sourceFile("atlas/dom/AtlasDomRuntime.kt"),
            scrollRuntime = sourceFile("atlas/dom/AtlasInteractionScrollRuntime.kt"),
            bridge = sourceFile("atlas/dom/AtlasGraphBridgeRuntime.kt"),
            sessionBridge = sourceFile("atlas/AtlasBridgeSession.kt"),
            payload = sourceFile("atlas/AtlasMapPayload.kt"),
            delivery = sourceFile("atlas/AtlasSemanticMapDelivery.kt"),
            viewerSelection = sourceFile("state/ViewerSelection.kt"),
            applicationEffects = sourceFile("application/DocxApplicationEffects.kt"),
        )

    @Test
    fun keepsTheBridgeNarrowAndTheLegacyInteractionPrimitivesPresent() {
        val source = bridgeSource()

        assertEquals(1, JSON_PARSE.findAll(source).count())
        assertEquals(1, JSON_STRINGIFY.findAll(source).count())
        assertFalse(FETCH_CALL.containsMatchIn(source))
        listOf(
            "mount: mount",
            "update: update",
            "command: command",
            "snapshot: snapshot",
            "destroy: destroy",
            "d3.randomLcg(0.42)",
            "function nodeDrag(state)",
            "function scopeDrag(state, scope)",
            "function packVariableRectangles(rectangles, targetAspect, gap)",
            "function measureNodeLabels(state)",
            "function constrainNodesToRegions(state)",
            "function applyManualScopeOffsets(state)",
            "function translateDraggedScopeLayout(state, context, dx, dy)",
            "function aggregateScopeRelationships(state, level)",
            "function renderScopeRelationshipOverlay(state)",
            "function highlightScope(state, scope)",
            "function sourceSetScope(buildId, projectId, sourceSet)",
            "function selectScopeByCommand(state, value, notify)",
            "STATIC_LAYOUT_NODE_THRESHOLD",
            "global.requestAnimationFrame(function relayoutAfterResize()",
            "function installLasso(state)",
            "function highlightNode(state, nodeId)",
            "function focusAdjacentNode(state, node, key)",
            "function focusIncidentEdge(state, nodeId)",
            "state.svg.selectAll(\"*\").remove()",
            "style(\"display\", \"none\")",
            "(candidate.relationshipIds || []).includes(id)",
            "docx-atlas-selection",
            "function emitSelectionEvent(state, detail)",
            "restoreSelectionScrollSecondFrame",
            "docx-atlas-viewport",
            "data-docx-atlas-selected-scope-level",
            "data-docx-atlas-renderer\", \"d3",
            "data-docx-atlas-scope-relationship-basis",
            "data-docx-atlas-visible-scope-relationship-record-count",
            ". Aggregated only from the current Atlas frame.",
            "scope-selection scope-relationship-levels",
        ).forEach { marker ->
            assertTrue(source.contains(marker), "D3 bridge is missing interaction marker: $marker")
        }
        assertFalse(source.contains("Math.max(150, width)"), "fixed project sizing can overflow its parent region")
        assertFalse(source.contains("Math.max(70, (rectHeight(projectCell)"), "fixed subgroup sizing can overlap")
    }

    @Test
    fun usesOneBoundedCanvasInsteadOfOneDomSubtreePerGraphFact() {
        val source = canvasSource()

        assertTrue(source.contains("svg.style.visibility = \"hidden\""))
        assertTrue(source.contains("svg.style.pointerEvents = \"none\""))
        assertFalse(source.contains("svg.hidden = true"))
        assertEquals(0, JSON_PARSE.findAll(source).count())
        assertEquals(2, JSON_STRINGIFY.findAll(source).count())
        assertEquals(1, Regex("""document\.createElement\("canvas"\)""").findAll(source).count())
        assertFalse(FETCH_CALL.containsMatchIn(source))
        listOf(
            "global.docxAtlasMap = Object.freeze",
            "data-docx-atlas-canvas",
            "new ResizeObserver",
            "requestAnimationFrame",
            "function drawRelations(state, context, secondaryOrdinals)",
            "function drawNode(state, context, node, secondaryOrdinals)",
            "function openNode(state, nodeId)",
            "state.userNavigated = false;",
            "function effectiveRect(state, node)",
            "function hitRelation(state, screenPoint)",
            "docx-atlas-open",
            "docx-atlas-selection",
            "docx-atlas-layout",
            "data-docx-atlas-renderer\", \"canvas",
        ).forEach { marker ->
            assertTrue(source.contains(marker), "Canvas renderer is missing semantic-map marker: $marker")
        }
    }

    @Test
    fun keepsCanvasGesturesBoundedAndSynchronizesCanonicalSecondarySelectionsSilently() {
        val source = canvasSource()

        listOf(
            "const maximumActivePointers = 2",
            "const maximumUndoEntries = 100",
            "const maximumSnapshotSamples = 8",
            "event.shiftKey && !touch",
            "const nodeDrag = event.altKey && node && !touch",
            "nodeDrag ? \"node-drag\" : \"pan\"",
            "state.canvas.style.cursor = \"grab\"",
            "function completeBoxSelection(state, start, current)",
            "function beginPinch(state)",
            "function applyPinch(state)",
            "function finishPinch(state, cancelled)",
            "startWorldCentroid: worldPoint(state, centroid)",
            "function openKeyboardTarget(state)",
            "function toggleKeyboardTarget(state)",
            "docx-atlas-preview",
            "function resolveSiblingCollisions(state, nodeId)",
            "function pushUndo(state, offsets, sizes, changedIds)",
            "preserveResolvedLayoutState: hasAcceptedCurrentSlice(state)",
            "prefers-reduced-motion: reduce",
            "function releaseActivePointers(state)",
            "secondary-nodes:",
            "secondary-edges:",
            "clear-secondary",
            "function drawSecondaryBadge(context, point, ordinal, scale)",
            "function drawPrimaryBadge(context, point, scale)",
            "function drawNodeHalo(context, rect, scale)",
            "function drawRelationHalo(context, from, to, scale)",
            "function drawEndpointMarkers(context, from, to, scale)",
            "function relationTouchesSelectedNode(state, relation)",
            "const ancestor = roles.includes(\"ANCESTOR\")",
            "context.fillStyle = ancestor ? \"rgba(83,199,232,0.22)\"",
            "function retainInteractionState(state)",
            "state.secondaryNodeIds = retainedIds(state.frame.content.nodes, state.secondaryNodeIds)",
            "state.secondaryRelationIds = retainedIds(state.frame.content.relations, state.secondaryRelationIds)",
        ).forEach { marker ->
            assertTrue(source.contains(marker), "Canvas renderer is missing bounded interaction marker: $marker")
        }

        val silentSynchronization =
            source
                .substringAfter("function replaceSecondaryNodes(state, value)")
                .substringBefore("function completeBoxSelection(state, start, current)")
        assertFalse(
            silentSynchronization.contains("docx-atlas-selection"),
            "History restoration must replace secondary selections without feeding selection events " +
                "back to the reducer",
        )
        assertFalse(source.contains("setInterval("), "Canvas interaction state must not grow from a polling loop")
    }

    private fun bridgeSource(): String {
        val candidates =
            listOf(
                File("src/wasmJsMain/resources/docx-atlas-d3.js"),
                File("docx-web/src/wasmJsMain/resources/docx-atlas-d3.js"),
            )
        return requireNotNull(candidates.firstOrNull(File::isFile)) {
            "Unable to locate the DOCX Atlas D3 bridge from ${File(".").absolutePath}"
        }.readText()
    }

    private fun canvasSource(): String {
        val candidates =
            listOf(
                File("src/wasmJsMain/resources/docx-atlas-map.js"),
                File("docx-web/src/wasmJsMain/resources/docx-atlas-map.js"),
            )
        return requireNotNull(candidates.firstOrNull(File::isFile)) {
            "Unable to locate the DOCX Atlas Canvas renderer from ${File(".").absolutePath}"
        }.readText()
    }

    private fun sourceFile(name: String): String {
        val relativePath = "src/wasmJsMain/kotlin/zone/clanker/docx/web/$name"
        val candidates = listOf(File(relativePath), File("docx-web/$relativePath"))
        return requireNotNull(candidates.firstOrNull(File::isFile)) {
            "Unable to locate $relativePath from ${File(".").absolutePath}"
        }.readText()
    }

    private companion object {
        val JSON_PARSE = Regex("""\bJSON\.parse\(""")
        val JSON_STRINGIFY = Regex("""\bJSON\.stringify\(""")
        val FETCH_CALL = Regex("""\bfetch\s*\(""")
    }
}
