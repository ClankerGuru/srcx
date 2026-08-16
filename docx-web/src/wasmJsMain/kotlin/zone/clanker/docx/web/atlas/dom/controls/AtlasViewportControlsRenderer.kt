package zone.clanker.docx.web.atlas.dom.controls

import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLDivElement
import org.w3c.dom.HTMLElement
import zone.clanker.docx.web.atlas.AtlasController
import zone.clanker.docx.web.atlas.dom.requiredButton
import zone.clanker.docx.web.atlas.dom.requiredElement
import zone.clanker.docx.web.atlas.dom.requiredHtmlElement
import kotlin.js.ExperimentalWasmJsInterop

internal class AtlasViewportControlsRenderer {
    fun render(
        root: HTMLDivElement,
        controller: AtlasController,
        onGraphCommand: (String) -> Unit,
    ) {
        viewportActions.forEach { action ->
            root.requiredButton("[data-srcx-graph-action=\"$action\"]").onclick = {
                onGraphCommand(action)
                null
            }
        }
        val graph = root.requiredHtmlElement("[data-srcx-architecture-graph]")
        val fullscreenButton = root.requiredButton("[data-srcx-graph-fullscreen]")
        val chromeToggle = root.requiredButton("[data-srcx-chart-controls-toggle]")
        installFullscreenRestoration(graph)
        installFullscreen(graph, fullscreenButton, chromeToggle)
        fullscreenButton.onclick = {
            toggleFullscreen(graph)
            null
        }
        chromeToggle.onclick = {
            controller.toggleFocusMap()
            updateAtlasChromeVisibility(root, controller.focusMap)
            null
        }
        updateAtlasChromeVisibility(root, controller.focusMap)
    }

    fun release(root: HTMLDivElement) {
        releaseFullscreen(root.requiredHtmlElement("[data-srcx-architecture-graph]"))
    }
}

internal fun updateAtlasChromeVisibility(
    root: HTMLDivElement,
    collapsed: Boolean,
) {
    val graph = root.requiredHtmlElement("[data-srcx-architecture-graph]")
    graph.setAttribute("data-srcx-fullscreen-chrome", if (collapsed) "collapsed" else "expanded")
    graph.setAttribute("data-srcx-chart-controls-state", if (collapsed) "collapsed" else "expanded")
    root.requiredHtmlElement("[data-srcx-chart-controls]").also { controls ->
        controls.hidden = false
        controls.setAttribute("data-srcx-controls-state", if (collapsed) "collapsed" else "expanded")
    }
    root.requiredButton("[data-srcx-chart-controls-toggle]").also { button ->
        button.hidden = false
        button.setAttribute("aria-expanded", (!collapsed).toString())
        val label = if (collapsed) "Expand chart controls" else "Hide chart controls"
        button.setAttribute("aria-label", label)
        button.title = label
        root.requiredElement("[data-srcx-chart-controls-toggle-icon]").textContent =
            if (collapsed) "▾" else "▴"
    }
}

@OptIn(ExperimentalWasmJsInterop::class)
@Suppress("UnusedParameter")
private fun installFullscreen(
    element: HTMLElement,
    button: HTMLButtonElement,
    chromeToggle: HTMLButtonElement,
): Unit =
    js(
        """{
            if (element.__docxFullscreenHandler) {
                element.__docxFullscreenButton = button;
                element.__docxFullscreenChromeToggle = chromeToggle;
                element.__docxFullscreenHandler();
                return;
            }
            element.__docxFullscreenButton = button;
            element.__docxFullscreenChromeToggle = chromeToggle;
            const sync = () => {
                const root = element.getRootNode();
                const active = document.fullscreenElement === element ||
                    (root && root.fullscreenElement === element) ||
                    element.matches(":fullscreen");
                const activeChanged = element.__docxFullscreenActive !== undefined &&
                    element.__docxFullscreenActive !== active;
                const leaving = !active && element.__docxFullscreenActive === true;
                element.__docxFullscreenActive = active;
                if (activeChanged) {
                    element.dispatchEvent(new CustomEvent("docx-atlas-fullscreen-transition", { bubbles: true }));
                }
                element.setAttribute("data-srcx-fullscreen", String(active));
                element.__docxFullscreenButton.setAttribute("aria-pressed", String(active));
                element.__docxFullscreenButton.setAttribute(
                    "aria-label",
                    active ? "Exit full screen" : "Enter full screen"
                );
                element.__docxFullscreenButton.title = active ? "Exit full screen" : "Full screen";
                element.__docxFullscreenChromeToggle.hidden = false;
                if (active) element.removeAttribute("data-srcx-fullscreen-error");
                if (leaving) element.__docxFullscreenRestore();
            };
            element.__docxFullscreenHandler = sync;
            document.addEventListener("fullscreenchange", sync);
            sync();
        }""",
    )

@OptIn(ExperimentalWasmJsInterop::class)
@Suppress("UnusedParameter")
private fun installFullscreenRestoration(element: HTMLElement): Unit =
    js(
        """{
            if (element.__docxFullscreenRestore) return;
            element.__docxFullscreenRestore = () => {
                const scrollContainer = element.__docxFullscreenScrollContainer;
                const returnX = Number(element.__docxFullscreenReturnX || 0);
                const returnY = Number(element.__docxFullscreenReturnY || 0);
                const returnScrollLeft = Number(element.__docxFullscreenReturnScrollLeft || 0);
                const returnScrollTop = Number(element.__docxFullscreenReturnScrollTop || 0);
                const returnGraphLeft = Number(element.__docxFullscreenReturnGraphLeft || 0);
                const returnGraphTop = Number(element.__docxFullscreenReturnGraphTop || 0);
                element.__docxFullscreenButton?.focus({ preventScroll: true });
                element.__docxFullscreenRestoreCancelled = false;
                element.setAttribute("data-srcx-fullscreen-restore-state", "running");
                element.setAttribute("data-srcx-fullscreen-restore-target-scroll-top", String(returnScrollTop));
                element.setAttribute("data-srcx-fullscreen-restore-target-graph-top", String(returnGraphTop));
                const targets = [scrollContainer, window].filter(Boolean);
                const events = ["pointerdown", "wheel", "touchstart"];
                const cancelRestoration = (event) => {
                    element.__docxFullscreenRestoreCancelled = true;
                    element.setAttribute("data-srcx-fullscreen-restore-state", "cancelled");
                    element.setAttribute("data-srcx-fullscreen-restore-cancel-event", event.type);
                };
                const releaseCancellation = () => targets.forEach((target) => events.forEach((eventName) =>
                    target.removeEventListener(eventName, cancelRestoration, true)));
                targets.forEach((target) => events.forEach((eventName) => target.addEventListener(
                    eventName, cancelRestoration, { once: true, capture: true, passive: true }
                )));
                const restore = (remainingFrames) => {
                    if (element.__docxFullscreenRestoreCancelled) return releaseCancellation();
                    window.scrollTo(returnX, returnY);
                    if (scrollContainer) {
                        scrollContainer.scrollLeft = returnScrollLeft;
                        scrollContainer.scrollTop = returnScrollTop;
                        const rect = element.getBoundingClientRect();
                        scrollContainer.scrollLeft += rect.left - returnGraphLeft;
                        scrollContainer.scrollTop += rect.top - returnGraphTop;
                    }
                    element.setAttribute("data-srcx-fullscreen-restore-frame", String(60 - remainingFrames));
                    element.setAttribute("data-srcx-fullscreen-restore-scroll-top", String(scrollContainer?.scrollTop || 0));
                    element.setAttribute("data-srcx-fullscreen-restore-graph-top", String(element.getBoundingClientRect().top));
                    if (remainingFrames > 0) requestAnimationFrame(() => restore(remainingFrames - 1));
                    else {
                        element.setAttribute("data-srcx-fullscreen-restore-state", "complete");
                        releaseCancellation();
                    }
                };
                requestAnimationFrame(() => requestAnimationFrame(() => restore(60)));
            };
        }""",
    )

@OptIn(ExperimentalWasmJsInterop::class)
@Suppress("UnusedParameter")
private fun toggleFullscreen(element: HTMLElement): Unit =
    js(
        """{
            if (document.fullscreenElement) {
                document.exitFullscreen();
            } else if (element.requestFullscreen) {
                const root = element.getRootNode();
                const shadowHost = root && root.host;
                const scrollContainer = element.closest("[data-docx-atlas-surface]") ||
                    (shadowHost?.matches?.("[data-docx-atlas-surface]")
                        ? shadowHost
                        : shadowHost?.closest?.("[data-docx-atlas-surface]"));
                const graphRect = element.getBoundingClientRect();
                element.__docxFullscreenReturnX = window.scrollX;
                element.__docxFullscreenReturnY = window.scrollY;
                element.__docxFullscreenScrollContainer = scrollContainer;
                element.__docxFullscreenReturnScrollLeft = scrollContainer?.scrollLeft || 0;
                element.__docxFullscreenReturnScrollTop = scrollContainer?.scrollTop || 0;
                element.__docxFullscreenReturnGraphLeft = graphRect.left;
                element.__docxFullscreenReturnGraphTop = graphRect.top;
                element.requestFullscreen().catch((error) => {
                    element.setAttribute("data-srcx-fullscreen", "false");
                    element.setAttribute("data-srcx-fullscreen-error", String(error && error.message || error));
                });
            }
        }""",
    )

@OptIn(ExperimentalWasmJsInterop::class)
@Suppress("UnusedParameter")
private fun releaseFullscreen(element: HTMLElement): Unit =
    js(
        """{
            const handler = element.__docxFullscreenHandler;
            if (handler) document.removeEventListener("fullscreenchange", handler);
            delete element.__docxFullscreenHandler;
            delete element.__docxFullscreenButton;
            delete element.__docxFullscreenChromeToggle;
            delete element.__docxFullscreenActive;
            delete element.__docxFullscreenReturnX;
            delete element.__docxFullscreenReturnY;
            delete element.__docxFullscreenScrollContainer;
            delete element.__docxFullscreenReturnScrollLeft;
            delete element.__docxFullscreenReturnScrollTop;
            delete element.__docxFullscreenReturnGraphLeft;
            delete element.__docxFullscreenReturnGraphTop;
            delete element.__docxFullscreenRestoreCancelled;
            delete element.__docxFullscreenRestore;
            element.removeAttribute("data-srcx-fullscreen-restore-state");
            element.removeAttribute("data-srcx-fullscreen-restore-target-scroll-top");
            element.removeAttribute("data-srcx-fullscreen-restore-target-graph-top");
            element.removeAttribute("data-srcx-fullscreen-restore-cancel-event");
            element.removeAttribute("data-srcx-fullscreen-restore-frame");
            element.removeAttribute("data-srcx-fullscreen-restore-scroll-top");
            element.removeAttribute("data-srcx-fullscreen-restore-graph-top");
        }""",
    )

private val viewportActions = listOf("zoom-in", "zoom-out", "fit", "reset")
