package zone.clanker.docx.web.atlas.dom.controls

import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLDivElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.KeyboardEvent
import zone.clanker.docx.web.atlas.dom.clearContent
import zone.clanker.docx.web.atlas.dom.htmlElement
import zone.clanker.docx.web.atlas.dom.requiredHtmlElement
import zone.clanker.docx.web.atlas.dom.requiredInput

internal data class VirtualFilterChoice(
    val key: String,
    val spec: FilterButtonSpec,
    val selectionState: FilterSelectionState,
    val attributes: Map<String, String> = emptyMap(),
    val onClick: () -> Unit,
) {
    val searchText: String = "${spec.copy.label} ${spec.copy.detail}".lowercase()
}

internal enum class FilterSelectionState {
    SELECTED,
    PARTIAL,
    UNSELECTED,
}

/**
 * A bounded DOM window over an arbitrarily large logical choice list.
 *
 * The rail always contains one sizing canvas and at most seven buttons: the five visible rows plus
 * one overscan row on either side. Search, keyboard focus, and pinned selections operate on the
 * logical list rather than on whichever buttons happen to be mounted.
 */
internal class AtlasVirtualizedChoiceRail(
    private val kind: String,
) {
    private lateinit var root: HTMLDivElement
    private lateinit var rail: HTMLElement
    private lateinit var canvas: HTMLElement
    private lateinit var searchInput: HTMLInputElement
    private lateinit var clearButton: HTMLButtonElement
    private var allChoices: List<VirtualFilterChoice> = emptyList()
    private var visibleChoices: List<VirtualFilterChoice> = emptyList()
    private var focusedKey: String? = null
    private var choiceSignature: List<String>? = null
    private var selectionSignature: List<String>? = null
    private var renderedWindowStart = -1
    private var renderedWindowEnd = -1
    private var renderedRovingKey: String? = null
    private var mountedButtons: Map<String, HTMLButtonElement> = emptyMap()

    fun render(
        root: HTMLDivElement,
        choices: List<VirtualFilterChoice>,
        clearButton: HTMLButtonElement,
    ) {
        this.root = root
        this.rail = root.requiredHtmlElement("[data-srcx-$kind-filter]")
        this.searchInput = root.requiredInput("[data-srcx-$kind-filter-search]")
        this.clearButton = clearButton
        rail.style.setProperty("inline-size", "100%")
        rail.style.setProperty("max-inline-size", "100%")
        rail.style.setProperty("min-inline-size", "0")
        rail.style.setProperty("overflow-x", "hidden")
        rail.style.setProperty("overflow-y", "auto")
        val nextChoiceSignature = choices.map(VirtualFilterChoice::key)
        allChoices = choices.pinSelectedChoices()

        val nextSelectionSignature =
            allChoices
                .filter { choice -> choice.selectionState != FilterSelectionState.UNSELECTED }
                .map { choice -> "${choice.selectionState}:${choice.key}" }
        if (
            choiceSignature?.let { previous -> previous != nextChoiceSignature } == true ||
            selectionSignature?.let { previous -> previous != nextSelectionSignature } == true
        ) {
            rail.scrollTop = 0.0
        }
        choiceSignature = nextChoiceSignature
        selectionSignature = nextSelectionSignature

        rail.clearContent()
        rail.setAttribute("data-srcx-choice-layout", "five-row-window")
        rail.setAttribute("data-srcx-virtualized", "true")
        rail.setAttribute("aria-orientation", "vertical")
        canvas = htmlElement("div", "srcx-dashboard__architecture-virtual-canvas")
        canvas.setAttribute("data-srcx-virtual-canvas", kind)
        rail.appendChild(canvas)
        renderedWindowStart = -1
        renderedWindowEnd = -1
        renderedRovingKey = null
        mountedButtons = emptyMap()

        wireSearch()
        wireClearButton()
        applyQuery(resetScroll = false)
        rail.onscroll = {
            renderWindow()
            null
        }
    }

    private fun wireSearch() {
        searchInput.oninput = {
            applyQuery(resetScroll = true)
            null
        }
        searchInput.onkeydown = { event ->
            when (event.key) {
                "ArrowDown" -> {
                    event.preventDefault()
                    clearButton.focus()
                    root.setAttribute("data-srcx-filter-focus", kind)
                }
                "End" -> {
                    event.preventDefault()
                    focusChoice(visibleChoices.lastIndex)
                }
                "Escape" -> {
                    event.preventDefault()
                    focusAndCloseScopeChooser(root, kind)
                }
            }
            null
        }
    }

    private fun wireClearButton() {
        clearButton.tabIndex = 0
        clearButton.onkeydown = { event ->
            when (event.key) {
                "ArrowRight", "ArrowDown" -> {
                    event.preventDefault()
                    focusChoice(0)
                }
                "ArrowLeft", "ArrowUp", "End" -> {
                    event.preventDefault()
                    focusChoice(visibleChoices.lastIndex)
                }
                "Escape" -> {
                    event.preventDefault()
                    focusAndCloseScopeChooser(root, kind)
                }
            }
            null
        }
    }

    private fun applyQuery(resetScroll: Boolean) {
        val query = searchInput.value.trim().lowercase()
        visibleChoices =
            if (query.isEmpty()) {
                allChoices
            } else {
                allChoices.filter { choice -> choice.searchText.contains(query) }
            }
        if (resetScroll) rail.scrollTop = 0.0
        if (visibleChoices.none { choice -> choice.key == focusedKey }) {
            focusedKey = visibleChoices.firstOrNull()?.key
        }
        root.requiredHtmlElement("[data-srcx-$kind-filter-empty]").hidden = visibleChoices.isNotEmpty()
        rail.setAttribute("data-srcx-total-choice-count", visibleChoices.size.toString())
        renderWindow(force = true)
    }

    private fun renderWindow(
        requestFocus: Boolean = false,
        force: Boolean = false,
    ) {
        canvas.style.setProperty(
            "block-size",
            virtualCanvasSize(visibleChoices.size),
        )
        if (visibleChoices.isEmpty()) {
            canvas.clearContent()
            mountedButtons = emptyMap()
            renderedWindowStart = -1
            renderedWindowEnd = -1
            renderedRovingKey = null
            rail.setAttribute("data-srcx-mounted-choice-count", "0")
            return
        }

        val window = visibleWindow()
        focusedKey = visibleChoices[window.rovingIndex].key
        if (!force && window.matches(renderedWindowStart, renderedWindowEnd, renderedRovingKey, focusedKey)) {
            if (requestFocus) mountedButtons[focusedKey]?.focus()
            return
        }

        canvas.clearContent()
        val nextMountedButtons = mutableMapOf<String, HTMLButtonElement>()
        for (index in window.start until window.end) {
            val choice = visibleChoices[index]
            val button = choiceButton(choice, index, window.rovingIndex)
            canvas.appendChild(button)
            nextMountedButtons[choice.key] = button
        }
        mountedButtons = nextMountedButtons
        renderedWindowStart = window.start
        renderedWindowEnd = window.end
        renderedRovingKey = focusedKey
        rail.setAttribute("data-srcx-mounted-choice-count", mountedButtons.size.toString())
        if (requestFocus) mountedButtons[focusedKey]?.focus()
    }

    private fun visibleWindow(): VirtualChoiceWindow {
        val firstVisibleIndex =
            (rail.scrollTop / VIRTUAL_ROW_STRIDE_PX)
                .toInt()
                .coerceIn(0, visibleChoices.lastIndex)
        val start = (firstVisibleIndex - VIRTUAL_OVERSCAN_ROWS).coerceAtLeast(0)
        val end =
            (firstVisibleIndex + VIRTUAL_VISIBLE_ROWS + VIRTUAL_OVERSCAN_ROWS)
                .coerceAtMost(visibleChoices.size)
        val focusedIndex = visibleChoices.indexOfFirst { choice -> choice.key == focusedKey }
        val rovingIndex = focusedIndex.takeIf { index -> index in start until end } ?: firstVisibleIndex
        return VirtualChoiceWindow(start, end, rovingIndex)
    }

    private fun choiceButton(
        choice: VirtualFilterChoice,
        index: Int,
        rovingIndex: Int,
    ): HTMLButtonElement =
        filterButton(choice.spec) {
            focusedKey = choice.key
            choice.onClick()
        }.also { button ->
            button.style.setProperty("block-size", "${VIRTUAL_ROW_HEIGHT_PX}px")
            button.style.setProperty("inset-block-start", "${index * VIRTUAL_ROW_STRIDE_PX}px")
            button.style.setProperty("inline-size", "100%")
            button.style.setProperty("position", "absolute")
            button.setAttribute("aria-posinset", (index + 1).toString())
            button.setAttribute("aria-setsize", visibleChoices.size.toString())
            button.setAttribute("data-srcx-choice-index", index.toString())
            button.setAttribute("data-srcx-selection-state", choice.selectionState.attributeValue)
            choice.attributes.forEach { (name, value) -> button.setAttribute(name, value) }
            if (choice.selectionState == FilterSelectionState.PARTIAL) button.setAttribute("aria-pressed", "mixed")
            button.tabIndex = if (index == rovingIndex) 0 else -1
            button.onkeydown = { event ->
                navigateFromChoice(event, index)
                null
            }
        }

    private fun navigateFromChoice(
        event: KeyboardEvent,
        currentIndex: Int,
    ) {
        when (event.key) {
            "ArrowRight", "ArrowDown" -> {
                event.preventDefault()
                if (currentIndex == visibleChoices.lastIndex) clearButton.focus() else focusChoice(currentIndex + 1)
            }
            "ArrowLeft", "ArrowUp" -> {
                event.preventDefault()
                if (currentIndex == 0) clearButton.focus() else focusChoice(currentIndex - 1)
            }
            "Home" -> {
                event.preventDefault()
                clearButton.focus()
            }
            "End" -> {
                event.preventDefault()
                focusChoice(visibleChoices.lastIndex)
            }
            "Escape" -> {
                event.preventDefault()
                focusAndCloseScopeChooser(root, kind)
            }
        }
        root.setAttribute("data-srcx-filter-focus", kind)
    }

    private fun focusChoice(index: Int) {
        if (index !in visibleChoices.indices) return
        focusedKey = visibleChoices[index].key
        val firstVisibleIndex = (rail.scrollTop / VIRTUAL_ROW_STRIDE_PX).toInt().coerceAtLeast(0)
        when {
            index < firstVisibleIndex -> rail.scrollTop = (index * VIRTUAL_ROW_STRIDE_PX).toDouble()
            index >= firstVisibleIndex + VIRTUAL_VISIBLE_ROWS ->
                rail.scrollTop = ((index - VIRTUAL_VISIBLE_ROWS + 1) * VIRTUAL_ROW_STRIDE_PX).toDouble()
        }
        renderWindow(requestFocus = true, force = true)
        root.setAttribute("data-srcx-filter-focus", kind)
    }
}

private data class VirtualChoiceWindow(
    val start: Int,
    val end: Int,
    val rovingIndex: Int,
) {
    fun matches(
        renderedStart: Int,
        renderedEnd: Int,
        renderedKey: String?,
        focusedKey: String?,
    ): Boolean = start == renderedStart && end == renderedEnd && focusedKey == renderedKey
}

private fun List<VirtualFilterChoice>.pinSelectedChoices(): List<VirtualFilterChoice> =
    FilterSelectionState.entries.flatMap { state -> filter { choice -> choice.selectionState == state } }

private val FilterSelectionState.attributeValue: String
    get() = name.lowercase()

private fun virtualCanvasSize(choiceCount: Int): String =
    if (choiceCount == 0) {
        "0px"
    } else {
        "${choiceCount * VIRTUAL_ROW_STRIDE_PX - VIRTUAL_ROW_GAP_PX}px"
    }

private const val VIRTUAL_VISIBLE_ROWS = 5
private const val VIRTUAL_OVERSCAN_ROWS = 1
private const val VIRTUAL_ROW_HEIGHT_PX = 44
private const val VIRTUAL_ROW_GAP_PX = 4
private const val VIRTUAL_ROW_STRIDE_PX = VIRTUAL_ROW_HEIGHT_PX + VIRTUAL_ROW_GAP_PX
