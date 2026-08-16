package zone.clanker.gradle.conventions

internal enum class DocxWebSmokeScenario(
    val id: String,
    val width: Int,
    val height: Int,
    val layoutMode: String,
    val minimumGraphViewportHeight: Int,
    val expectedTrace: List<String>,
) {
    WIDE(
        id = "ultrawide",
        width = 2_048,
        height = 858,
        layoutMode = "wide",
        minimumGraphViewportHeight = 300,
        expectedTrace = CURRENT_ATLAS_TRACE,
    ),
    STANDARD(
        id = "standard",
        width = 1_440,
        height = 900,
        layoutMode = "wide",
        minimumGraphViewportHeight = 300,
        expectedTrace = CURRENT_ATLAS_TRACE,
    ),
    TABLET(
        id = "tablet",
        width = 1_024,
        height = 768,
        layoutMode = "wide",
        minimumGraphViewportHeight = 260,
        expectedTrace = CURRENT_ATLAS_TRACE,
    ),
    COMPACT(
        id = "narrow",
        width = 720,
        height = 900,
        layoutMode = "compact",
        minimumGraphViewportHeight = 220,
        expectedTrace = CURRENT_ATLAS_TRACE,
    ),
    ;

    companion object {
        fun fromQuery(query: String?): DocxWebSmokeScenario? {
            val parameters =
                query
                    .orEmpty()
                    .split('&')
                    .filter(String::isNotBlank)
                    .associate { parameter -> parameter.substringBefore('=') to parameter.substringAfter('=', "") }
            if (parameters["docx-smoke"] != "1") return null
            return entries.firstOrNull { scenario -> scenario.id == parameters["scenario"] }
        }
    }
}

private val CURRENT_ATLAS_TRACE =
    listOf(
        "boot",
        "canonical-css-loaded",
        "workspace-atlas",
        "embedded-report-hud",
        "all-workspace-filters",
        "scope-sheets",
        "detailed-overview-stable",
        "detailed-overview-interactions",
        "layer-sheet",
        "layer-combination",
        "fixture-build",
        "selected-app",
        "history-back",
        "history-forward",
        "history-home",
        "source-set-main",
        "file-source-evidence",
        "relationship-source-evidence",
        "fullscreen-on",
        "chrome-collapsed",
        "chrome-expanded",
        "fullscreen-off",
        "canvas-interactions",
        "global-search",
        "no-horizontal-scroll",
    )

internal data object DocxWebSmokeDriver {
    fun instrument(
        indexHtml: String,
        scenario: DocxWebSmokeScenario,
    ): String {
        require(BODY_END in indexHtml) { "DOCX smoke could not find the closing body element" }
        val script = SCRIPT_TEMPLATE.replace(SCENARIO_PLACEHOLDER, scenario.id)
        return indexHtml.replace(BODY_END, "$script\n$BODY_END")
    }

    private const val BODY_END = "</body>"
    private const val SCENARIO_PLACEHOLDER = "__DOCX_SMOKE_SCENARIO__"
    private val SCRIPT_TEMPLATE =
        """
        <script>
        (() => {
            const root = document.getElementById("docx-app");
            const scenario = "__DOCX_SMOKE_SCENARIO__";
            const trace = [];
            const query = new URLSearchParams(window.location.search);
            const captureOnly = query.get("capture") === "1";
            const captureMode = query.get("capture-mode") || "ready";
            const keepAlive = window.setInterval(() => root.isConnected, 250);
            const failedStates = ["fatal", "catalog-failed", "project-failed"];
            const appProject = "project:fixture:app";
            const fixtureBuild = "build:fixture";
            const consumerFile = "file:fixture:consumer";
            const targetFile = "file:fixture:target";
            const constructorRelationship = "relationship:fixture:constructor";
            const canvasSelector = ".srcx-dashboard__architecture-canvas[data-docx-atlas-canvas]";
            const asyncSettlementTimeoutMillis = 2000000;
            let runtimeError = "";

            window.addEventListener("error", event => {
                runtimeError = String(event.error && event.error.message ? event.error.message : event.message);
                root.setAttribute("data-docx-smoke-runtime-error", runtimeError);
            });
            window.addEventListener("unhandledrejection", event => {
                const reason = event.reason;
                runtimeError = String(reason && reason.message ? reason.message : reason);
                root.setAttribute("data-docx-smoke-runtime-error", runtimeError);
            });

            trace.push = function(...items) {
                const size = Array.prototype.push.apply(this, items);
                root.setAttribute("data-docx-smoke-trace", this.join("|"));
                return size;
            };

            function expect(condition, message) {
                if (!condition) throw new Error(message);
            }

            function attribute(name) {
                return root.getAttribute(name) || "";
            }

            function numericAttribute(name) {
                const raw = attribute(name);
                expect(raw !== "", name + " is missing");
                const value = Number(raw);
                expect(Number.isFinite(value), name + " must be numeric, found " + raw);
                return value;
            }

            function expectValue(name, expected) {
                expect(attribute(name) === expected, name + " expected " + expected + ", found " + attribute(name));
            }

            function deepQuerySelector(searchRoot, selector) {
                const direct = searchRoot.querySelector(selector);
                if (direct) return direct;
                for (const element of searchRoot.querySelectorAll("*")) {
                    if (!element.shadowRoot) continue;
                    const nested = deepQuerySelector(element.shadowRoot, selector);
                    if (nested) return nested;
                }
                return null;
            }

            function atlasSurface() {
                return deepQuerySelector(document, "#docx-atlas-root");
            }

            function atlasHost() {
                const surface = atlasSurface();
                return surface && surface.querySelector("[data-docx-atlas-host]");
            }

            function publishFinalReadyGeometry() {
                const host = atlasHost();
                const map = host.querySelector(canvasSelector) || host.querySelector("[data-docx-atlas-svg]");
                expect(map, "Atlas has no rendered Canvas or detailed SVG map");
                root.setAttribute("data-docx-canvas-height", String(map.getBoundingClientRect().height));
            }

            function atlasAttribute(name) {
                const host = atlasHost();
                return host ? (host.getAttribute(name) || "") : "";
            }

            function numericAtlasAttribute(name) {
                const raw = atlasAttribute(name);
                expect(raw !== "", name + " is missing from the Canvas host");
                const value = Number(raw);
                expect(Number.isFinite(value), name + " must be numeric, found " + raw);
                return value;
            }

            function requiredElement(selector, parent) {
                const element = (parent || document).querySelector(selector);
                expect(element, "missing Atlas element " + selector);
                return element;
            }

            function deepActiveElement() {
                let active = document.activeElement;
                while (active && active.shadowRoot && active.shadowRoot.activeElement) {
                    active = active.shadowRoot.activeElement;
                }
                return active;
            }

            function probeEvidence() {
                const rootNames = [
                    "data-docx-state",
                    "data-docx-selected-build-id",
                    "data-docx-selected-project-id",
                    "data-docx-selected-project-content",
                    "data-docx-selected-node-id",
                    "data-docx-graph-scope",
                    "data-docx-graph-total-nodes",
                    "data-docx-graph-visible-nodes",
                    "data-docx-command-revision",
                    "data-docx-command-error",
                ];
                const atlasNames = [
                    "data-docx-atlas-renderer",
                    "data-docx-atlas-state",
                    "data-docx-atlas-load-state",
                    "data-docx-atlas-load-error",
                    "data-docx-atlas-selected-build-ids",
                    "data-docx-atlas-selected-project-ids",
                    "data-docx-atlas-selected-node-id",
                    "data-docx-atlas-node-count",
                    "data-docx-atlas-edge-count",
                    "data-docx-atlas-open-node-id",
                    "data-docx-atlas-evidence-state",
                    "data-docx-atlas-source-state",
                    "data-docx-atlas-history-can-back",
                    "data-docx-atlas-history-can-forward",
                ];
                const sessionNames = [
                    "data-docx-atlas-open-session-known",
                    "data-docx-atlas-open-session-expanded",
                    "data-docx-atlas-session-request-state",
                    "data-docx-atlas-session-request-revision",
                    "data-docx-atlas-session-request-error",
                    "data-docx-atlas-session-request-expanded-node-ids",
                    "data-docx-atlas-session-focus-expanded-node-ids",
                ];
                const surface = atlasSurface();
                const search = atlasHost()?.querySelector("[data-srcx-global-search]");
                return rootNames.map(name => name + "=" + attribute(name)).join(", ") + "; " +
                    atlasNames.map(name => name + "=" +
                        (name === "data-docx-atlas-renderer" ||
                            name === "data-docx-atlas-open-node-id" ||
                            name === "data-docx-atlas-selected-node-id" ||
                            name.endsWith("count") ?
                            atlasAttribute(name) : (surface ? surface.getAttribute(name) || "" : "")))
                        .join(", ") + "; " +
                    sessionNames.map(name => name + "=" + atlasAttribute(name)).join(", ") +
                    "; search-state=" + (search ? search.getAttribute("data-srcx-global-search-state") || "" : "") +
                    ", search-open=" + (search ? search.getAttribute("data-srcx-global-search-open") || "" : "") +
                    ", search-active=" + (search ? search.getAttribute("data-srcx-global-search-active-key") || "" : "") +
                    ", search-focused=" +
                    (search ? search.getAttribute("data-srcx-global-search-focused-target") || "" : "") +
                    ", document-active=" +
                    (deepActiveElement()?.getAttribute?.("data-srcx-global-search-target") ||
                        deepActiveElement()?.getAttribute?.("data-srcx-global-search-input") ||
                        deepActiveElement()?.tagName || "") +
                    ", search-requested=" +
                    (search ? search.getAttribute("data-srcx-global-search-requested-target") || "" : "") +
                    "; runtime-error=" + runtimeError;
            }

            function waitUntil(predicate, label, timeoutMillis = 45000) {
                return new Promise((resolve, reject) => {
                    const deadline = performance.now() + timeoutMillis;

                    function check() {
                        try {
                            const state = attribute("data-docx-state");
                            expect(failedStates.indexOf(state) < 0, label + " reached state " + state);
                            if (predicate()) {
                                resolve();
                            } else if (performance.now() >= deadline) {
                                reject(new Error(label + " timed out; last probe: " + probeEvidence()));
                            } else {
                                window.setTimeout(check, 25);
                            }
                        } catch (error) {
                            const failure = error instanceof Error ? error : new Error(String(error));
                            reject(new Error(failure.message + "; last probe: " + probeEvidence()));
                        }
                    }

                    check();
                });
            }

            function nextFrame() {
                return new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
            }

            function stylesheetReady(link) {
                if (link.sheet) return Promise.resolve();
                return new Promise((resolve, reject) => {
                    link.addEventListener("load", resolve, { once: true });
                    link.addEventListener("error", () => reject(new Error("stylesheet failed: " + link.href)), {
                        once: true,
                    });
                });
            }

            async function waitForCanonicalPaint() {
                const surface = atlasSurface();
                const links = [
                    ...document.head.querySelectorAll('link[rel="stylesheet"]'),
                    ...surface.querySelectorAll('link[rel="stylesheet"]'),
                ];
                await Promise.all(links.map(stylesheetReady));
                await nextFrame();
            }

            async function issue(name, argument, label, verify) {
                const priorRevision = numericAttribute("data-docx-command-revision");
                window.docxSmokeCommand(name, argument);
                await waitUntil(
                    () => attribute("data-docx-state") === "ready" &&
                        numericAttribute("data-docx-command-revision") > priorRevision,
                    name,
                );
                expectValue("data-docx-command-error", "");
                verify();
                trace.push(label);
            }

            function noHorizontalScroll(element, label) {
                const style = getComputedStyle(element);
                expect(style.overflowX !== "auto" && style.overflowX !== "scroll",
                    label + " enables horizontal scrolling with overflow-x=" + style.overflowX);
                if (style.overflowX === "hidden" || style.overflowX === "clip") {
                    return;
                }
                expect(element.scrollWidth <= element.clientWidth + 2,
                    label + " scrolls horizontally: " + element.scrollWidth + " > " + element.clientWidth);
            }

            function verifyPageAndHudDoNotScrollHorizontally() {
                const surface = atlasSurface();
                const elements = [
                    [document.documentElement, "document"],
                    [document.body, "body"],
                    [root, "application root"],
                    [surface.parentElement, "interop host"],
                    [surface, "Atlas surface"],
                    [requiredElement("#architecture", surface), "architecture section"],
                    [requiredElement("[data-srcx-architecture-graph]", surface), "map graph"],
                    [atlasHost(), "map viewport"],
                ];
                elements.forEach(entry => noHorizontalScroll(entry[0], entry[1]));
            }

            function verifyCanonicalStylesheets() {
                const paths = ["assets/atlas/theme.css", "assets/atlas/dashboard.css", "assets/docx.css"];
                const template = requiredElement("#docx-atlas-template");
                paths.forEach(path => {
                    expect(document.head.querySelector('link[rel="stylesheet"][href="' + path + '"]'),
                        "document head is missing canonical stylesheet " + path);
                    expect(template.content.querySelector('link[rel="stylesheet"][href="' + path + '"]'),
                        "Atlas template is missing canonical stylesheet " + path);
                    expect(atlasSurface().querySelector('link[rel="stylesheet"][href="' + path + '"]'),
                        "cloned Atlas surface is missing canonical stylesheet " + path);
                });
                expect(document.querySelector('script[src="assets/docx-atlas-map.js"]'),
                    "document is missing the bounded Canvas renderer script");
                expect(window.docxAtlasMap && typeof window.docxAtlasMap.snapshot === "function",
                    "Canvas map API is unavailable");
            }

            function verifyEmbeddedReportAndHud() {
                const surface = atlasSurface();
                const architecture = requiredElement("#architecture", surface);
                const graph = requiredElement("[data-srcx-architecture-graph]", surface);
                const host = atlasHost();
                const tolerance = 3;
                const surfaceRect = surface.getBoundingClientRect();
                const architectureRect = architecture.getBoundingClientRect();
                const graphRect = graph.getBoundingClientRect();
                const hostRect = host.getBoundingClientRect();
                expect(surfaceRect.width <= window.innerWidth + tolerance && surfaceRect.width >= window.innerWidth * 0.9,
                    "report surface does not use the available viewport width");
                expect(surface.scrollHeight > surface.clientHeight + tolerance,
                    "the complete report is not vertically scrollable");
                expect(architectureRect.width <= surfaceRect.width + tolerance && graphRect.width <= architectureRect.width + tolerance,
                    "embedded Atlas escapes the report section width");
                expect(hostRect.width <= graphRect.width + tolerance && hostRect.height >= 420,
                    "embedded Atlas viewport is missing or undersized");
                [
                    ".srcx-masthead",
                    ".srcx-dashboard__hero",
                    ".srcx-dashboard__metric-strip",
                    ".srcx-dashboard__nav",
                    "#builds",
                    "#health",
                    "#findings",
                    "#architecture > .srcx-section-heading",
                    ".srcx-dashboard__architecture-summary",
                    ".srcx-dashboard__architecture-guide",
                ].forEach(selector => {
                    const element = requiredElement(selector, surface);
                    expect(getComputedStyle(element).display !== "none",
                        "required report content is hidden: " + selector);
                });
                const controls = requiredElement("[data-srcx-chart-controls]", host);
                const search = requiredElement("[data-srcx-global-search]", host);
                const toolbar = requiredElement("[data-srcx-map-toolbar]", host);
                const history = requiredElement("[data-srcx-history-toolbar]", host);
                expect(getComputedStyle(controls).position === "absolute", "scope controls are not a map HUD");
                expect(getComputedStyle(toolbar).position === "absolute", "map actions are not overlaid on the map");
                expect(getComputedStyle(history).position === "absolute", "history is not overlaid on the map");
                const hudRects = [
                    [search.getBoundingClientRect(), "global search"],
                    [controls.getBoundingClientRect(), "scope controls"],
                    [toolbar.getBoundingClientRect(), "map toolbar"],
                ];
                hudRects.forEach(entry => {
                    const rect = entry[0];
                    expect(rect.left >= hostRect.left - tolerance && rect.right <= hostRect.right + tolerance &&
                        rect.top >= hostRect.top - tolerance && rect.bottom <= hostRect.bottom + tolerance,
                        entry[1] + " is clipped outside the embedded Atlas: " +
                        [rect.left, rect.top, rect.right, rect.bottom].join(","));
                });
                const overlaps = (left, right) =>
                    left.left < right.right - tolerance && left.right > right.left + tolerance &&
                    left.top < right.bottom - tolerance && left.bottom > right.top + tolerance;
                const rectText = rect => [rect.left, rect.top, rect.right, rect.bottom].join(",");
                expect(!overlaps(hudRects[0][0], hudRects[1][0]),
                    "global search obscures the scope controls: search=" + rectText(hudRects[0][0]) +
                    ", scope=" + rectText(hudRects[1][0]) +
                    ", search-open=" + search.getAttribute("data-srcx-global-search-open"));
                expect(!overlaps(hudRects[1][0], hudRects[2][0]),
                    "map toolbar obscures the scope controls: scope=" + rectText(hudRects[1][0]) +
                    ", toolbar=" + rectText(hudRects[2][0]));
                Array.from(controls.querySelectorAll("[data-srcx-scope-level-button]")).forEach(button => {
                    const rect = button.getBoundingClientRect();
                    expect(rect.width >= 72 && rect.height >= 40,
                        "scope trigger is clipped or too small: " + button.textContent.trim());
                });
                verifyPageAndHudDoNotScrollHorizontally();
            }

            function assertEnabled(selector, label) {
                const elements = Array.from(atlasSurface().querySelectorAll(selector));
                expect(elements.length > 0, "missing " + label);
                expect(elements.every(element => !element.disabled), label + " is disabled at All workspace");
            }

            function verifyAllWorkspaceFiltersEnabled() {
                assertEnabled("[data-srcx-scope-level-button]", "scope level buttons");
                assertEnabled("[data-srcx-graph-view]", "composable view layers");
                assertEnabled("[data-srcx-graph-search]", "typed search");
                assertEnabled("[data-srcx-search-target-options] button", "search target filters");
                assertEnabled("[data-srcx-declaration-kind-options] button", "declaration filters");
                assertEnabled("[data-srcx-relationship-kind-options] button", "relationship filters");
                assertEnabled("[data-srcx-relationship-count-presets] button", "relationship count filters");
                assertEnabled("[data-srcx-relationship-direction-options] button", "relationship directions");
                const lenses = Array.from(atlasSurface().querySelectorAll("[data-srcx-graph-view]"));
                expect(lenses.length === 4, "Atlas must expose Files, Symbols, Problems, and Cycles layers");
                expect(lenses.some(button => button.getAttribute("aria-pressed") === "true"),
                    "All workspace has no visible data layer");
            }

            function scopeTrigger(level) {
                return requiredElement('[data-srcx-scope-level-button="' + level + '"]', atlasSurface());
            }

            function scopePanel(level) {
                return requiredElement('[data-srcx-scope-control="' + level + '"]', atlasSurface());
            }

            function scopeRail(level) {
                return requiredElement('[data-srcx-' + level + '-filter]', scopePanel(level));
            }

            function scopeIsOpen(level) {
                const triggers = Array.from(atlasSurface().querySelectorAll("[data-srcx-scope-level-button]"));
                const panels = Array.from(atlasSurface().querySelectorAll("[data-srcx-scope-control]"));
                return scopeTrigger(level).getAttribute("aria-expanded") === "true" &&
                    !scopePanel(level).hidden &&
                    triggers.filter(button => button.getAttribute("aria-expanded") === "true").length === 1 &&
                    panels.filter(panel => !panel.hidden).length === 1;
            }

            async function openScope(level) {
                if (!scopeIsOpen(level)) scopeTrigger(level).click();
                await waitUntil(() => scopeIsOpen(level), level + " scope sheet open");
                await waitForCanonicalPaint();
                await nextFrame();
                verifyVirtualScope(level);
            }

            function verifyVirtualScope(level) {
                const panel = scopePanel(level);
                const rail = scopeRail(level);
                const mounted = Number(rail.getAttribute("data-srcx-mounted-choice-count"));
                const total = Number(rail.getAttribute("data-srcx-total-choice-count"));
                const rows = Array.from(rail.querySelectorAll("[data-srcx-choice-index]"));
                expect(rail.getAttribute("data-srcx-virtualized") === "true",
                    level + " scope does not use a virtual window");
                expect(Number.isInteger(mounted) && mounted >= 0 && mounted <= 7,
                    level + " scope mounted " + mounted + " rows; maximum is seven");
                expect(rows.length === mounted && mounted <= total,
                    level + " virtual row counts are inconsistent");
                rows.forEach(row => {
                    expect(row.getBoundingClientRect().height >= 43,
                        level + " row is below the 44px interaction target");
                });
                const railStyle = getComputedStyle(rail);
                expect(railStyle.overflowX === "hidden",
                    level + " rail allows horizontal scrolling: overflow-x=" + railStyle.overflowX +
                    ", class=" + rail.className + ", width=" + rail.clientWidth + "/" + rail.scrollWidth);
                expect(railStyle.overflowY === "auto" || railStyle.overflowY === "scroll",
                    level + " rail is not a bounded five-row vertical window");
                noHorizontalScroll(panel, level + " sheet");
                noHorizontalScroll(rail, level + " virtual rail");
            }

            async function verifyScopeSheets() {
                await openScope("build");
                const buildSearch = requiredElement("[data-srcx-build-filter-search]", scopePanel("build"));
                expect(deepActiveElement() === buildSearch, "opening Builds does not focus its search");
                buildSearch.dispatchEvent(new KeyboardEvent("keydown", { key: "ArrowDown", bubbles: true }));
                await nextFrame();
                const allBuilds = requiredElement("[data-srcx-build-filter-clear] button", scopePanel("build"));
                expect(deepActiveElement() === allBuilds, "ArrowDown does not enter the Builds choice list");
                allBuilds.dispatchEvent(new KeyboardEvent("keydown", { key: "ArrowDown", bubbles: true }));
                await nextFrame();
                expect(deepActiveElement().hasAttribute("data-srcx-choice-index"),
                    "scope choice keyboard navigation is not deterministic");
                deepActiveElement().dispatchEvent(new KeyboardEvent("keydown", { key: "Escape", bubbles: true }));
                await waitUntil(() => !scopeIsOpen("build"), "Escape closes Builds");
                expect(deepActiveElement() === scopeTrigger("build"),
                    "Escape did not return focus to the Builds trigger");

                await openScope("project");
                atlasHost().dispatchEvent(new MouseEvent("click", { bubbles: true, composed: true, view: window }));
                await waitUntil(() => !scopeIsOpen("project"), "outside click closes Projects");

                await openScope("build");
                scopeTrigger("source-set").click();
                await waitUntil(() => scopeIsOpen("source-set"), "Source sets replaces Builds");
                verifyVirtualScope("source-set");
                const main = requiredElement('[data-srcx-filter-source-set="main"]', scopePanel("source-set"));
                expect(main.getAttribute("data-srcx-source-set-id-count") === "2",
                    "All-workspace sources are repeated per project instead of grouped by name");
                scopeTrigger("source-set").click();
                await waitUntil(() => !scopeIsOpen("source-set"), "Source sets closes from its trigger");
            }

            function textButton(host, label) {
                const button = Array.from(host.querySelectorAll("button"))
                    .find(candidate => candidate.textContent.trim() === label);
                expect(button, "missing button " + label);
                return button;
            }

            function layerNames() {
                return (atlasSurface().getAttribute("data-srcx-graph-layers") || "")
                    .split(",")
                    .filter(Boolean);
            }

            async function verifyLayerSheetAndCombinations() {
                const toggle = requiredElement("[data-srcx-layers-toggle]", atlasSurface());
                const sheet = requiredElement("[data-srcx-layers-sheet]", atlasSurface());
                toggle.click();
                await waitUntil(() => !sheet.hidden && toggle.getAttribute("aria-expanded") === "true",
                    "layer sheet open");
                expect(Array.from(atlasSurface().querySelectorAll("[data-srcx-scope-level-button]"))
                    .every(button => button.getAttribute("aria-expanded") === "false"),
                    "Layers and a scope sheet are open at the same time");
                const presets = requiredElement("[data-srcx-layer-presets]", sheet);
                const options = requiredElement("[data-srcx-layer-options]", sheet);
                ["Focus", "Explore", "Everything"].forEach(label => textButton(presets, label));
                trace.push("layer-sheet");

                textButton(presets, "Focus").click();
                await waitUntil(() => layerNames().join(",") === "containment,relationships,labels",
                    "Focus layer preset");
                textButton(presets, "Explore").click();
                await waitUntil(() => {
                    const names = layerNames();
                    return ["containment", "files", "symbols", "problems", "relationships", "labels"]
                        .every(name => names.includes(name));
                }, "Explore layer preset");
                textButton(options, "Cycles").click();
                await waitUntil(() => layerNames().includes("cycles"), "composable Cycles layer");
                expect(Array.from(options.querySelectorAll('button[aria-pressed="true"]')).length >= 7,
                    "individual layer toggles replaced instead of composing the active layers");
                requiredElement("[data-srcx-layers-close]", sheet).click();
                await waitUntil(() => sheet.hidden && toggle.getAttribute("aria-expanded") === "false",
                    "layer sheet close");
                trace.push("layer-combination");
            }

            function verifyCanvas(expectedNodes, expectedEdges) {
                const host = atlasHost();
                const canvases = Array.from(host.querySelectorAll(canvasSelector));
                expect(atlasAttribute("data-docx-atlas-renderer") === "canvas",
                    "Atlas did not select the bounded Canvas renderer");
                expect(canvases.length === 1, "Atlas must mount exactly one Canvas, found " + canvases.length);
                const canvas = canvases[0];
                const rect = canvas.getBoundingClientRect();
                const hostRect = host.getBoundingClientRect();
                expect(canvas.width > 0 && canvas.height > 0 && rect.width > 0 && rect.height > 0,
                    "Canvas has no drawable bitmap or CSS geometry");
                expect(Math.abs(rect.width - hostRect.width) <= 2 && Math.abs(rect.height - hostRect.height) <= 2,
                    "Canvas does not fill its map viewport");
                expect(numericAtlasAttribute("data-docx-atlas-node-count") === expectedNodes,
                    "Canvas node count expected " + expectedNodes + ", found " +
                        numericAtlasAttribute("data-docx-atlas-node-count"));
                expect(numericAtlasAttribute("data-docx-atlas-edge-count") === expectedEdges,
                    "Canvas edge count expected " + expectedEdges + ", found " +
                        numericAtlasAttribute("data-docx-atlas-edge-count"));
                expect(host.querySelectorAll("[data-docx-atlas-node-id], [data-docx-atlas-edge-id]").length === 0,
                    "bounded Canvas regressed to one DOM subtree per graph record");
            }

            function verifyDetailedOverview(expectedNodes, expectedEdges) {
                const host = atlasHost();
                const svg = requiredElement("[data-docx-atlas-svg]", host);
                expect(atlasAttribute("data-docx-atlas-renderer") === "d3",
                    "Atlas replaced the detailed file-first overview renderer");
                expect(host.querySelectorAll(canvasSelector).length === 0,
                    "background semantic settlement mounted Canvas over the detailed overview");
                expect(svg.getBoundingClientRect().width > 0 && svg.getBoundingClientRect().height > 0,
                    "detailed Atlas SVG has no visible geometry");
                expect(numericAtlasAttribute("data-docx-atlas-node-count") === expectedNodes,
                    "detailed Atlas node count expected " + expectedNodes + ", found " +
                        numericAtlasAttribute("data-docx-atlas-node-count"));
                expect(numericAtlasAttribute("data-docx-atlas-edge-count") === expectedEdges,
                    "detailed Atlas edge count expected " + expectedEdges + ", found " +
                        numericAtlasAttribute("data-docx-atlas-edge-count"));
                expect(host.querySelectorAll("[data-docx-atlas-node-id]").length === expectedNodes,
                    "detailed Atlas lost its labeled file nodes");
            }

            async function verifyDetailedOverviewInteractions() {
                const surface = atlasSurface();
                const host = atlasHost();
                const svg = requiredElement("[data-docx-atlas-svg]", host);
                const api = window.docxAtlasD3;
                expect(api && typeof api.snapshot === "function" && typeof api.command === "function",
                    "detailed Atlas interaction API is unavailable");
                const original = JSON.parse(api.snapshot(host));
                const originalSvg = svg;
                const originalScroll = surface.scrollTop;

                surface.scrollTop = Math.max(0, surface.scrollHeight - surface.clientHeight);
                await nextFrame();
                surface.scrollTop = originalScroll;
                await nextFrame();
                expect(requiredElement("[data-docx-atlas-svg]", host) === originalSvg,
                    "scrolling remounted or replaced the detailed Atlas");

                api.command(host, "zoom-in");
                await waitUntil(() => JSON.parse(api.snapshot(host)).scale > original.scale,
                    "detailed Atlas zoom");
                api.command(host, "pan:36,24");
                const navigated = JSON.parse(api.snapshot(host));
                expect(navigated.panX !== original.panX || navigated.panY !== original.panY,
                    "detailed Atlas pan did not move the viewport");

                const firstNode = requiredElement("[data-docx-atlas-node-id]", host);
                const firstNodeId = firstNode.getAttribute("data-docx-atlas-node-id");
                api.command(host, "select-node:" + firstNodeId);
                await waitUntil(() =>
                    atlasAttribute("data-docx-atlas-selected-node-id") === firstNodeId &&
                    surface.getAttribute("data-docx-atlas-history-can-back") === "true",
                    "detailed Atlas selection and history");
                const historyButton = selector => requiredElement(selector, atlasSurface());
                expect(!historyButton("[data-srcx-history-back]").disabled,
                    "detailed Atlas selection did not enable Back");
                historyButton("[data-srcx-history-back]").click();
                await waitUntil(() =>
                    atlasAttribute("data-docx-atlas-selected-node-id") === "" &&
                    !historyButton("[data-srcx-history-forward]").disabled,
                    "detailed Atlas Back");
                historyButton("[data-srcx-history-forward]").click();
                await waitUntil(() =>
                    atlasAttribute("data-docx-atlas-selected-node-id") === firstNodeId &&
                    !historyButton("[data-srcx-history-back]").disabled,
                    "detailed Atlas Forward");
                api.command(host, "clear-selection");
                api.command(host, "fit");
                await waitUntil(() => JSON.parse(api.snapshot(host)).viewportMode === "fit",
                    "detailed Atlas fit reset");
                verifyDetailedOverview(original.nodeCount, original.edgeCount);
                expect(requiredElement("[data-docx-atlas-svg]", host) === originalSvg,
                    "detailed Atlas interactions replaced the renderer");
            }

            async function verifyDetailedOverviewLenses() {
                const toggle = requiredElement("[data-srcx-filters-toggle]", atlasSurface());
                const sheet = requiredElement("[data-srcx-filters-sheet]", atlasSurface());
                toggle.click();
                await waitUntil(() => !sheet.hidden && toggle.getAttribute("aria-expanded") === "true",
                    "filter sheet open for detailed lenses");

                async function selectLens(lens, expectedNodes, expectedEdges) {
                    const button = requiredElement('[data-srcx-graph-view="' + lens + '"]', sheet);
                    button.click();
                    await waitUntil(() =>
                        atlasAttribute("data-docx-atlas-lens") === lens &&
                        atlasSurface().getAttribute("data-srcx-graph-lens") === lens &&
                        button.getAttribute("aria-pressed") === "true" &&
                        numericAtlasAttribute("data-docx-atlas-node-count") === expectedNodes &&
                        numericAtlasAttribute("data-docx-atlas-edge-count") === expectedEdges,
                        "visible detailed " + lens + " lens");
                }

                await selectLens("symbols", 3, 1);
                await selectLens("problems", 3, 1);
                await selectLens("cycles", 0, 0);
                await selectLens("files", 3, 1);
                requiredElement("[data-srcx-filters-close]", sheet).click();
                await waitUntil(() => sheet.hidden && toggle.getAttribute("aria-expanded") === "false",
                    "filter sheet close after detailed lenses");
                trace.push("detailed-overview-lenses");
            }

            function mapSnapshot() {
                expect(window.docxAtlasMap && typeof window.docxAtlasMap.snapshot === "function",
                    "bounded Canvas API is unavailable");
                return JSON.parse(window.docxAtlasMap.snapshot(atlasHost()));
            }

            function emitPointer(canvas, type, pointerId, pointerType, point, extra) {
                const rect = canvas.getBoundingClientRect();
                const ending = type === "pointerup" || type === "pointercancel";
                canvas.dispatchEvent(new PointerEvent(type, Object.assign({
                    bubbles: true,
                    cancelable: true,
                    pointerId,
                    pointerType,
                    isPrimary: pointerId % 2 === 1,
                    button: 0,
                    buttons: ending ? 0 : 1,
                    clientX: rect.left + point.x,
                    clientY: rect.top + point.y,
                }, extra || {})));
            }

            async function verifyCanvasInteractions() {
                const host = atlasHost();
                const canvas = requiredElement(canvasSelector, host);
                const api = window.docxAtlasMap;
                api.command(host, "clear-selection");
                api.command(host, "fit");
                await nextFrame();
                let selectionEventCount = 0;
                const recordSelection = () => selectionEventCount += 1;
                host.addEventListener("docx-atlas-selection", recordSelection);

                const currentSlice = mapSnapshot();
                const secondaryNodeIds = currentSlice.sampleNodes.slice(0, 2).map(node => node.id);
                const secondaryRelationIds = currentSlice.sampleRelations.slice(0, 1).map(relation => relation.id);
                expect(secondaryNodeIds.length === 2 && secondaryRelationIds.length === 1,
                    "Canvas snapshot lacks canonical IDs for secondary-selection coverage");
                api.command(host, "secondary-nodes:" + secondaryNodeIds.join(",") + ",missing");
                api.command(host, "secondary-edges:" + secondaryRelationIds.join(",") + ",missing");
                let snapshot = mapSnapshot();
                expect(snapshot.secondaryNodeCount === secondaryNodeIds.length &&
                    snapshot.secondaryRelationCount === secondaryRelationIds.length,
                    "canonical secondary replacement did not retain only current-slice IDs");
                expect(selectionEventCount === 0,
                    "canonical secondary replacement fed a selection event back into the reducer");
                api.command(host, "clear-secondary");
                snapshot = mapSnapshot();
                expect(snapshot.secondaryNodeCount === 0 && snapshot.secondaryRelationCount === 0,
                    "clear-secondary did not clear both secondary sets");
                expect(selectionEventCount === 0, "clear-secondary emitted a selection event");
                expect(snapshot.sampleNodes.length > 1 && snapshot.sampleNodes.length <= 8,
                    "Canvas snapshot diagnostics are missing or unbounded");

                const first = snapshot.sampleNodes.find(node => node.id === consumerFile);
                expect(first, "Canvas snapshot lacks the file used for pointer coverage");
                const firstCenter = { x: first.x + first.width / 2, y: first.y + first.height / 2 };
                emitPointer(canvas, "pointermove", 701, "mouse", firstCenter, { buttons: 0 });
                await nextFrame();
                expect(atlasAttribute("data-docx-atlas-preview-node-id") === first.id,
                    "hover did not publish a non-committing preview");
                expect(selectionEventCount === 0, "hover preview changed selection");

                const boxStart = { x: 2, y: 2 };
                const boxEnd = { x: canvas.clientWidth - 2, y: canvas.clientHeight - 2 };
                emitPointer(canvas, "pointerdown", 703, "mouse", boxStart, { shiftKey: true });
                emitPointer(canvas, "pointermove", 703, "mouse", boxEnd, { shiftKey: true });
                emitPointer(canvas, "pointerup", 703, "mouse", boxEnd, { shiftKey: true });
                snapshot = mapSnapshot();
                expect(snapshot.secondaryNodeCount >= 1 && selectionEventCount >= 1,
                    "Shift-drag did not add boxed nodes as secondary selections: nodes=" +
                    snapshot.secondaryNodeCount + ", events=" + selectionEventCount +
                    ", box=" + atlasAttribute("data-docx-atlas-box-selection-count") +
                    ", gesture=" + atlasAttribute("data-docx-atlas-gesture") +
                    ", pointers=" + snapshot.activePointerCount);
                api.command(host, "clear-selection");
                await nextFrame();

                const pinchCenter = { x: canvas.clientWidth / 2, y: canvas.clientHeight / 2 };
                const scaleBeforePinch = mapSnapshot().scale;
                emitPointer(canvas, "pointerdown", 705, "touch", {
                    x: pinchCenter.x - 30,
                    y: pinchCenter.y,
                });
                emitPointer(canvas, "pointerdown", 706, "touch", {
                    x: pinchCenter.x + 30,
                    y: pinchCenter.y,
                });
                emitPointer(canvas, "pointermove", 706, "touch", {
                    x: pinchCenter.x + 100,
                    y: pinchCenter.y,
                });
                emitPointer(canvas, "pointerup", 706, "touch", {
                    x: pinchCenter.x + 100,
                    y: pinchCenter.y,
                });
                emitPointer(canvas, "pointerup", 705, "touch", {
                    x: pinchCenter.x - 30,
                    y: pinchCenter.y,
                });
                snapshot = mapSnapshot();
                expect(snapshot.scale > scaleBeforePinch && snapshot.activePointerCount === 0,
                    "two-pointer pinch did not zoom or clean up pointer state");

                const panBefore = { x: snapshot.panX, y: snapshot.panY };
                emitPointer(canvas, "pointerdown", 707, "touch", pinchCenter);
                emitPointer(canvas, "pointermove", 707, "touch", {
                    x: pinchCenter.x + 28,
                    y: pinchCenter.y + 18,
                });
                emitPointer(canvas, "pointerup", 707, "touch", {
                    x: pinchCenter.x + 28,
                    y: pinchCenter.y + 18,
                });
                snapshot = mapSnapshot();
                expect(snapshot.panX !== panBefore.x || snapshot.panY !== panBefore.y,
                    "one-pointer touch did not pan the map");
                expect(snapshot.activePointerCount === 0 && atlasAttribute("data-docx-atlas-gesture") === "idle",
                    "touch pan left captured pointer state behind");

                api.command(host, "fit");
                await nextFrame();
                snapshot = mapSnapshot();
                const dragSource = snapshot.sampleNodes.find(node => node.id === consumerFile);
                expect(dragSource, "Canvas snapshot lost the source file before grab-to-pan coverage");
                const dragStart = {
                    x: dragSource.x + dragSource.width / 2,
                    y: dragSource.y + dragSource.height / 2,
                };
                const panUndoBefore = snapshot.undoDepth;
                const mousePanBefore = { x: snapshot.panX, y: snapshot.panY };
                const panEnd = { x: dragStart.x + 48, y: dragStart.y + 32 };
                emitPointer(canvas, "pointerdown", 708, "mouse", dragStart);
                emitPointer(canvas, "pointermove", 708, "mouse", panEnd);
                emitPointer(canvas, "pointerup", 708, "mouse", panEnd);
                snapshot = mapSnapshot();
                expect((snapshot.panX !== mousePanBefore.x || snapshot.panY !== mousePanBefore.y) &&
                    snapshot.undoDepth === panUndoBefore,
                    "ordinary grab over a semantic node did not pan without editing layout");

                api.command(host, "fit");
                await nextFrame();
                snapshot = mapSnapshot();
                const layoutDragSource = snapshot.sampleNodes.find(node => node.id === consumerFile);
                const layoutDragTarget = snapshot.sampleNodes.find(node => node.id === targetFile);
                expect(layoutDragSource && layoutDragTarget,
                    "Canvas snapshot lost the sibling files before explicit layout drag coverage");
                const layoutDragStart = {
                    x: layoutDragSource.x + layoutDragSource.width / 2,
                    y: layoutDragSource.y + layoutDragSource.height / 2,
                };
                const dragEnd = {
                    x: layoutDragTarget.x + layoutDragTarget.width / 2,
                    y: layoutDragTarget.y + layoutDragTarget.height / 2,
                };
                const undoBefore = snapshot.undoDepth;
                emitPointer(canvas, "pointerdown", 709, "mouse", layoutDragStart, { altKey: true });
                emitPointer(canvas, "pointermove", 709, "mouse", dragEnd, { altKey: true });
                emitPointer(canvas, "pointerup", 709, "mouse", dragEnd, { altKey: true });
                snapshot = mapSnapshot();
                expect(snapshot.lastCollisionCount > 0 && snapshot.undoDepth === undoBefore + 1,
                    "post-drag sibling collision resolution did not create exactly one undo entry");
                api.command(host, "undo");
                expect(mapSnapshot().undoDepth === undoBefore, "one Undo did not restore the pre-drag layout");

                api.command(host, "fit");
                await nextFrame();
                snapshot = mapSnapshot();
                const keyboardTarget = snapshot.sampleNodes.find(node => node.id === consumerFile);
                expect(keyboardTarget, "Canvas snapshot lost the keyboard interaction target");
                emitPointer(canvas, "pointermove", 711, "mouse", {
                    x: keyboardTarget.x + keyboardTarget.width / 2,
                    y: keyboardTarget.y + keyboardTarget.height / 2,
                }, { buttons: 0 });
                const eventsBeforeSpace = selectionEventCount;
                canvas.dispatchEvent(new KeyboardEvent("keydown", {
                    key: " ",
                    code: "Space",
                    bubbles: true,
                    cancelable: true,
                }));
                const keyboardSnapshot = mapSnapshot();
                expect(selectionEventCount === eventsBeforeSpace + 1 && keyboardSnapshot.secondaryNodeCount === 1,
                    "Space did not toggle the focused Canvas hit: before=" + eventsBeforeSpace +
                    ", after=" + selectionEventCount + ", secondary=" + keyboardSnapshot.secondaryNodeCount +
                    ", preview=" + atlasAttribute("data-docx-atlas-preview-node-id") +
                    ", primary=" + keyboardSnapshot.selectedNodeId);
                api.command(host, "clear-secondary");
                host.removeEventListener("docx-atlas-selection", recordSelection);
                trace.push("canvas-interactions");
            }

            async function selectFixtureProject() {
                await openScope("build");
                requiredElement('[data-srcx-filter-build="fixture"]', scopePanel("build")).click();
                await waitUntil(() =>
                    attribute("data-docx-selected-build-id") === fixtureBuild &&
                    attribute("data-docx-selected-project-id") === "" &&
                    scopeIsOpen("project") &&
                    atlasAttribute("data-docx-atlas-state") === "ready",
                    "fixture build selection");
                await waitForCanonicalPaint();
                await nextFrame();
                verifyVirtualScope("project");
                trace.push("fixture-build");

                requiredElement('[data-srcx-filter-project=":app"]', scopePanel("project")).click();
                await waitUntil(() =>
                    attribute("data-docx-selected-project-id") === appProject &&
                    scopeIsOpen("source-set") &&
                    atlasAttribute("data-docx-atlas-state") === "ready" &&
                    numericAtlasAttribute("data-docx-atlas-node-count") === 4,
                    "fixture app project selection");
                await waitForCanonicalPaint();
                await nextFrame();
                expectValue("data-docx-selected-project-content", ":app|symbols=2|relationships=1|findings=1");
                verifyVirtualScope("source-set");
                trace.push("selected-app");
            }

            async function verifyHistoryToolbar() {
                const historyButton = selector => requiredElement(selector, atlasSurface());
                const backButton = historyButton("[data-srcx-history-back]");
                const forwardButton = historyButton("[data-srcx-history-forward]");
                expect(!backButton.disabled,
                    "project selection did not create an undoable history entry");
                backButton.click();
                expect(!forwardButton.disabled, "history back did not enable its matching forward control");
                trace.push("history-back");
                forwardButton.click();
                expect(!backButton.disabled, "history forward did not restore its matching back control");
                trace.push("history-forward");

                requiredElement('[data-srcx-graph-action="zoom-in"]', atlasSurface()).click();
                await waitUntil(() => atlasAttribute("data-docx-atlas-viewport-mode") === "manual", "history home setup");
                historyButton("[data-srcx-history-home]").click();
                await waitUntil(() => atlasAttribute("data-docx-atlas-viewport-mode") === "fit", "history home");
                expect(requiredElement("[data-srcx-history-breadcrumb]", atlasSurface()).children.length >= 1,
                    "history home has no workspace breadcrumb");
                trace.push("history-home");
            }

            async function selectMainSourceSet() {
                await openScope("source-set");
                const main = requiredElement('[data-srcx-filter-source-set="main"]', scopePanel("source-set"));
                expect(main.getAttribute("data-srcx-source-set-id-count") === "1",
                    "selected project main source grouping has the wrong membership");
                main.click();
                await waitUntil(() => {
                    const currentMain = requiredElement(
                        '[data-srcx-filter-source-set="main"]',
                        scopePanel("source-set"),
                    );
                    return attribute("data-docx-graph-source-set") === "source-set:fixture:main" &&
                        currentMain.getAttribute("aria-pressed") === "true";
                }, "main source-set filter");
                await waitUntil(() =>
                    atlasAttribute("data-docx-atlas-state") === "ready" &&
                    numericAtlasAttribute("data-docx-atlas-node-count") === 5 &&
                    numericAtlasAttribute("data-docx-atlas-edge-count") === 0,
                    "main source-set semantic slice");
                scopeTrigger("source-set").click();
                await waitUntil(() => !scopeIsOpen("source-set"), "main source sheet close");
                await nextFrame();
                trace.push("source-set-main");
            }

            async function verifyFileAndRelationshipEvidence() {
                const host = atlasHost();
                const api = window.docxAtlasMap;
                const packageNode = JSON.parse(api.snapshot(host)).sampleNodes.find(node =>
                    node.container && node.id.startsWith("source-package:"));
                expect(packageNode, "collapsed source-set slice is missing its expandable package");
                api.command(host, "open-node:" + packageNode.id);
                await waitUntil(() =>
                    host.getAttribute("data-docx-atlas-open-node-id") === packageNode.id,
                    "package open command acknowledgment");
                await waitUntil(() =>
                    numericAtlasAttribute("data-docx-atlas-node-count") === 7 &&
                    numericAtlasAttribute("data-docx-atlas-edge-count") === 1,
                    "package semantic expansion");
                trace.push("package-expanded");
                api.command(host, "select-node:" + consumerFile);
                await waitUntil(() =>
                    host.getAttribute("data-docx-atlas-selected-node-id") === consumerFile &&
                    !host.hasAttribute("data-docx-atlas-selected-edge-id"),
                    "Canvas file selection");
                trace.push("file-source-evidence");
                await waitUntil(() => {
                    const surface = atlasSurface();
                    const detail = surface && surface.querySelector("[data-srcx-detail]");
                    const sourceState = surface && surface.getAttribute("data-docx-atlas-source-state");
                    const sourceTargetMatches = sourceState !== "ready" ||
                        (surface.getAttribute("data-docx-atlas-source-project-id") === appProject &&
                            surface.getAttribute("data-docx-atlas-source-file-id") === consumerFile);
                    return surface && detail && !detail.hidden && detail.classList.contains("is-open") &&
                        (sourceState === "ready" || sourceState === "idle") && sourceTargetMatches &&
                        detail.textContent.includes("class Consumer { val target = Target() }");
                }, "lazy file source evidence");
                const fileDetail = requiredElement("[data-srcx-detail]", atlasSurface());
                expect(requiredElement(".srcx-dashboard__architecture-source-viewer", fileDetail)
                    .querySelectorAll("[data-srcx-source-line]").length >= 3,
                    "file detail does not expose complete source lines");
                const sourceState = atlasSurface().getAttribute("data-docx-atlas-source-state");
                expect(sourceState === "ready" || sourceState === "idle",
                    "file source body is neither loaded lazily nor available inline: " + sourceState);

                api.command(host, "select-edge:" + constructorRelationship);
                await waitUntil(() =>
                    !host.hasAttribute("data-docx-atlas-selected-node-id") &&
                    host.getAttribute("data-docx-atlas-selected-edge-id") === constructorRelationship,
                    "Canvas relationship selection");
                trace.push("relationship-source-evidence");
                await waitUntil(() => {
                    const detail = requiredElement("[data-srcx-detail]", atlasSurface());
                    const sourceState = atlasSurface().getAttribute("data-docx-atlas-source-state");
                    return (sourceState === "ready" || sourceState === "idle") &&
                        detail.querySelector('[data-srcx-source-line].is-active-relationship[aria-current="true"]');
                }, "lazy relationship source evidence");
                const relationshipDetail = requiredElement("[data-srcx-detail]", atlasSurface());
                const activeLine = requiredElement(
                    '[data-srcx-source-line].is-active-relationship[aria-current="true"]',
                    relationshipDetail,
                );
                expect(activeLine.textContent.includes("Target()"),
                    "relationship evidence did not highlight the exact source occurrence");
                expect(requiredElement(".srcx-dashboard__architecture-occurrence-status", relationshipDetail)
                    .textContent.includes("Record 1 of 1"),
                    "relationship evidence does not expose its occurrence count");
            }

            async function verifyGlobalSearch() {
                const surface = atlasSurface();
                const canvas = requiredElement(canvasSelector, atlasHost());
                const input = requiredElement("[data-srcx-global-search-input]", atlasHost());
                const selectedBuilds = surface.getAttribute("data-docx-atlas-selected-build-ids") || "";
                const selectedProjects = surface.getAttribute("data-docx-atlas-selected-project-ids") || "";

                canvas.dispatchEvent(new KeyboardEvent("keydown", {
                    key: "/",
                    bubbles: true,
                    composed: true,
                }));
                await waitUntil(() =>
                    deepActiveElement() === input &&
                    requiredElement("[data-srcx-global-search]", atlasHost())
                        .getAttribute("data-srcx-global-search-open") === "true",
                    "global search keyboard shortcut");

                const currentInput = requiredElement("[data-srcx-global-search-input]", atlasHost());
                currentInput.value = "class:fixture";
                currentInput.dispatchEvent(new Event("input", { bubbles: true, composed: true }));
                await waitUntil(() => {
                    const currentHost = atlasHost();
                    const results = requiredElement("[data-srcx-global-search-results]", currentHost);
                    return requiredElement("[data-srcx-global-search]", currentHost)
                        .getAttribute("data-srcx-global-search-state") === "ready" &&
                        Number(results.getAttribute("data-srcx-global-search-result-count")) >= 2;
                }, "typed global search results", asyncSettlementTimeoutMillis);

                const results = requiredElement("[data-srcx-global-search-results]", atlasHost());
                expect(Number(results.getAttribute("data-srcx-global-search-mounted-count")) <= 7,
                    "global search mounted more than seven virtual rows");
                const consumer = requiredElement(
                    '[data-srcx-global-search-result="CLASS:symbol:fixture:consumer"]',
                    results);
                expect(consumer.textContent.includes("Consumer"), "global search omitted the class name");
                expect(consumer.textContent.includes(":app"), "global search omitted the ownership breadcrumb");
                expect(consumer.querySelector('[data-srcx-search-badge="relationships"]'),
                    "global search omitted the relationship badge");
                expect(consumer.querySelector('[data-srcx-search-badge="problems"]'),
                    "global search omitted the problem badge");

                const navigableInput = requiredElement("[data-srcx-global-search-input]", atlasHost());
                navigableInput.dispatchEvent(new KeyboardEvent("keydown", {
                    key: "ArrowDown",
                    bubbles: true,
                    composed: true,
                }));
                await waitUntil(() => {
                    const active = deepActiveElement();
                    return active && active.matches(
                        '[data-srcx-global-search-target="CLASS:symbol:fixture:target"]');
                }, "global search keyboard navigation");
                const activeInput = requiredElement("[data-srcx-global-search-input]", atlasHost());
                activeInput.focus();
                activeInput.dispatchEvent(new KeyboardEvent("keydown", {
                    key: "Enter",
                    bubbles: true,
                    composed: true,
                }));
                await waitUntil(() =>
                    attribute("data-docx-selected-node-id") === "symbol:fixture:target" &&
                    requiredElement("[data-srcx-global-search]", atlasHost())
                        .getAttribute("data-srcx-global-search-open") === "false",
                    "global search fly-to", asyncSettlementTimeoutMillis);
                expect((atlasSurface().getAttribute("data-docx-atlas-selected-build-ids") || "") === selectedBuilds,
                    "global search fly-to changed build scope");
                expect((atlasSurface().getAttribute("data-docx-atlas-selected-project-ids") || "") === selectedProjects,
                    "global search fly-to changed project scope");
                trace.push("global-search");
            }

            function restoreProperty(target, name, descriptor) {
                if (descriptor) Object.defineProperty(target, name, descriptor);
                else delete target[name];
            }

            function installFullscreenHarness(graph) {
                let fullscreenElement = null;
                let shadowFullscreenElement = null;
                const graphRoot = graph.getRootNode();
                const documentFullscreenDescriptor = Object.getOwnPropertyDescriptor(document, "fullscreenElement");
                const shadowFullscreenDescriptor = graphRoot === document ? null :
                    Object.getOwnPropertyDescriptor(graphRoot, "fullscreenElement");
                const exitFullscreenDescriptor = Object.getOwnPropertyDescriptor(document, "exitFullscreen");
                Object.defineProperty(document, "fullscreenElement", {
                    configurable: true,
                    get: () => fullscreenElement,
                });
                if (graphRoot !== document) {
                    Object.defineProperty(graphRoot, "fullscreenElement", {
                        configurable: true,
                        get: () => shadowFullscreenElement,
                    });
                }
                Object.defineProperty(graph, "requestFullscreen", {
                    configurable: true,
                    value: () => {
                        fullscreenElement = graphRoot.host || graph;
                        shadowFullscreenElement = graph;
                        graph.classList.add("is-fullscreen-fallback");
                        graph.dispatchEvent(new Event("fullscreenchange", { bubbles: true, composed: true }));
                        return Promise.resolve();
                    },
                });
                Object.defineProperty(document, "exitFullscreen", {
                    configurable: true,
                    value: () => {
                        fullscreenElement = null;
                        shadowFullscreenElement = null;
                        graph.classList.remove("is-fullscreen-fallback");
                        graph.dispatchEvent(new Event("fullscreenchange", { bubbles: true, composed: true }));
                        restoreProperty(document, "fullscreenElement", documentFullscreenDescriptor);
                        if (graphRoot !== document) {
                            restoreProperty(graphRoot, "fullscreenElement", shadowFullscreenDescriptor);
                        }
                        restoreProperty(document, "exitFullscreen", exitFullscreenDescriptor);
                        return Promise.resolve();
                    },
                });
            }

            function verifyCanvasFillsViewport() {
                const graph = requiredElement("[data-srcx-architecture-graph]", atlasSurface());
                const host = atlasHost();
                const canvas = requiredElement(canvasSelector, host);
                const graphRect = graph.getBoundingClientRect();
                const hostRect = host.getBoundingClientRect();
                const canvasRect = canvas.getBoundingClientRect();
                const tolerance = 3;
                expect(Math.abs(graphRect.left) <= tolerance && Math.abs(graphRect.top) <= tolerance,
                    "fullscreen Atlas is not anchored at the viewport origin");
                expect(Math.abs(graphRect.width - window.innerWidth) <= tolerance &&
                    Math.abs(graphRect.height - window.innerHeight) <= tolerance,
                    "fullscreen Atlas does not use the complete viewport");
                expect(Math.abs(hostRect.left - graphRect.left) <= tolerance &&
                    Math.abs(hostRect.top - graphRect.top) <= tolerance &&
                    Math.abs(hostRect.width - graphRect.width) <= tolerance &&
                    Math.abs(hostRect.height - graphRect.height) <= tolerance,
                    "fullscreen map viewport leaves unused chart space");
                expect(Math.abs(canvasRect.width - hostRect.width) <= tolerance &&
                    Math.abs(canvasRect.height - hostRect.height) <= tolerance,
                    "Canvas does not fill the fullscreen map viewport");
            }

            async function verifyFullscreenHideShow() {
                const surface = atlasSurface();
                const graph = requiredElement("[data-srcx-architecture-graph]", surface);
                const button = requiredElement("[data-srcx-graph-fullscreen]", graph);
                const toggle = requiredElement("[data-srcx-chart-controls-toggle]", graph);
                const controls = requiredElement("[data-srcx-chart-controls]", graph);
                const toolbar = requiredElement("[data-srcx-map-toolbar]", graph);
                const history = requiredElement("[data-srcx-history-toolbar]", graph);
                const detail = requiredElement("[data-srcx-detail]", graph);
                installFullscreenHarness(graph);
                button.click();
                await waitUntil(() => graph.getAttribute("data-srcx-fullscreen") === "true", "fullscreen enter");
                await nextFrame();
                verifyCanvasFillsViewport();
                expect(button.getAttribute("aria-pressed") === "true", "fullscreen button state is stale");
                trace.push("fullscreen-on");

                toggle.click();
                await waitUntil(() =>
                    graph.getAttribute("data-srcx-chart-controls-state") === "collapsed" &&
                    controls.getAttribute("data-srcx-controls-state") === "collapsed",
                    "HUD collapse");
                await nextFrame();
                expect(getComputedStyle(requiredElement("[data-srcx-control-bar]", controls)).display !== "none",
                    "collapsed HUD lost its persistent expand control");
                expect(toggle.getAttribute("aria-expanded") === "false" &&
                    toggle.getAttribute("aria-label") === "Expand chart controls",
                    "collapsed HUD toggle does not explain how to restore controls");
                const visibleToolbarChrome = Array.from(toolbar.children).filter(child =>
                    child !== toggle && !child.contains(toggle) && getComputedStyle(child).display !== "none");
                expect(getComputedStyle(toolbar).display !== "none" && visibleToolbarChrome.length === 0 &&
                    getComputedStyle(history).display === "none" &&
                    getComputedStyle(detail).display === "none",
                    "collapsed fullscreen HUD still obscures the map");
                verifyCanvasFillsViewport();
                trace.push("chrome-collapsed");

                toggle.click();
                await waitUntil(() =>
                    graph.getAttribute("data-srcx-chart-controls-state") === "expanded" &&
                    controls.getAttribute("data-srcx-controls-state") === "expanded",
                    "HUD expand");
                await nextFrame();
                expect(getComputedStyle(toolbar).display !== "none" && getComputedStyle(history).display !== "none",
                    "expanding the HUD did not restore map controls");
                expect(getComputedStyle(detail).display !== "none",
                    "expanding the HUD did not restore selected source evidence");
                verifyCanvasFillsViewport();
                trace.push("chrome-expanded");

                button.click();
                await waitUntil(() => graph.getAttribute("data-srcx-fullscreen") === "false", "fullscreen exit");
                expect(button.getAttribute("aria-pressed") === "false", "fullscreen exit state is stale");
                trace.push("fullscreen-off");
            }

            function verifyWorkspaceReady() {
                expectValue("data-docx-state", "ready");
                expectValue("data-docx-layout-mode", scenario === "narrow" ? "compact" : "wide");
                expectValue("data-docx-catalog-content", "Fixture Workspace");
                expectValue("data-docx-selected-build-id", "");
                expectValue("data-docx-selected-project-id", "");
                expectValue("data-docx-selected-project-content", "All workspace|nodes=3|relationships=1|findings=1");
                expectValue("data-docx-horizontal-overflow", "false");
                expect(typeof window.docxSmokeCommand === "function", "docxSmokeCommand is unavailable");
            }

            async function run() {
                await waitUntil(() =>
                    attribute("data-docx-state") === "ready" &&
                    typeof window.docxSmokeCommand === "function" &&
                    attribute("data-docx-command-revision") !== "" &&
                    attribute("data-docx-layout-mode") !== "" &&
                    atlasSurface() &&
                    atlasSurface().getAttribute("data-docx-dashboard-state") === "ready" &&
                    atlasAttribute("data-docx-atlas-state") === "ready" &&
                    atlasAttribute("data-docx-atlas-renderer") === "d3" &&
                    numericAtlasAttribute("data-docx-atlas-node-count") === 3 &&
                    numericAtlasAttribute("data-docx-atlas-edge-count") === 1 &&
                    numericAttribute("data-docx-graph-total-nodes") === 3 &&
                    attribute("data-docx-graph-frame-settled") === "true" &&
                    atlasAttribute("data-docx-atlas-session-request-state") === "settled" &&
                    numericAttribute("data-docx-graph-viewport-height") >= (scenario === "narrow" ? 220 : 260),
                    "settled All-workspace detailed Atlas ready state");
                await waitForCanonicalPaint();
                verifyWorkspaceReady();
                trace.push("boot");
                verifyCanonicalStylesheets();
                trace.push("canonical-css-loaded");
                trace.push("workspace-atlas");
                verifyEmbeddedReportAndHud();
                trace.push("embedded-report-hud");
                verifyAllWorkspaceFiltersEnabled();
                trace.push("all-workspace-filters");
                await verifyScopeSheets();
                trace.push("scope-sheets");
                verifyDetailedOverview(3, 1);
                trace.push("detailed-overview-stable");
                await verifyDetailedOverviewInteractions();
                trace.push("detailed-overview-interactions");
                await verifyDetailedOverviewLenses();
                await verifyLayerSheetAndCombinations();
                await selectFixtureProject();
                verifyCanvas(4, 0);
                await verifyHistoryToolbar();
                await selectMainSourceSet();
                verifyCanvas(5, 0);
                await verifyFileAndRelationshipEvidence();
                await verifyFullscreenHideShow();
                await verifyCanvasInteractions();
                await verifyGlobalSearch();
                verifyPageAndHudDoNotScrollHorizontally();
                expectValue("data-docx-horizontal-overflow", "false");
                trace.push("no-horizontal-scroll");
                publishFinalReadyGeometry();
                root.setAttribute("data-docx-smoke-trace", trace.join("|"));
                root.setAttribute("data-docx-smoke-result", "passed");
            }

            async function capture() {
                await waitUntil(() =>
                    attribute("data-docx-state") === "ready" &&
                    atlasSurface() &&
                    atlasSurface().getAttribute("data-docx-dashboard-state") === "ready" &&
                    atlasAttribute("data-docx-atlas-renderer") === "d3" &&
                    atlasAttribute("data-docx-atlas-state") === "ready",
                    "capture-ready detailed Atlas state");
                await waitForCanonicalPaint();
                if (captureMode === "evidence") {
                    await selectFixtureProject();
                    await selectMainSourceSet();
                    await verifyFileAndRelationshipEvidence();
                } else {
                    verifyDetailedOverview(3, 1);
                }
                publishFinalReadyGeometry();
                root.setAttribute("data-docx-smoke-trace", "capture-" + captureMode);
                root.setAttribute("data-docx-smoke-result", "passed");
            }

            (captureOnly ? capture() : run())
                .catch(error => {
                    root.setAttribute("data-docx-smoke-trace", trace.join("|"));
                    root.setAttribute("data-docx-smoke-result", "failed");
                    root.setAttribute("data-docx-smoke-error", String(error && error.message ? error.message : error));
                })
                .finally(() => window.clearInterval(keepAlive));
        })();
        </script>
        """.trimIndent()
}
