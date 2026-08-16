package zone.clanker.docx.web.atlas.dom.search

import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import zone.clanker.docx.web.state.WorkspaceGlobalSearchState

/** Self-contained global search surface that appends only its own subtree to an existing host. */
internal class AtlasGlobalSearchRenderer {
    private val resultsRenderer = AtlasGlobalSearchResultsRenderer()
    private var mountedHost: HTMLElement? = null
    private var elements: AtlasGlobalSearchElements? = null
    private var model: AtlasGlobalSearchRenderModel? = null
    private var focusActiveAfterRender: Boolean = false
    private val outsideClickListener: (Event) -> Unit = ::handleOutsideClick
    private val inputListener: (Event) -> Unit = ::handleInput
    private val inputKeyListener: (Event) -> Unit = ::handleInputKeyEvent

    fun mount(host: HTMLElement) {
        if (mountedHost === host) return
        release()
        val next = createGlobalSearchElements()
        host.appendChild(next.surface)
        mountedHost = host
        elements = next
        wireStableControls(next)
        host.addEventListener("click", outsideClickListener)
        host.addEventListener("keydown", inputKeyListener)
    }

    fun render(
        host: HTMLElement,
        model: AtlasGlobalSearchRenderModel,
    ) {
        mount(host)
        this.model = model
        val mounted = requireNotNull(elements)
        mounted.surface.setAttribute("data-srcx-global-search-state", model.state.statusName)
        mounted.surface.setAttribute("data-srcx-global-search-query", model.state.queryState.query)
        mounted.surface.setAttribute(
            "data-srcx-global-search-active-key",
            model.state.activeResultKey.orEmpty(),
        )
        mounted.surface.setAttribute(
            "data-srcx-global-search-request-revision",
            model.state.queryState.requestRevision
                .toString(),
        )
        mounted.surface.setAttribute(
            "data-srcx-global-search-result-count",
            model.state.visibleResults.size
                .toString(),
        )
        mounted.surface.setAttribute(
            "data-srcx-global-search-error",
            model.state.queryState.error
                .orEmpty(),
        )
        mounted.surface.setAttribute("data-srcx-global-search-open", model.expanded.toString())
        mounted.input.setAttribute("aria-expanded", model.expanded.toString())
        mounted.input.setAttribute(
            "aria-busy",
            model.state.queryState.loading
                .toString(),
        )
        mounted.input.setAttribute("role", "combobox")
        mounted.input.setAttribute("aria-autocomplete", "list")
        if (mounted.input.value != model.state.queryState.query) {
            mounted.input.value = model.state.queryState.query
        }
        renderPrefixes(mounted, model)
        renderSelection(mounted, model.selectedNodeIds.size)
        mounted.status.textContent = model.state.statusText
        resultsRenderer.render(mounted.results, model)
        mounted.results.hidden = !model.expanded
        model.state.activeResultKey?.let(::globalSearchResultId)?.let { activeId ->
            mounted.input.setAttribute("aria-activedescendant", activeId)
        } ?: mounted.input.removeAttribute("aria-activedescendant")
        if (focusActiveAfterRender) {
            focusActiveAfterRender = false
            resultsRenderer.focusActive(model.state)
        }
    }

    fun release() {
        val mounted = elements
        mounted?.input?.removeEventListener("input", inputListener)
        mounted?.clearSelection?.onclick = null
        mounted?.surface?.onclick = null
        resultsRenderer.release()
        mountedHost?.removeEventListener("click", outsideClickListener)
        mountedHost?.removeEventListener("keydown", inputKeyListener)
        mounted?.surface?.parentElement?.removeChild(mounted.surface)
        mountedHost = null
        elements = null
        model = null
        focusActiveAfterRender = false
    }

    private fun wireStableControls(mounted: AtlasGlobalSearchElements) {
        mounted.surface.onclick = { event ->
            event.stopPropagation()
            null
        }
        mounted.input.addEventListener("input", inputListener)
        mounted.input.onfocus = {
            model?.actions?.onOpened()
            null
        }
        mounted.clearSelection.onclick = {
            model?.actions?.onSelectionCleared()
            null
        }
    }

    private fun handleInput(
        @Suppress("UnusedParameter") event: Event,
    ) {
        elements?.input?.value?.let { query -> model?.actions?.onQueryChanged(query) }
    }

    private fun handleInputKeyEvent(event: Event) {
        val input = elements?.input ?: return
        if (event.target !== input) return
        (event as? KeyboardEvent)?.let(::handleInputKey)
    }

    private fun handleInputKey(event: KeyboardEvent) {
        val current = model ?: return
        when (event.key) {
            "ArrowDown" -> navigate(current, event, 1)
            "ArrowUp" -> navigate(current, event, -1)
            "Home" -> activateBoundary(current, event, first = true)
            "End" -> activateBoundary(current, event, first = false)
            "Enter" ->
                current.state.activeResult?.let { result ->
                    elements?.surface?.setAttribute("data-srcx-global-search-requested-target", result.key)
                    if (event.shiftKey) {
                        current.actions.onResultToggled(result)
                    } else {
                        event.preventDefault()
                        current.actions.onTargetRequested(result)
                    }
                }
            "Escape" -> {
                event.preventDefault()
                dismissAndFocusChart(current)
            }
        }
    }

    private fun handleOutsideClick(
        @Suppress("UnusedParameter") event: Event,
    ) {
        val current = model ?: return
        if (current.expanded) dismissAndFocusChart(current)
    }

    private fun dismissAndFocusChart(current: AtlasGlobalSearchRenderModel) {
        current.actions.onDismissed()
        (mountedHost?.querySelector("[data-docx-atlas-canvas]") as? HTMLElement)?.focus()
    }

    private fun navigate(
        current: AtlasGlobalSearchRenderModel,
        event: KeyboardEvent,
        offset: Int,
    ) {
        if (current.state.visibleResults.isEmpty()) return
        event.preventDefault()
        focusActiveAfterRender = true
        current.actions.onActiveResultChanged(current.state.moveActive(offset).activeResultKey)
    }

    private fun activateBoundary(
        current: AtlasGlobalSearchRenderModel,
        event: KeyboardEvent,
        first: Boolean,
    ) {
        val target =
            if (first) current.state.visibleResults.firstOrNull() else current.state.visibleResults.lastOrNull()
        if (target != null) {
            event.preventDefault()
            focusActiveAfterRender = true
            current.actions.onActiveResultChanged(target.key)
        }
    }

    private fun renderPrefixes(
        mounted: AtlasGlobalSearchElements,
        model: AtlasGlobalSearchRenderModel,
    ) {
        mounted.prefixRail.textContent = ""
        GLOBAL_SEARCH_PREFIXES.forEach { prefix ->
            val active = prefix.matches(model.state.queryState.query)
            mounted.prefixRail.appendChild(
                buttonElement(prefix.label).also { button ->
                    button.setAttribute("data-srcx-global-search-prefix", prefix.token ?: "all")
                    button.setAttribute("aria-pressed", active.toString())
                    button.onclick = {
                        model.actions.onQueryChanged(prefix.applyTo(model.state.queryState.query))
                        mounted.input.focus()
                        null
                    }
                },
            )
        }
    }

    private fun renderSelection(
        mounted: AtlasGlobalSearchElements,
        selectedNodeCount: Int,
    ) {
        mounted.selectionSummary.textContent = "$selectedNodeCount selected"
        mounted.clearSelection.disabled = selectedNodeCount == 0
    }
}

private data class GlobalSearchPrefix(
    val token: String?,
    val label: String,
) {
    fun matches(query: String): Boolean {
        val requested = query.substringBefore(':', "").lowercase().takeIf(DOCUMENTED_PREFIXES::contains)
        return requested == token
    }

    fun applyTo(query: String): String {
        val requested = query.substringBefore(':', "").lowercase()
        val body = if (requested in DOCUMENTED_PREFIXES) query.substringAfter(':').trimStart() else query
        return token?.let { value -> "$value:$body" } ?: body
    }
}

private val GLOBAL_SEARCH_PREFIXES: List<GlobalSearchPrefix> =
    listOf(
        GlobalSearchPrefix(null, "Everything"),
        GlobalSearchPrefix("build", "Builds"),
        GlobalSearchPrefix("project", "Projects"),
        GlobalSearchPrefix("source", "Sources"),
        GlobalSearchPrefix("package", "Packages"),
        GlobalSearchPrefix("file", "Files"),
        GlobalSearchPrefix("class", "Classes"),
        GlobalSearchPrefix("symbol", "Symbols"),
        GlobalSearchPrefix("method", "Methods"),
        GlobalSearchPrefix("ext", "Extensions"),
        GlobalSearchPrefix("problem", "Problems"),
        GlobalSearchPrefix("cycle", "Cycles"),
    )

private val DOCUMENTED_PREFIXES: Set<String> =
    GLOBAL_SEARCH_PREFIXES.mapNotNullTo(mutableSetOf(), GlobalSearchPrefix::token)

internal fun globalSearchResultId(key: String): String = "srcx-global-search-result-$key"

private val WorkspaceGlobalSearchState.statusName: String
    get() =
        when {
            queryState.loading -> "loading"
            queryState.error != null -> "error"
            queryState.query.isBlank() -> "idle"
            visibleResults.isEmpty() -> "empty"
            else -> "ready"
        }

private val WorkspaceGlobalSearchState.statusText: String
    get() =
        when {
            queryState.loading -> "Searching the workspace index…"
            queryState.error != null -> requireNotNull(queryState.error)
            queryState.query.isBlank() -> "Type a name or choose a category to search the entire workspace."
            results == null || visibleResults.isEmpty() -> "No matching workspace records."
            results.truncated -> "${visibleResults.size} results shown; refine the query to search a truncated prefix."
            else -> "${visibleResults.size} results in ${results.groups.size} groups."
        }
