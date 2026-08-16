package zone.clanker.docx.web.atlas

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AtlasGlobalSearchContractTest {
    @Test
    fun keepsGlobalSearchGenerationGuardedAndIndependentFromProjectShards() {
        val state = sourceFile("src/commonMain/kotlin/zone/clanker/docx/web/state/WorkspaceGlobalSearchState.kt")
        val transaction =
            sourceFile("src/commonMain/kotlin/zone/clanker/docx/web/state/WorkspaceGlobalSearchTransaction.kt")
        val effects =
            sourceFile("src/wasmJsMain/kotlin/zone/clanker/docx/web/application/WorkspaceGlobalSearchEffects.kt")

        listOf(
            "queryState.generationId == request.generationId",
            "queryState.requestRevision == request.revision",
            "if (!queryState.loading || queryState.query.isBlank()) return null",
            "cachedCatalog = catalog",
            "LaunchedEffect(site?.manifest?.generationId, request?.revision)",
            "request.cachedCatalog ?: loader.loadSearchCatalog(site)?.catalog",
            "loader.search(site, catalog, request.query)",
            "if (error is CancellationException) throw error",
        ).forEach { marker ->
            assertTrue(state.contains(marker) || transaction.contains(marker) || effects.contains(marker), marker)
        }
        assertFalse(effects.contains("loadProject"))
        assertFalse(effects.contains("AtlasController"))
        assertFalse(effects.contains("AtlasDomRuntime"))
    }

    @Test
    fun mountsAnAccessibleGroupedMultiSelectSurfaceWithSemanticTargets() {
        val renderer =
            sourceFile(
                "src/wasmJsMain/kotlin/zone/clanker/docx/web/atlas/dom/search/AtlasGlobalSearchRenderer.kt",
            )
        val results =
            sourceFile(
                "src/wasmJsMain/kotlin/zone/clanker/docx/web/atlas/dom/search/" +
                    "AtlasGlobalSearchResultsRenderer.kt",
            )
        val dom =
            sourceFile(
                "src/wasmJsMain/kotlin/zone/clanker/docx/web/atlas/dom/search/" +
                    "AtlasGlobalSearchElements.kt",
            )
        val actions =
            sourceFile("src/wasmJsMain/kotlin/zone/clanker/docx/web/atlas/dom/search/AtlasGlobalSearchActions.kt")
        val runtime = sourceFile("src/wasmJsMain/kotlin/zone/clanker/docx/web/atlas/dom/AtlasDomRuntime.kt")
        val surface = sourceFile("src/wasmJsMain/kotlin/zone/clanker/docx/web/atlas/dom/AtlasDomSurface.kt")
        val application = sourceFile("src/wasmJsMain/kotlin/zone/clanker/docx/web/application/DocxApplication.kt")
        val state = sourceFile("src/commonMain/kotlin/zone/clanker/docx/web/state/WorkspaceGlobalSearchState.kt")

        assertSearchSurface(renderer, results, dom, actions)
        assertCanonicalSelection(results, actions, runtime, application, state)
        assertObservedSearchState(surface, application)
        assertFocusedResultShiftEnter(results)
        assertSearchPrefixes(renderer)
        assertFalse(renderer.contains("host.textContent = \"\""), "Mounting must preserve unrelated host content")
    }

    private fun assertObservedSearchState(
        surface: String,
        application: String,
    ) {
        assertTrue(application.contains("globalSearch = { application.globalSearch }"))
        assertTrue(surface.contains("val globalSearch: () -> WorkspaceGlobalSearchState"))
        assertTrue(surface.contains("bindings.globalSearch()"))
    }

    private fun assertFocusedResultShiftEnter(results: String) {
        val enterBranch = results.substringAfter("\"Enter\" -> {").substringBefore("\" \", \"Spacebar\"")

        assertTrue(enterBranch.contains("if (event.shiftKey)"))
        assertTrue(enterBranch.contains("event.preventDefault()"))
        assertTrue(enterBranch.contains("model.actions.onResultToggled(result)"))
    }

    private fun assertCanonicalSelection(
        results: String,
        actions: String,
        runtime: String,
        application: String,
        state: String,
    ) {
        assertTrue(actions.contains("val selectedNodeIds: Set<String>"))
        assertTrue(runtime.contains("selectedNodeIds = nextController.bridgeSecondarySelection.nodeIds"))
        assertTrue(results.contains("result.id in model.selectedNodeIds"))
        assertTrue(application.contains("onSelectionCleared = application.controller::clearSecondaryNodes"))
        assertFalse(application.contains("globalSearch.toggle"))
        assertFalse(state.contains("selectedResults"))
    }

    private fun assertSearchSurface(
        renderer: String,
        results: String,
        dom: String,
        actions: String,
    ) {
        listOf(
            "fun mount(host: HTMLElement)",
            "host.appendChild(next.surface)",
            "parentElement?.removeChild(mounted.surface)",
            "data-srcx-global-search-input",
            "data-srcx-global-search-results",
            "data-srcx-global-search-query",
            "data-srcx-global-search-request-revision",
            "data-srcx-global-search-result-count",
            "data-srcx-global-search-error",
            "data-srcx-global-search-group",
            "data-srcx-global-search-mounted-count",
            "workspaceSearchVirtualWindow",
            "data-srcx-search-badge",
            "result.kind.name.lowercase()",
            "aria-pressed",
            "onResultToggled",
            "onTargetRequested: (WorkspaceSearchEntry) -> Unit",
            "model.actions.onTargetRequested(result)",
            "\"ArrowDown\"",
            "\"ArrowUp\"",
            "\"Home\"",
            "\"End\"",
            "\"Spacebar\"",
            "\"Escape\"",
            "host.addEventListener(\"click\", outsideClickListener)",
            "removeEventListener(\"click\", outsideClickListener)",
            "host.addEventListener(\"keydown\", inputKeyListener)",
            "removeEventListener(\"keydown\", inputKeyListener)",
            "input.addEventListener(\"input\", inputListener)",
            "input?.removeEventListener(\"input\", inputListener)",
            "queueMicrotask",
            "requestAnimationFrame(focus)",
            "button.focus({ preventScroll: true })",
            "data-srcx-global-search-focused-target",
            "[data-docx-atlas-canvas]",
            "dismissAndFocusChart(current)",
        ).forEach { marker ->
            assertTrue(
                renderer.contains(marker) ||
                    results.contains(marker) ||
                    dom.contains(marker) ||
                    actions.contains(marker),
                marker,
            )
        }
        assertFalse(renderer.contains("input.oninput"), "Synthetic input events must not require an InputEvent cast")
    }

    private fun assertSearchPrefixes(renderer: String) {
        listOf(
            "build",
            "project",
            "source",
            "package",
            "file",
            "class",
            "symbol",
            "method",
            "ext",
            "problem",
            "cycle",
        ).forEach { prefix ->
            assertTrue(renderer.contains("GlobalSearchPrefix(\"$prefix\""), "Missing $prefix: search prefix")
        }
    }

    private fun sourceFile(relativePath: String): String {
        val candidates = listOf(File(relativePath), File("docx-web/$relativePath"))
        return requireNotNull(candidates.firstOrNull(File::isFile)) {
            "Unable to locate $relativePath from ${File(".").absolutePath}"
        }.readText()
    }
}
