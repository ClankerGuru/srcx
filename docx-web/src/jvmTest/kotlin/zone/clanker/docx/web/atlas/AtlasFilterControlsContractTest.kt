package zone.clanker.docx.web.atlas

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AtlasFilterControlsContractTest {
    @Test
    fun exposesDirectAccessibleChartControls() {
        val template = sourceFile("src/wasmJsMain/resources/index.html").readText()
        assertFilterControlMarkers(template)
        assertTransientFilterSheetStructure(template)
    }

    @Test
    fun keepsFiveRowScopeChoicesAndLetsTheHostStylesheetReplaceTheLegacyDeck() {
        val css = workspaceFile(CANONICAL_DASHBOARD_CSS).readText()
        val hostCss = sourceFile("src/wasmJsMain/resources/docx.css").readText()

        listOf(
            "Workspace Atlas semantic-map control surface",
            ".srcx-dashboard__architecture-scope-control",
            ".srcx-dashboard__architecture-filter-bar",
            "block-size: clamp(720px, 82vh, 900px)",
            "position: absolute",
            "grid-template-rows: 44px 236px auto",
            "block-size: 236px",
            ".srcx-dashboard__architecture-virtual-canvas",
            "data-srcx-selection-state=\"partial\"",
            "scrollbar-gutter: stable",
            "overflow-y: auto",
            "overflow-x: hidden",
            "grid-template-columns: repeat(3, minmax(0, 1fr))",
            "min-height: 44px",
            "touch-action: pan-y",
            ".srcx-dashboard__architecture-scope-control[hidden]",
            "border-radius: 0",
            "box-shadow: 2px 2px 0 var(--srcx-shadow)",
            "@container (max-width: 640px)",
            ".srcx-dashboard__architecture-filter-button[aria-checked=\"true\"]",
            ".srcx-dashboard__architecture-filter-button[aria-pressed=\"true\"]",
            ".srcx-dashboard__architecture-search-target-button[aria-pressed=\"true\"]",
            ".srcx-dashboard__architecture-count-filter-button[aria-pressed=\"true\"]",
            ".srcx-dashboard__architecture-direction-filter-button[aria-pressed=\"true\"]",
            ".srcx-dashboard__architecture-count-filter",
            "[data-srcx-graph-view-count]",
        ).forEach { marker ->
            assertTrue(css.contains(marker), "Canonical Atlas CSS is missing chart-dock behavior: $marker")
        }
        assertHostHudCss(hostCss)
        assertFalse(
            css.contains("max-height: min(62%, 520px)"),
            "Atlas chart dock must not retain its vertically scrolling compact-height cap",
        )
    }

    @Test
    fun opensOneCompactScopePanelAndClosesItFromTheButtonOrOutsideClick() {
        val switcher =
            sourceFile(
                "src/wasmJsMain/kotlin/zone/clanker/docx/web/atlas/dom/controls/AtlasScopeLevelSwitcher.kt",
            ).readText()

        listOf(
            "private var activeLevel: AtlasScopeLevel? = null",
            "activeLevel = level.takeUnless { activeLevel == level }",
            "wireOutsideClick(root)",
            "closeMapSheet(root)",
            "[data-srcx-filters-sheet]",
            "[data-srcx-layers-sheet]",
            "event.stopPropagation()",
            "if (activeLevel != null) close(root)",
            "panel.hidden = !active",
            "button.setAttribute(\"aria-expanded\", active.toString())",
            "data-srcx-active-scope-level",
            "data-srcx-scope-chooser-state",
            "BUILD(\"build\", \"Builds\")",
            "PROJECT(\"project\", \"Projects\")",
            "SOURCE_SET(\"source-set\", \"Source sets\")",
            "root.requiredInput(level.searchSelector).focus()",
            "\"ArrowRight\", \"ArrowDown\"",
        ).forEach { marker ->
            assertTrue(switcher.contains(marker), "Scope switcher is missing compact disclosure behavior: $marker")
        }
    }

    @Test
    fun advancesThroughTheVirtualizedMultiSelectScopeHierarchy() {
        val navigator = controlSource("AtlasDomNavigatorRenderer.kt")
        val buildRenderer = controlSource("AtlasBuildNavigatorRenderer.kt")
        val projectRenderer = controlSource("AtlasProjectNavigatorRenderer.kt")
        val controls = controlSource("AtlasNavigatorControls.kt")
        val virtualRail = controlSource("AtlasVirtualizedChoiceRail.kt")

        assertTrue(navigator.contains("levelSwitcher.advanceTo(root, AtlasScopeLevel.PROJECT)"))
        assertTrue(navigator.contains("levelSwitcher.advanceTo(root, AtlasScopeLevel.SOURCE_SET)"))
        assertTrue(buildRenderer.contains("onFirstBuildSelected()"))
        assertTrue(projectRenderer.contains("onFirstProjectSelected()"))
        assertTrue(controls.contains("data-srcx-roving-item"))
        assertTrue(controls.contains("data-srcx-selected-count"))
        assertTrue(controls.contains("focusAndCloseScopeChooser"))
        assertTrue(virtualRail.contains("data-srcx-choice-layout\", \"five-row-window"))
        assertTrue(virtualRail.contains("data-srcx-virtualized\", \"true"))
        assertTrue(virtualRail.contains("data-srcx-mounted-choice-count"))
        assertTrue(virtualRail.contains("for (index in window.start until window.end)"))
        assertTrue(virtualRail.contains("VIRTUAL_VISIBLE_ROWS = 5"))
        assertTrue(virtualRail.contains("VIRTUAL_OVERSCAN_ROWS = 1"))
        assertTrue(virtualRail.contains("pinSelectedChoices"))
        assertTrue(virtualRail.contains("focusAndCloseScopeChooser(root, kind)"))
        assertFalse(virtualRail.contains("allChoices.forEach { choice -> filterButton"))
        assertTrue(buildRenderer.contains("compactDetail = null"))
        assertTrue(controls.contains("multi-select"))
    }

    @Test
    fun keepsOnePersistentToggleForNormalAndFullscreenUse() {
        val renderer =
            sourceFile(
                "src/wasmJsMain/kotlin/zone/clanker/docx/web/atlas/dom/controls/AtlasViewportControlsRenderer.kt",
            ).readText()

        listOf(
            "[data-srcx-chart-controls-toggle]",
            "[data-srcx-chart-controls]",
            "data-srcx-chart-controls-state",
            "button.hidden = false",
            "Expand chart controls",
            "Hide chart controls",
        ).forEach { marker ->
            assertTrue(renderer.contains(marker), "Atlas controls renderer is missing dock state: $marker")
        }
    }

    @Test
    fun filtersOnlyByCategoriesRepresentedInTheTypedSnapshot() {
        val controller =
            listOf(
                "src/wasmJsMain/kotlin/zone/clanker/docx/web/atlas/AtlasGraphFilters.kt",
                "src/wasmJsMain/kotlin/zone/clanker/docx/web/atlas/AtlasGraphQueryFilters.kt",
                "src/wasmJsMain/kotlin/zone/clanker/docx/web/atlas/GraphDeclarationFilter.kt",
            ).joinToString("\n") { path -> sourceFile(path).readText() }
        val request =
            sourceFile(
                "src/commonMain/kotlin/zone/clanker/docx/web/atlas/projection/AtlasProjectFrameRequest.kt",
            ).readText()
        val projection =
            listOf(
                "src/commonMain/kotlin/zone/clanker/docx/web/atlas/projection/AtlasProjectionContext.kt",
                "src/commonMain/kotlin/zone/clanker/docx/web/atlas/projection/AtlasNodeMatcher.kt",
            ).joinToString("\n") { path -> sourceFile(path).readText() }

        listOf(
            "FUNCTIONS(\"Functions & methods\", setOf(SymbolKind.FUNCTION))",
            "val relationshipFilters",
            "val declarationFilters",
            "val sourceSetIds",
            "val searchTargets",
            "val relationshipCountMoreThan",
            "val relationshipDirection",
            "fun toggleRelationshipFilter",
            "fun toggleDeclarationFilter",
            "fun toggleSearchTarget",
            "fun updateRelationshipCountMoreThan",
            "fun updateRelationshipDirection",
            "fun updateSourceSets",
        ).forEach { marker ->
            assertTrue(controller.contains(marker), "Atlas controller is missing typed filter state: $marker")
        }
        assertFalse(controller.contains("SymbolKind.METHOD"), "The typed snapshot has no method declaration kind")
        assertTrue(request.contains("val sourceSetIds: Set<String>"))
        assertTrue(request.contains("val symbolKinds: Set<SymbolKind>"))
        assertTrue(request.contains("val searchTargets: Set<AtlasSearchTarget>"))
        assertTrue(request.contains("val relationshipCountFilter: AtlasRelationshipCountFilter?"))
        assertTrue(projection.contains("symbol.kind in request.symbolKinds"))
        assertTrue(projection.contains("file.sourceSetId in request.sourceSetIds"))
    }

    @Test
    fun groupsCatalogSourceSetsByNameAcrossTheEffectiveProjects() {
        val renderer =
            sourceFile(
                "src/wasmJsMain/kotlin/zone/clanker/docx/web/atlas/dom/controls/" +
                    "AtlasSourceSetNavigatorRenderer.kt",
            ).readText()
        val presentation =
            sourceFile(
                "src/commonMain/kotlin/zone/clanker/docx/web/catalog/WorkspaceCatalogPresentation.kt",
            ).readText()
        val application =
            sourceFile(
                "src/wasmJsMain/kotlin/zone/clanker/docx/web/application/DocxApplication.kt",
            ).readText()

        assertTrue(renderer.contains("orderedSourceSets(model.summary)"))
        assertTrue(renderer.contains("sourceSet.fileCount"))
        assertTrue(renderer.contains("onSourceSetsSelected"))
        assertTrue(renderer.contains(".groupBy { sourceSet -> sourceSet.name }"))
        assertTrue(renderer.contains("sourceSetIds = matches.map { sourceSet -> sourceSet.id }.toSet()"))
        assertTrue(renderer.contains("value = choice.name"))
        assertTrue(renderer.contains("controller.sourceSetIds - choice.sourceSetIds"))
        assertTrue(renderer.contains("controller.sourceSetIds + choice.sourceSetIds"))
        assertTrue(renderer.contains("onSourceSetsSelected(emptySet())"))
        assertTrue(renderer.contains("FilterSelectionState.SELECTED"))
        assertTrue(renderer.contains("FilterSelectionState.PARTIAL"))
        assertTrue(renderer.contains("FilterSelectionState.UNSELECTED"))
        assertTrue(renderer.contains("data-srcx-source-set-id-count"))
        assertTrue(renderer.contains("standardSourceSetRank"))
        assertTrue(renderer.contains("model.selectedProjectIds.isNotEmpty() -> model.selectedProjectIds"))
        assertTrue(renderer.contains("model.selectedBuildIds.isNotEmpty()"))
        assertTrue(renderer.contains("else ->"))
        assertTrue(renderer.contains(".map { project -> project.id }"))
        assertTrue(application.contains("controller.replaceSourceSets(sourceSetIds)"))
        assertFalse(application.contains("controller.graph.updateSourceSets"))
        assertFalse(application.contains("controller.selectProjects(projectIds, buildIds)"))
        assertTrue(presentation.contains("orderedProjects(summary).flatMap"))
        assertFalse(renderer.contains("ProjectGraphShard::files"))
        assertFalse(renderer.contains("model.buildProjects"))
        assertFalse(renderer.contains("sourceSetLabel("))
    }

    @Test
    fun narrowsProjectsToSelectedBuildsBeforeTheSourceLevel() {
        val renderer =
            sourceFile(
                "src/wasmJsMain/kotlin/zone/clanker/docx/web/atlas/dom/controls/" +
                    "AtlasProjectNavigatorRenderer.kt",
            ).readText()

        assertTrue(renderer.contains("project.buildId in model.selectedBuildIds"))
        assertTrue(renderer.contains("orderedBuilds(model.summary)"))
        assertTrue(renderer.contains("orderedProjects(model.summary)"))
    }

    @Test
    fun keepsTheReportScrollableAndLimitsOneScreenLayoutToNativeFullscreen() {
        val hostCss = sourceFile("src/wasmJsMain/resources/docx.css").readText()

        assertTrue(hostCss.contains("[data-docx-atlas-surface]"))
        assertTrue(hostCss.contains("width: calc(100% - 12px)"))
        assertTrue(hostCss.contains("overflow-x: hidden"))
        assertTrue(hostCss.contains("overflow-y: auto"))
        assertTrue(hostCss.contains("scrollbar-gutter: stable"))
        assertTrue(hostCss.contains("[data-srcx-fullscreen=\"true\"]"))
        assertTrue(hostCss.contains("block-size: 100% !important"))
        assertTrue(hostCss.contains(".srcx-dashboard__architecture-canvas[data-docx-atlas-canvas]"))
        assertTrue(hostCss.contains("touch-action: none"))
        assertTrue(
            hostCss.contains(
                "[data-docx-atlas-host][data-docx-atlas-renderer=\"canvas\"] > " +
                    ".srcx-dashboard__architecture-svg",
            ),
        )
        assertTrue(hostCss.contains("pointer-events: none !important"))
        assertTrue(hostCss.contains("[data-docx-atlas-host] > .srcx-dashboard__architecture-chart-controls"))
        assertTrue(
            hostCss.contains(
                "[data-docx-atlas-surface] [data-docx-atlas-host] > " +
                    ".srcx-dashboard__architecture-history-toolbar",
            ),
        )
        assertFalse(hostCss.contains(".srcx-dashboard__section:not(#architecture)"))
    }

    @Test
    fun loadsOneBoundedCanvasAndPublishesItsVisibleCounts() {
        val template = sourceFile("src/wasmJsMain/resources/index.html").readText()
        val canvas = sourceFile("src/wasmJsMain/resources/docx-atlas-map.js").readText()
        val bridge =
            sourceFile(
                "src/wasmJsMain/kotlin/zone/clanker/docx/web/atlas/dom/AtlasGraphBridgeRuntime.kt",
            ).readText()

        assertTrue(template.contains("<script src=\"assets/docx-atlas-map.js\"></script>"))
        listOf(
            "document.createElement(\"canvas\")",
            "data-docx-atlas-canvas",
            "data-docx-atlas-renderer\", \"canvas",
            "data-docx-atlas-node-count",
            "data-docx-atlas-edge-count",
            "new ResizeObserver",
            "requestAnimationFrame",
            "global.docxAtlasMap = Object.freeze({ mount, update, destroy, command, snapshot })",
        ).forEach { marker ->
            assertTrue(canvas.contains(marker), "Canvas renderer is missing bounded-map contract: $marker")
        }
        assertTrue(bridge.contains("if (mapMounted) AtlasMapInterop.command"))
        assertTrue(bridge.contains("mapMounted -> AtlasMapInterop.snapshot"))
        assertFalse(canvas.contains("document.createElement(\"g\")"))
        assertFalse(canvas.contains("querySelectorAll(\"[data-docx-atlas-node-id]"))
    }

    @Test
    fun exposesComposableLayersAndReducerBackedMapHistory() {
        val template = sourceFile("src/wasmJsMain/resources/index.html").readText()
        val sessionControls =
            sourceFile(
                "src/wasmJsMain/kotlin/zone/clanker/docx/web/atlas/dom/controls/" +
                    "AtlasSessionControlsRenderer.kt",
            ).readText()
        val lensControls =
            sourceFile(
                "src/wasmJsMain/kotlin/zone/clanker/docx/web/atlas/dom/controls/" +
                    "AtlasLensControlsRenderer.kt",
            ).readText()
        val controller =
            sourceFile(
                "src/wasmJsMain/kotlin/zone/clanker/docx/web/atlas/AtlasController.kt",
            ).readText()

        assertSessionTemplateMarkers(template)
        assertSessionControlBehavior(sessionControls)
        assertSessionBackedLensAndHistory(lensControls, controller)
    }

    @Test
    fun loadsFileBodiesOnlyForSelectedFileOrRelationshipEvidence() {
        val runtime =
            sourceFile(
                "src/wasmJsMain/kotlin/zone/clanker/docx/web/atlas/dom/AtlasDomRuntime.kt",
            ).readText()
        val requestRuntime =
            sourceFile(
                "src/wasmJsMain/kotlin/zone/clanker/docx/web/atlas/dom/AtlasEvidenceRequestRuntime.kt",
            ).readText()
        val statusRenderer =
            sourceFile(
                "src/wasmJsMain/kotlin/zone/clanker/docx/web/atlas/dom/AtlasDomStatusRenderer.kt",
            ).readText()
        val effects =
            sourceFile(
                "src/wasmJsMain/kotlin/zone/clanker/docx/web/application/SourceContentEffects.kt",
            ).readText()
        val loader =
            sourceFile(
                "src/commonMain/kotlin/zone/clanker/docx/web/site/WorkspaceSiteLoader.kt",
            ).readText()
        val sourceRenderer =
            sourceFile(
                "src/wasmJsMain/kotlin/zone/clanker/docx/web/atlas/dom/detail/AtlasSourceEvidenceRenderer.kt",
            ).readText()

        listOf(
            "AtlasEvidenceRequestRuntime.requestProject",
            "AtlasEvidenceRequestRuntime.requestSource",
        ).forEach { marker ->
            assertTrue(runtime.contains(marker), "Atlas runtime is missing lazy-evidence request: $marker")
        }
        listOf(
            "data-docx-atlas-evidence-state",
            "data-docx-atlas-evidence-project-id",
            "data-docx-atlas-source-state",
            "data-docx-atlas-source-project-id",
            "data-docx-atlas-source-file-id",
            "data-docx-atlas-source-content-hash",
        ).forEach { marker ->
            assertTrue(statusRenderer.contains(marker), "Atlas status renderer is missing lazy-evidence state: $marker")
        }
        assertTrue(requestRuntime.contains("fun requestProject("))
        assertTrue(requestRuntime.contains("fun requestSource("))
        assertTrue(requestRuntime.contains("nodeId in project.ownedFileIds"))
        assertTrue(requestRuntime.contains("nodeId in project.ownedSymbolIds"))
        assertFalse(requestRuntime.contains("takeIf { inspectionRequest(model, selection) == null }"))
        assertTrue(effects.contains("loader.loadSourceContent(request.site, request.projectId, request.fileId)"))
        assertTrue(loader.contains("suspend fun loadSourceContent("))
        assertTrue(loader.contains("project.sourceContents.singleOrNull"))
        assertTrue(sourceRenderer.contains("data-srcx-source-line"))
        assertTrue(sourceRenderer.contains("is-active-relationship"))
        assertTrue(sourceRenderer.contains("aria-current"))
    }

    @Test
    fun browserSmokeTargetsVirtualHudCanvasLayersHistoryAndLazyEvidence() {
        val smoke = workspaceFile(DOCX_WEB_SMOKE_DRIVER).readText()

        listOf(
            "mounted <= 7",
            "data-srcx-mounted-choice-count",
            "data-srcx-virtualized",
            "noHorizontalScroll(panel",
            "data-docx-atlas-renderer",
            "detailed-overview-stable",
            "detailed-overview-interactions",
            "layer-combination",
            "data-srcx-history-back",
            "history-forward",
            "history-home",
            "data-docx-atlas-evidence-state",
            "file-source-evidence",
            "relationship-source-evidence",
            "is-active-relationship",
            "chrome-collapsed",
            "chrome-expanded",
            "canvas-interactions",
            "secondary-nodes:",
            "secondary-edges:",
            "clear-secondary",
            "Shift-drag did not add boxed nodes",
            "two-pointer pinch did not zoom",
            "one-pointer touch did not pan",
            "post-drag sibling collision resolution",
            "hover did not publish a non-committing preview",
            "Space did not toggle the focused Canvas hit",
        ).forEach { marker ->
            assertTrue(smoke.contains(marker), "Browser smoke is missing current Atlas assertion: $marker")
        }
        listOf(
            "window.d3",
            "architecture-svg-node",
            "architecture-svg-edge",
            "architecture-zoom-layer",
            "runD3InteractionGates",
            "control-dock",
        ).forEach { staleMarker ->
            assertFalse(smoke.contains(staleMarker), "Browser smoke retained stale graph contract: $staleMarker")
        }
    }

    private fun sourceFile(relativePath: String): File {
        val candidates = listOf(File(relativePath), File("docx-web/$relativePath"))
        return requireNotNull(candidates.firstOrNull(File::isFile)) {
            "Unable to locate $relativePath from ${File(".").absolutePath}"
        }
    }

    private fun controlSource(fileName: String): String =
        sourceFile("src/wasmJsMain/kotlin/zone/clanker/docx/web/atlas/dom/controls/$fileName").readText()

    private fun workspaceFile(relativePath: String): File {
        val candidates = listOf(File(relativePath), File("../$relativePath"))
        return requireNotNull(candidates.firstOrNull(File::isFile)) {
            "Unable to locate $relativePath from ${File(".").absolutePath}"
        }
    }

    private companion object {
        const val CANONICAL_DASHBOARD_CSS =
            "srcx-gradle-plugin/src/main/resources/zone/clanker/gradle/srcx/report/html/dashboard.css"
        const val DOCX_WEB_SMOKE_DRIVER =
            "build-logic/src/main/kotlin/zone/clanker/gradle/conventions/DocxWebSmokeDriver.kt"
    }
}

private fun assertHostHudCss(hostCss: String) {
    listOf(
        "Host HUD: search left, semantic scope center, map tools right, and one transient sheet.",
        """
        .srcx-theme[data-docx-atlas-surface] .srcx-dashboard__architecture-graph
            .srcx-dashboard__architecture-chart-controls {
        """.trimIndent(),
        "inline-size: min(380px, 30vw)",
        "block-size: 44px",
        "[data-docx-atlas-surface] .srcx-dashboard__architecture-map-toolbar",
        ".srcx-dashboard__architecture-filters-sheet",
        "grid-template-areas:",
        "\"lenses search\"",
        "\"declarations relationships\"",
        "overflow: hidden",
        "> :not([data-srcx-chart-controls-toggle])",
        "display: flex !important",
        "Keep the map controls usable while source evidence is open.",
        "> .srcx-dashboard__architecture-detail-resize",
        "inset-block-start: 64px",
        "inset-block-start: 118px",
        "inset-block-start: 166px",
    ).forEach { marker ->
        assertTrue(hostCss.contains(marker), "Host Atlas CSS is missing HUD override behavior: $marker")
    }
    assertFalse(
        hostCss.contains("overflow-x: auto"),
        "The one-screen host HUD must never introduce horizontal scrolling",
    )
}

private fun assertSessionTemplateMarkers(template: String) {
    listOf(
        "data-srcx-layers-toggle",
        "data-srcx-layers-sheet",
        "data-srcx-layer-presets",
        "data-srcx-layer-options",
        "data-srcx-filters-toggle",
        "data-srcx-filters-sheet",
        "data-srcx-filters-close",
        "data-srcx-history-toolbar",
        "data-srcx-history-back",
        "data-srcx-history-forward",
        "data-srcx-history-home",
        "data-srcx-history-breadcrumb",
    ).forEach { marker ->
        assertTrue(template.contains(marker), "Atlas template is missing map-session control: $marker")
    }
}

private fun assertSessionControlBehavior(sessionControls: String) {
    listOf(
        "LayerPreset(\"Focus\"",
        "\"Explore\"",
        "LayerPreset(\"Everything\"",
        "AtlasLayer.entries.forEach",
        "controller.toggleLayer(layer)",
        "controller.replaceLayers(preset.layers)",
        "actions.onNavigateBack()",
        "actions.onNavigateForward()",
        "controller.focusSemanticNode(null)",
        "onGraphCommand(\"fit\")",
        "AtlasOverlay.RelationshipSettings",
        "closeOpenMapSheet",
        "closeSheetOutside",
        "closeSheetOnEscape",
        "toggle.focus()",
        "MAP_SHEET_SELECTOR",
    ).forEach { marker ->
        assertTrue(sessionControls.contains(marker), "Session controls are missing behavior: $marker")
    }
}

private fun assertSessionBackedLensAndHistory(
    lensControls: String,
    controller: String,
) {
    assertTrue(lensControls.contains("val selected ="))
    assertTrue(lensControls.contains("controller.session"))
    assertTrue(lensControls.contains("controller.toggleLayer(layer)"))
    assertTrue(lensControls.contains("model.preserveDetailedOverview"))
    assertTrue(lensControls.contains("controller.graph.updateLens(lens)"))
    assertTrue(lensControls.contains("controller.layoutMode == AtlasLayoutMode.COMPACT"))
    assertTrue(lensControls.contains("AtlasOverlayChanged(AtlasOverlay.Closed)"))
    assertTrue(lensControls.contains("controller.graph.lens == lens"))
    assertTrue(lensControls.contains("data-srcx-graph-lens"))
    assertTrue(lensControls.contains("data-srcx-graph-layers"))
    assertTrue(controller.contains("dispatch(AtlasNavigateBack)"))
    assertTrue(controller.contains("dispatch(AtlasNavigateForward)"))
}

private fun assertFilterControlMarkers(template: String) {
    listOf(
        "data-srcx-chart-controls-toggle",
        "data-srcx-chart-controls",
        "aria-controls=\"srcx-atlas-chart-controls\"",
        "data-srcx-filters-toggle",
        "aria-controls=\"srcx-atlas-filters-sheet\"",
        "data-srcx-filters-sheet",
        "data-srcx-filters-close",
        "data-srcx-scope-control=\"build\"",
        "data-srcx-scope-control=\"project\"",
        "data-srcx-scope-control=\"source-set\"",
        "data-srcx-scope-stepper",
        "role=\"group\"",
        "data-srcx-scope-level-button=\"build\"",
        "data-srcx-scope-level-button=\"project\"",
        "data-srcx-scope-level-button=\"source-set\"",
        "role=\"region\"",
        "data-srcx-build-filter-count",
        "data-srcx-project-filter-count",
        "data-srcx-source-set-filter-count",
        "data-srcx-build-filter-search",
        "data-srcx-project-filter-search",
        "data-srcx-source-set-filter-search",
        "data-srcx-build-filter-clear",
        "data-srcx-project-filter-clear",
        "data-srcx-source-set-filter-clear",
        "srcx-dashboard__architecture-scope-tools",
        "data-srcx-control-strip=\"declarations\"",
        "data-srcx-control-strip=\"relationships\"",
        "data-srcx-control-strip=\"relationship-count\"",
        "data-srcx-declaration-kind-options",
        "data-srcx-relationship-kind-clear",
        "data-srcx-relationship-count-input",
        "data-srcx-relationship-count-presets",
        "data-srcx-relationship-direction-options",
        "data-srcx-search-target-options",
        "data-srcx-search-target-summary",
        "data-srcx-graph-view-count=\"files\"",
        "data-srcx-graph-view-count=\"symbols\"",
        "data-srcx-graph-view-count=\"problems\"",
        "data-srcx-graph-view-count=\"cycles\"",
        "aria-label=\"Choose one or more builds\"",
        "aria-label=\"Choose one or more projects\"",
        "aria-label=\"Choose one or more source sets\"",
        "aria-label=\"Filter symbols by declaration type\"",
    ).forEach { marker ->
        assertTrue(template.contains(marker), "Atlas template is missing compact filter marker: $marker")
    }
    assertFalse(template.contains("<small>Many</small>"), "Scope tabs must expose truthful selection counts")
    assertFalse(template.contains("data-srcx-filter-menu="), "Scope filters must not require disclosure menus")
    assertFalse(template.contains("data-srcx-control-menu="), "Type filters must not require disclosure menus")
    assertFalse(template.contains("role=\"radiogroup\""), "Multi-select scope filters must not use radio semantics")
}

private fun assertTransientFilterSheetStructure(template: String) {
    assertTrue(
        template.indexOf("data-srcx-map-toolbar") < template.indexOf("data-srcx-filters-sheet"),
        "The filter sheet must be anchored from the in-map toolbar",
    )
    assertTrue(
        template.indexOf("data-srcx-filters-sheet") < template.indexOf("data-srcx-graph-controls"),
        "Lens and relationship controls must live inside the transient filter sheet",
    )
    assertTrue(
        template.indexOf("assets/atlas/dashboard.css") < template.indexOf("assets/docx.css"),
        "The host HUD stylesheet must load after the canonical dashboard stylesheet",
    )
    val compactHud =
        template.substring(
            template.indexOf("id=\"srcx-atlas-chart-controls\""),
            template.indexOf("data-srcx-map-toolbar"),
        )
    assertFalse(compactHud.contains("data-srcx-graph-search"), "The compact HUD must not duplicate search")
    assertFalse(compactHud.contains("data-srcx-graph-controls"), "The compact HUD must not retain a filter deck")
    val filterSheet =
        template.substring(
            template.indexOf("id=\"srcx-atlas-filters-sheet\""),
            template.indexOf("id=\"srcx-atlas-layers-sheet\""),
        )
    assertTrue(filterSheet.contains("hidden"), "The custom filter sheet must start closed")
    assertFalse(filterSheet.contains("<details"), "The custom filter sheet must not use native disclosures")
    assertFalse(filterSheet.contains("<select"), "The custom filter sheet must not use a dropdown")
}
