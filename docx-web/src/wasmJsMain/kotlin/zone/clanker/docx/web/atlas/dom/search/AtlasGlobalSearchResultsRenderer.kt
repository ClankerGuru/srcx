package zone.clanker.docx.web.atlas.dom.search

import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLDivElement
import org.w3c.dom.HTMLElement
import zone.clanker.docx.web.state.WorkspaceGlobalSearchState
import zone.clanker.docx.web.state.WorkspaceSearchVirtualWindow
import zone.clanker.docx.web.state.workspaceSearchVirtualWindow
import zone.clanker.report.model.WorkspaceSearchBadge
import zone.clanker.report.model.WorkspaceSearchEntry
import zone.clanker.report.model.WorkspaceSearchKind
import kotlin.js.ExperimentalWasmJsInterop

internal class AtlasGlobalSearchResultsRenderer {
    private var targetButtons: Map<String, HTMLButtonElement> = emptyMap()
    private var currentHost: HTMLDivElement? = null
    private var currentModel: AtlasGlobalSearchRenderModel? = null
    private var resultKeys: List<String> = emptyList()
    private var firstVisibleIndex: Int = 0
    private var mountedWindow: WorkspaceSearchVirtualWindow = WorkspaceSearchVirtualWindow(0, emptyList())
    private var focusActiveAfterRender: Boolean = false

    fun render(
        host: HTMLDivElement,
        model: AtlasGlobalSearchRenderModel,
    ) {
        val entries = model.state.visibleResults
        val nextKeys = entries.map(WorkspaceSearchEntry::key)
        val activeIndex = entries.indexOfFirst { entry -> entry.key == model.state.activeResultKey }
        if (nextKeys != resultKeys) {
            firstVisibleIndex = activeIndex.coerceAtLeast(0).centeredVisibleStart(entries.size)
        } else if (
            activeIndex >= 0 &&
            activeIndex !in mountedWindow.firstIndex until mountedWindow.lastIndexExclusive
        ) {
            firstVisibleIndex = activeIndex.centeredVisibleStart(entries.size)
        }
        currentHost = host
        currentModel = model
        resultKeys = nextKeys
        host.onscroll = {
            handleScroll()
            null
        }
        renderWindow(host, model, entries)
        if (activeIndex >= 0) host.scrollTop = firstVisibleIndex * SEARCH_RESULT_ROW_HEIGHT.toDouble()
        if (focusActiveAfterRender) {
            focusActiveAfterRender = false
            focusActive(model.state)
        }
    }

    fun release() {
        currentHost?.onscroll = null
        currentHost = null
        currentModel = null
        resultKeys = emptyList()
        firstVisibleIndex = 0
        mountedWindow = WorkspaceSearchVirtualWindow(0, emptyList())
        focusActiveAfterRender = false
        targetButtons = emptyMap()
    }

    private fun handleScroll() {
        val host = currentHost ?: return
        val model = currentModel ?: return
        val entries = model.state.visibleResults
        val nextFirst =
            (host.scrollTop / SEARCH_RESULT_ROW_HEIGHT)
                .toInt()
                .coerceIn(0, (entries.size - VISIBLE_SEARCH_ROWS).coerceAtLeast(0))
        if (nextFirst != firstVisibleIndex) {
            firstVisibleIndex = nextFirst
            renderWindow(host, model, entries)
        }
    }

    private fun renderWindow(
        host: HTMLDivElement,
        model: AtlasGlobalSearchRenderModel,
        entries: List<WorkspaceSearchEntry>,
    ) {
        mountedWindow = workspaceSearchVirtualWindow(entries, firstVisibleIndex, VISIBLE_SEARCH_ROWS, OVERSCAN_ROWS)
        val groupCounts = entries.groupingBy(WorkspaceSearchEntry::kind).eachCount()
        val canvas = divElement("srcx-global-search__virtual-canvas")
        canvas.style.height = "${entries.size * SEARCH_RESULT_ROW_HEIGHT}px"
        host.textContent = ""
        targetButtons =
            buildMap {
                mountedWindow.entries.forEachIndexed { index, result ->
                    canvas.appendChild(
                        resultGroup(
                            result = result,
                            resultIndex = mountedWindow.firstIndex + index,
                            groupCount = groupCounts.getValue(result.kind),
                            model = model,
                            buttons = this,
                        ),
                    )
                }
            }
        host.appendChild(canvas)
        host.setAttribute("data-srcx-global-search-group-count", groupCounts.size.toString())
        host.setAttribute("data-srcx-global-search-result-count", entries.size.toString())
        host.setAttribute("data-srcx-global-search-mounted-count", targetButtons.size.toString())
    }

    fun focusActive(state: WorkspaceGlobalSearchState) {
        state.activeResultKey?.let { key -> targetButtons[key]?.let(::focusSearchResult) }
    }

    private fun resultGroup(
        result: WorkspaceSearchEntry,
        resultIndex: Int,
        groupCount: Int,
        model: AtlasGlobalSearchRenderModel,
        buttons: MutableMap<String, HTMLButtonElement>,
    ): HTMLElement {
        val section = element("section", "srcx-global-search__group")
        section.style.top = "${resultIndex * SEARCH_RESULT_ROW_HEIGHT}px"
        section.setAttribute("data-srcx-global-search-group", result.kind.name.lowercase())
        section.setAttribute("role", "group")
        section.setAttribute("aria-label", "${result.kind.displayName}, $groupCount results")
        section.appendChild(
            element(
                "h3",
                "srcx-global-search__group-title",
                "${result.kind.displayName} · $groupCount",
            ),
        )
        section.appendChild(resultRow(result, model, buttons))
        return section
    }

    private fun resultRow(
        result: WorkspaceSearchEntry,
        model: AtlasGlobalSearchRenderModel,
        buttons: MutableMap<String, HTMLButtonElement>,
    ): HTMLDivElement {
        val state = model.state
        val selected = result.id in model.selectedNodeIds
        val active = result.key == state.activeResultKey
        val row = divElement("srcx-global-search__result")
        row.setAttribute("data-srcx-global-search-result", result.key)
        row.setAttribute("data-srcx-search-kind", result.kind.name.lowercase())
        row.setAttribute("data-srcx-search-active", active.toString())
        row.setAttribute("data-srcx-search-selected", selected.toString())
        val toggle = buttonElement(if (selected) "Selected" else "Select")
        toggle.className = "srcx-global-search__toggle"
        toggle.setAttribute("data-srcx-global-search-toggle", result.key)
        toggle.setAttribute("aria-pressed", selected.toString())
        toggle.setAttribute("aria-label", "${if (selected) "Remove" else "Add"} ${result.label} from selection")
        toggle.onclick = {
            model.actions.onResultToggled(result)
            null
        }
        val target = buttonElement("")
        target.id = globalSearchResultId(result.key)
        target.className = "srcx-global-search__target"
        target.setAttribute("data-srcx-global-search-target", result.key)
        target.setAttribute("aria-label", "Go to ${result.label}, ${result.detail}")
        target.setAttribute("role", "option")
        target.setAttribute("aria-selected", selected.toString())
        target.tabIndex = if (active) 0 else -1
        target.appendChild(element("strong", "srcx-global-search__label", result.label))
        target.appendChild(element("small", "srcx-global-search__detail", result.detail))
        if (result.badges.isNotEmpty()) {
            target.appendChild(
                element("span", "srcx-global-search__badges").also { badges ->
                    result.badges.forEach { badge -> badges.appendChild(resultBadge(badge)) }
                },
            )
        }
        target.onfocus = {
            model.actions.onActiveResultChanged(result.key)
            null
        }
        target.onmouseenter = {
            model.actions.onActiveResultChanged(result.key)
            null
        }
        target.onclick = {
            model.actions.onTargetRequested(result)
            null
        }
        target.onkeydown = { event ->
            focusActiveAfterRender = handleResultKey(event, result, model)
            null
        }
        buttons[result.key] = target
        row.appendChild(toggle)
        row.appendChild(target)
        return row
    }
}

@OptIn(ExperimentalWasmJsInterop::class)
@Suppress("UnusedParameter")
private fun focusSearchResult(button: HTMLButtonElement): Unit =
    js(
        """{
            const focus = () => {
                if (!button.isConnected) return;
                button.focus({ preventScroll: true });
                const surface = button.closest("[data-srcx-global-search]");
                surface?.setAttribute("data-srcx-global-search-focused-target", button.dataset.srcxGlobalSearchTarget || "");
            };
            queueMicrotask(() => {
                focus();
                requestAnimationFrame(focus);
            });
        }""",
    )

private fun resultBadge(badge: WorkspaceSearchBadge): HTMLElement =
    element(
        tagName = "span",
        className = "srcx-global-search__badge",
        text = "${badge.count} ${badge.kind.label}",
    ).also { element ->
        element.setAttribute("data-srcx-search-badge", badge.kind.name.lowercase())
    }

private fun Int.centeredVisibleStart(entryCount: Int): Int =
    (this - VISIBLE_SEARCH_ROWS / 2).coerceIn(0, (entryCount - VISIBLE_SEARCH_ROWS).coerceAtLeast(0))

private fun handleResultKey(
    event: org.w3c.dom.events.KeyboardEvent,
    result: WorkspaceSearchEntry,
    model: AtlasGlobalSearchRenderModel,
): Boolean =
    when (event.key) {
        "ArrowDown", "ArrowRight" -> model.navigate(event, 1)
        "ArrowUp", "ArrowLeft" -> model.navigate(event, -1)
        "Home" -> model.activateBoundary(event, first = true)
        "End" -> model.activateBoundary(event, first = false)
        "Enter" -> {
            if (event.shiftKey) {
                event.preventDefault()
                model.actions.onResultToggled(result)
            }
            false
        }
        " ", "Spacebar" -> {
            event.preventDefault()
            model.actions.onResultToggled(result)
            false
        }
        "Escape" -> {
            event.preventDefault()
            model.actions.onDismissed()
            false
        }
        else -> false
    }

private fun AtlasGlobalSearchRenderModel.navigate(
    event: org.w3c.dom.events.KeyboardEvent,
    offset: Int,
): Boolean {
    event.preventDefault()
    actions.onActiveResultChanged(state.moveActive(offset).activeResultKey)
    return true
}

private fun AtlasGlobalSearchRenderModel.activateBoundary(
    event: org.w3c.dom.events.KeyboardEvent,
    first: Boolean,
): Boolean {
    event.preventDefault()
    val target = if (first) state.visibleResults.firstOrNull() else state.visibleResults.lastOrNull()
    actions.onActiveResultChanged(target?.key)
    return target != null
}

private val WorkspaceSearchKind.displayName: String
    get() =
        when (this) {
            WorkspaceSearchKind.BUILD -> "Builds"
            WorkspaceSearchKind.PROJECT -> "Projects"
            WorkspaceSearchKind.SOURCE_SET -> "Source sets"
            WorkspaceSearchKind.PACKAGE -> "Packages"
            WorkspaceSearchKind.FILE -> "Files"
            WorkspaceSearchKind.CLASS -> "Classes"
            WorkspaceSearchKind.INTERFACE -> "Interfaces"
            WorkspaceSearchKind.OBJECT -> "Objects"
            WorkspaceSearchKind.ENUM -> "Enums"
            WorkspaceSearchKind.FUNCTION -> "Functions / methods"
            WorkspaceSearchKind.PROPERTY -> "Properties"
            WorkspaceSearchKind.FINDING -> "Findings"
            WorkspaceSearchKind.CYCLE -> "Cycles"
        }

private const val VISIBLE_SEARCH_ROWS: Int = 5
private const val OVERSCAN_ROWS: Int = 1
private const val SEARCH_RESULT_ROW_HEIGHT: Int = 72
