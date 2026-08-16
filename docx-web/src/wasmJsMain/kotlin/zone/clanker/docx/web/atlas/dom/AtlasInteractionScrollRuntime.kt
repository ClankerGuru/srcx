package zone.clanker.docx.web.atlas.dom

import kotlinx.browser.window
import org.w3c.dom.HTMLDivElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.Node
import org.w3c.dom.events.Event
import kotlin.js.ExperimentalWasmJsInterop

internal class AtlasInteractionScrollRuntime(
    private val root: () -> HTMLDivElement?,
    private val host: () -> HTMLElement?,
) {
    private var interactionScrollAnchor: Double? = null
    private var interactionGraphTopAnchor: Double? = null
    private var pendingSelectionScrollTop: Double? = null
    private var pointerInteractionScrollTop: Double? = null
    private var detachedSurfaceScrollTop: Double? = null
    private var restoreRevision = 0

    val preservedScrollTop: Double?
        get() = interactionScrollAnchor ?: pendingSelectionScrollTop ?: detachedSurfaceScrollTop

    val selectionShouldFocusDetail: Boolean
        get() = pointerInteractionScrollTop == null

    fun release(detach: Boolean) {
        restoreRevision += 1
        if (detach) detachedSurfaceScrollTop = preservedScrollTop ?: root()?.scrollTop
    }

    fun attach(nextRoot: HTMLDivElement) {
        detachedSurfaceScrollTop?.let { scrollTop -> restore(nextRoot, scrollTop, "surface-attach") }
    }

    fun restorePreserved(
        element: HTMLElement,
        reason: String,
        clearDetached: Boolean = false,
    ) {
        preservedScrollTop?.let { scrollTop -> restore(element, scrollTop, reason) }
        if (clearDetached) detachedSurfaceScrollTop = null
    }

    fun restorePendingSelection(element: HTMLElement) {
        pendingSelectionScrollTop?.let { scrollTop -> restore(element, scrollTop, "selection-dispatch") }
    }

    fun finishDetailFocus(element: HTMLElement) {
        val scrollTop = pendingSelectionScrollTop ?: return
        restore(element, scrollTop, "detail-focus")
        pendingSelectionScrollTop = null
    }

    fun preserveSelection(
        selection: GraphSelection,
        currentSelection: GraphSelection,
    ) {
        if (selection == currentSelection) return
        val currentScrollTop = root()?.scrollTop
        pendingSelectionScrollTop = pointerInteractionScrollTop ?: currentScrollTop
        interactionScrollAnchor = pendingSelectionScrollTop
        if (interactionGraphTopAnchor == null) interactionGraphTopAnchor = graphViewportTop(root(), host())
        host()?.setAttribute(SELECTION_SCROLL_ATTRIBUTE, currentScrollTop.toString())
        host()?.setAttribute(PRESERVED_SCROLL_ATTRIBUTE, pendingSelectionScrollTop.toString())
        pointerInteractionScrollTop = null
    }

    fun handleGraphPointerDown(
        @Suppress("UnusedParameter") event: Event,
    ) {
        pointerInteractionScrollTop = root()?.scrollTop
        interactionScrollAnchor = pointerInteractionScrollTop
        interactionGraphTopAnchor = graphViewportTop(root(), host())
        host()?.setAttribute(POINTER_SCROLL_ATTRIBUTE, pointerInteractionScrollTop.toString())
    }

    fun handleSurfacePointerDown(event: Event) {
        if (event.targetsGraphHost(host())) return
        val currentRoot = root()
        val anchor = interactionScrollAnchor ?: pendingSelectionScrollTop
        if (
            currentRoot != null &&
            anchor != null &&
            interactionViewportHasDrifted(
                currentRoot,
                anchor,
                interactionGraphTopAnchor,
                graphViewportTop(root(), host()),
            )
        ) {
            event.preventDefault()
            event.stopPropagation()
            restore(currentRoot, anchor, "stale-pointer-hit")
            return
        }
        clearAnchor()
    }

    fun handleSurfaceWheel(event: Event) {
        if (event.targetsGraphHost(host())) {
            interactionScrollAnchor = root()?.scrollTop
            interactionGraphTopAnchor = graphViewportTop(root(), host())
        } else {
            clearAnchor()
        }
    }

    fun handleSurfaceScroll(
        @Suppress("UnusedParameter") event: Event,
    ) {
        val currentRoot = root() ?: return
        val anchor = interactionScrollAnchor ?: pendingSelectionScrollTop ?: return
        if (
            interactionViewportHasDrifted(
                currentRoot,
                anchor,
                interactionGraphTopAnchor,
                graphViewportTop(root(), host()),
            )
        ) {
            restore(currentRoot, anchor, "surface-scroll")
        }
    }

    fun handleFullscreenTransition(
        @Suppress("UnusedParameter") event: Event,
    ) {
        pendingSelectionScrollTop = null
        clearAnchor()
    }

    private fun restore(
        element: HTMLElement,
        scrollTop: Double,
        reason: String,
    ) {
        val revision = ++restoreRevision
        element.setAttribute("data-docx-atlas-scroll-restore-reason", reason)
        val targetScrollTop =
            interactionScrollTarget(
                element,
                scrollTop,
                interactionGraphTopAnchor,
                graphViewportTop(root(), host()),
            )
        element.setAttribute("data-docx-atlas-scroll-restore-request", targetScrollTop.toString())
        element.scrollTop = targetScrollTop
        element.setAttribute("data-docx-atlas-scroll-restore-sync", element.scrollTop.toString())
        restoreBeforePaint(element, targetScrollTop)
        continueRestoration(element, scrollTop, revision)
    }

    private fun continueRestoration(
        element: HTMLElement,
        scrollTop: Double,
        revision: Int,
        remainingFrames: Int = SCROLL_RESTORE_FRAME_LIMIT,
    ) {
        window.requestAnimationFrame {
            if (revision != restoreRevision || root() !== element) return@requestAnimationFrame
            val anchorStillActive = interactionScrollAnchor == scrollTop || pendingSelectionScrollTop == scrollTop
            if (!anchorStillActive) return@requestAnimationFrame
            val targetScrollTop =
                interactionScrollTarget(
                    element,
                    scrollTop,
                    interactionGraphTopAnchor,
                    graphViewportTop(root(), host()),
                )
            if (kotlin.math.abs(element.scrollTop - targetScrollTop) >= SCROLL_RESTORE_TOLERANCE) {
                element.scrollTop = targetScrollTop
            }
            element.setAttribute("data-docx-atlas-scroll-restore-frame", element.scrollTop.toString())
            if (remainingFrames > 1) {
                continueRestoration(element, scrollTop, revision, remainingFrames - 1)
            } else {
                clearAnchor()
            }
        }
    }

    private fun clearAnchor() {
        interactionScrollAnchor = null
        interactionGraphTopAnchor = null
        pointerInteractionScrollTop = null
        restoreRevision += 1
    }
}

private fun graphViewportTop(
    root: HTMLDivElement?,
    host: HTMLElement?,
): Double? {
    val currentRoot = root ?: return null
    val graph = host?.closest("[data-srcx-architecture-graph]") as? HTMLElement ?: return null
    return graph.getBoundingClientRect().top - currentRoot.getBoundingClientRect().top
}

private fun Event.targetsGraphHost(graphHost: HTMLElement?): Boolean {
    graphHost ?: return false
    val eventTarget = target as? Node ?: return false
    return eventTarget === graphHost || graphHost.contains(eventTarget)
}

private fun interactionScrollTarget(
    element: HTMLElement,
    fallbackScrollTop: Double,
    anchoredGraphTop: Double?,
    currentGraphTop: Double?,
): Double {
    if (anchoredGraphTop == null || currentGraphTop == null) return fallbackScrollTop
    return (element.scrollTop + currentGraphTop - anchoredGraphTop).coerceAtLeast(0.0)
}

private fun interactionViewportHasDrifted(
    element: HTMLElement,
    fallbackScrollTop: Double,
    anchoredGraphTop: Double?,
    currentGraphTop: Double?,
): Boolean =
    kotlin.math.abs(
        element.scrollTop -
            interactionScrollTarget(element, fallbackScrollTop, anchoredGraphTop, currentGraphTop),
    ) >= SCROLL_RESTORE_TOLERANCE

@OptIn(ExperimentalWasmJsInterop::class)
@Suppress("UnusedParameter")
private fun restoreBeforePaint(
    element: HTMLElement,
    scrollTop: Double,
): Unit = js("queueMicrotask(() => { if (element.isConnected) element.scrollTop = scrollTop; })")

private const val POINTER_SCROLL_ATTRIBUTE = "data-docx-atlas-pointer-scroll-top"
private const val SCROLL_RESTORE_FRAME_LIMIT = 4
private const val SELECTION_SCROLL_ATTRIBUTE = "data-docx-atlas-selection-scroll-top"
private const val PRESERVED_SCROLL_ATTRIBUTE = "data-docx-atlas-preserved-scroll-top"
private const val SCROLL_RESTORE_TOLERANCE = 1.0
