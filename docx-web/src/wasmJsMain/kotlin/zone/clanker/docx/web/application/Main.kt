package zone.clanker.docx.web.application

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import zone.clanker.docx.web.probe.DocxInteractionProbe
import zone.clanker.docx.web.probe.DocxSmokeCommandSource
import zone.clanker.docx.web.probe.DocxViewerProbe
import zone.clanker.docx.web.site.WorkspaceSiteLoader
import zone.clanker.docx.web.site.WorkspaceSiteTextSource
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsString
import kotlin.js.Promise

private val workspaceSiteLoader =
    WorkspaceSiteLoader(
        WorkspaceSiteTextSource(::fetchReportText),
    )

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val root = document.getElementById("docx-app") ?: error("DOCX viewer root is missing")
    val smokeMode = isDocxSmokeMode()
    val commandSource = if (smokeMode) DocxSmokeCommandSource(root.id) else null
    installFatalErrorHandlers(root.id)
    if (smokeMode) installDocxSmokeCommand(root.id)
    publishViewerStatus(root.id, "loading-catalog")
    root.innerHTML = ""
    val onProbeChanged: (DocxViewerProbe) -> Unit = { probe ->
        if (probe.state == "ready") {
            publishReadyProbe(
                rootId = root.id,
                contentHeight = probe.contentHeight,
                catalogContent = requireNotNull(probe.catalogContent),
                selectedProjectContent = requireNotNull(probe.selectedProjectContent),
            )
        } else {
            publishViewerStatus(root.id, probe.state)
        }
    }
    val onInteractionChanged: (DocxInteractionProbe?) -> Unit = { probe ->
        if (smokeMode) {
            if (probe == null) clearInteractionProbe(root.id) else publishInteractionProbe(root.id, probe)
        }
    }
    ComposeViewport(root) {
        DocxApplication(
            loader = workspaceSiteLoader,
            commandSource = commandSource,
            onProbeChanged = onProbeChanged,
            onInteractionChanged = onInteractionChanged,
        )
    }
}

private fun isDocxSmokeMode(): Boolean =
    window.location.search
        .removePrefix("?")
        .split('&')
        .any { parameter -> parameter == "docx-smoke=1" }

@OptIn(ExperimentalWasmJsInterop::class)
@Suppress("UnusedParameter")
private fun installDocxSmokeCommand(rootId: String): Unit =
    js(
        """{
            const root = document.getElementById(rootId);
            if (!root) return;
            window.docxSmokeCommand = (name, arg = "") => {
                root.__docxCommandRequestRevision = (root.__docxCommandRequestRevision || 0) + 1;
                root.setAttribute("data-docx-command-request-name", String(name || ""));
                root.setAttribute("data-docx-command-request-argument", String(arg || ""));
                root.setAttribute(
                    "data-docx-command-request-revision",
                    String(root.__docxCommandRequestRevision)
                );
                return root.__docxCommandRequestRevision;
            };
        }""",
    )

private fun clearInteractionProbe(rootId: String) {
    val root = document.getElementById(rootId) ?: return
    interactionAttributes.forEach(root::removeAttribute)
}

private fun publishInteractionProbe(
    rootId: String,
    probe: DocxInteractionProbe,
) {
    val root = document.getElementById(rootId) ?: return
    root.setAttribute("data-docx-layout-mode", probe.workspace.layoutMode)
    root.setAttribute("data-docx-filter-query", probe.workspace.filterQuery)
    root.setAttribute(
        "data-docx-visible-project-ids",
        probe.workspace.visibleProjectIds.joinToString("|"),
    )
    root.setAttribute(
        "data-docx-visible-project-count",
        probe.workspace.visibleProjectIds.size
            .toString(),
    )
    root.setAttribute("data-docx-selected-build-id", probe.workspace.selectedBuildId)
    root.setAttribute("data-docx-selected-project-id", probe.workspace.selectedProjectId)
    root.setAttribute("data-docx-selected-build-ids", probe.workspace.selectedBuildIds.joinToString(","))
    root.setAttribute("data-docx-selected-project-ids", probe.workspace.selectedProjectIds.joinToString(","))
    root.setAttribute("data-docx-graph-scope", probe.graphScope.scope)
    root.setAttribute("data-docx-graph-lens", probe.graphScope.lens)
    root.setAttribute("data-docx-graph-source-set", probe.graphScope.sourceSet)
    root.setAttribute("data-docx-graph-kind-filter", probe.graphScope.kindFilter)
    root.setAttribute("data-docx-graph-search-query", probe.graphScope.searchQuery)
    root.setAttribute("data-docx-graph-search-targets", probe.graphScope.filters.searchTargets)
    root.setAttribute(
        "data-docx-graph-relationship-count-more-than",
        probe.graphScope.filters.relationshipCountMoreThan,
    )
    root.setAttribute("data-docx-graph-relationship-direction", probe.graphScope.filters.relationshipDirection)
    root.setAttribute("data-docx-graph-scale", probe.graphViewport.scale.toString())
    root.setAttribute("data-docx-graph-pan-x", probe.graphViewport.panX.toString())
    root.setAttribute("data-docx-graph-pan-y", probe.graphViewport.panY.toString())
    root.setAttribute("data-docx-graph-mode", probe.graphViewport.mode)
    root.setAttribute("data-docx-graph-total-nodes", probe.graphFrame.totalNodes.toString())
    root.setAttribute(
        "data-docx-graph-visible-nodes",
        probe.graphFrame.visibleNodeIds.size
            .toString(),
    )
    root.setAttribute(
        "data-docx-graph-visible-node-ids",
        probe.graphFrame.visibleNodeIds.joinToString("|"),
    )
    root.setAttribute("data-docx-graph-page", probe.graphFrame.page.toString())
    root.setAttribute("data-docx-graph-page-count", probe.graphFrame.pageCount.toString())
    root.setAttribute("data-docx-graph-frame-settled", probe.graphFrame.settled.toString())
    root.setAttribute("data-docx-graph-viewport-height", probe.graphViewport.height.toString())
    root.setAttribute("data-docx-selected-node-id", probe.selection.selectedNodeId)
    root.setAttribute("data-docx-selected-edge-id", probe.selection.selectedEdgeId)
    root.setAttribute("data-docx-inspector-content", probe.selection.inspectorContent)
    root.setAttribute("data-docx-focus-map", probe.workspace.focusMap.toString())
    root.setAttribute("data-docx-horizontal-overflow", hasHorizontalOverflow(rootId).toString())
    if (probe.command.error == null) {
        root.removeAttribute("data-docx-command-error")
    } else {
        root.setAttribute("data-docx-command-error", probe.command.error)
    }
    root.setAttribute("data-docx-command-revision", probe.command.revision.toString())
}

@OptIn(ExperimentalWasmJsInterop::class)
@Suppress("UnusedParameter")
private fun hasHorizontalOverflow(rootId: String): Boolean =
    js(
        """{
            const root = document.getElementById(rootId);
            return root ? root.scrollWidth > root.clientWidth + 1 : false;
        }""",
    )

@OptIn(ExperimentalWasmJsInterop::class)
@Suppress("UnusedParameter")
private fun publishViewerStatus(
    rootId: String,
    state: String,
): Unit =
    js(
        """{
            const root = document.getElementById(rootId);
            if (!root) return;
            root.setAttribute("data-docx-state", state);
            [
                "data-docx-viewport-width",
                "data-docx-viewport-height",
                "data-docx-app-width",
                "data-docx-app-height",
                "data-docx-canvas-height",
                "data-docx-content-height",
                "data-docx-catalog-content",
                "data-docx-selected-project-content"
            ].forEach((name) => root.removeAttribute(name));
        }""",
    )

@OptIn(ExperimentalWasmJsInterop::class)
@Suppress("UnusedParameter")
private fun publishReadyProbe(
    rootId: String,
    contentHeight: Int,
    catalogContent: String,
    selectedProjectContent: String,
): Unit =
    js(
        """{
            const root = document.getElementById(rootId);
            if (!root) return;
            root.setAttribute("data-docx-state", "loading-layout");
            const canvasHeights = [];
            const visit = (node) => {
                if (!node) return;
                if (node instanceof HTMLCanvasElement && node.matches("[data-docx-atlas-canvas]")) {
                    canvasHeights.push(node.getBoundingClientRect().height);
                }
                if (node.querySelectorAll) {
                    node.querySelectorAll("[data-docx-atlas-canvas]").forEach((canvas) => {
                        canvasHeights.push(canvas.getBoundingClientRect().height);
                    });
                    node.querySelectorAll("*").forEach((element) => {
                        if (element.shadowRoot) visit(element.shadowRoot);
                    });
                }
                if (node.shadowRoot) visit(node.shadowRoot);
            };
            visit(root);
            const appBounds = root.getBoundingClientRect();
            root.setAttribute("data-docx-viewport-width", String(window.innerWidth));
            root.setAttribute("data-docx-viewport-height", String(window.innerHeight));
            root.setAttribute("data-docx-app-width", String(appBounds.width));
            root.setAttribute("data-docx-app-height", String(appBounds.height));
            root.setAttribute(
                "data-docx-canvas-height",
                String(canvasHeights.reduce((height, candidate) => Math.max(height, candidate), 0))
            );
            root.setAttribute("data-docx-content-height", String(contentHeight));
            root.setAttribute("data-docx-catalog-content", catalogContent);
            root.setAttribute("data-docx-selected-project-content", selectedProjectContent);
            root.setAttribute("data-docx-state", "ready");
        }""",
    )

@OptIn(ExperimentalWasmJsInterop::class)
private suspend fun fetchReportText(path: String): String {
    val content: JsString = fetchText(path).await()
    return content.toString()
}

@OptIn(ExperimentalWasmJsInterop::class)
@Suppress("UnusedParameter")
private fun fetchText(path: String): Promise<JsString> =
    js(
        """window.fetch(path).then((response) => {
            if (!response.ok) throw new Error("Unable to load " + path + " (" + response.status + ")");
            return response.text();
        })""",
    )

@OptIn(ExperimentalWasmJsInterop::class)
@Suppress("UnusedParameter")
private fun installFatalErrorHandlers(rootId: String): Unit =
    js(
        """{
            const describe = (error) => {
                if (error && typeof error.message === "string") return error.message;
                if (typeof error === "string") return error;
                try { return String(error); } catch (_) { return "Unknown browser runtime error"; }
            };
            const isResizeObserverLoopWarning = (error) => {
                const message = describe(error).trim().replace(/\.$/, "");
                return message === "ResizeObserver loop limit exceeded" ||
                    message === "ResizeObserver loop completed with undelivered notifications";
            };
            const show = (error) => {
                if (isResizeObserverLoopWarning(error)) return;
                const root = document.getElementById(rootId);
                if (!root || root.getAttribute("data-docx-state") === "fatal") return;
                root.setAttribute("data-docx-state", "fatal");
                [
                    "data-docx-viewport-width",
                    "data-docx-viewport-height",
                    "data-docx-app-width",
                    "data-docx-app-height",
                    "data-docx-canvas-height",
                    "data-docx-content-height",
                    "data-docx-catalog-content",
                    "data-docx-selected-project-content"
                ].forEach((name) => root.removeAttribute(name));
                const panel = document.createElement("p");
                panel.className = "docx-boot";
                panel.textContent = "DOCX VIEWER FAILED — " + describe(error);
                root.replaceChildren(panel);
            };
            window.addEventListener("error", (event) => show(event.error || event.message));
            window.addEventListener("unhandledrejection", (event) => show(event.reason));
        }""",
    )

private val interactionAttributes =
    listOf(
        "data-docx-layout-mode",
        "data-docx-filter-query",
        "data-docx-visible-project-ids",
        "data-docx-visible-project-count",
        "data-docx-selected-build-id",
        "data-docx-selected-project-id",
        "data-docx-selected-build-ids",
        "data-docx-selected-project-ids",
        "data-docx-graph-scope",
        "data-docx-graph-lens",
        "data-docx-graph-source-set",
        "data-docx-graph-kind-filter",
        "data-docx-graph-search-query",
        "data-docx-graph-search-targets",
        "data-docx-graph-relationship-count-more-than",
        "data-docx-graph-relationship-direction",
        "data-docx-graph-scale",
        "data-docx-graph-pan-x",
        "data-docx-graph-pan-y",
        "data-docx-graph-mode",
        "data-docx-graph-total-nodes",
        "data-docx-graph-visible-nodes",
        "data-docx-graph-visible-node-ids",
        "data-docx-graph-page",
        "data-docx-graph-page-count",
        "data-docx-graph-frame-settled",
        "data-docx-graph-viewport-height",
        "data-docx-selected-node-id",
        "data-docx-selected-edge-id",
        "data-docx-inspector-content",
        "data-docx-focus-map",
        "data-docx-horizontal-overflow",
        "data-docx-command-error",
        "data-docx-command-revision",
    )
