package zone.clanker.docx.web.atlas.dom.controls

import org.w3c.dom.HTMLDivElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import zone.clanker.docx.web.atlas.AtlasGraphController
import zone.clanker.docx.web.atlas.dom.buttonElement
import zone.clanker.docx.web.atlas.dom.clearContent
import zone.clanker.docx.web.atlas.dom.requiredElement
import zone.clanker.docx.web.atlas.dom.requiredHtmlElement
import zone.clanker.docx.web.atlas.dom.requiredInput
import zone.clanker.docx.web.atlas.projection.AtlasSearchTarget

internal class AtlasSearchControlsRenderer {
    private var input: HTMLInputElement? = null
    private var controller: AtlasGraphController? = null
    private val inputListener: (Event) -> Unit = {
        controller?.updateSearch(input?.value.orEmpty())
    }

    fun render(
        root: HTMLDivElement,
        graph: AtlasGraphController,
        enabled: Boolean,
    ) {
        renderInput(root, graph, enabled)
        val rail = root.requiredHtmlElement("[data-srcx-search-target-options]")
        rail.clearContent()
        val buttons =
            (listOf<AtlasSearchTarget?>(null) + AtlasSearchTarget.entries)
                .map { target -> searchTargetButton(graph, target, enabled) }
        buttons.forEach { button -> rail.appendChild(button) }
        wireControlChipGroup(root, buttons)
        root.requiredElement("[data-srcx-search-target-summary]").textContent = graph.searchTargetSummary()
    }

    fun release() {
        input?.removeEventListener("input", inputListener)
        input = null
        controller = null
    }

    private fun renderInput(
        root: HTMLDivElement,
        graph: AtlasGraphController,
        enabled: Boolean,
    ) {
        val nextInput = root.requiredInput("[data-srcx-graph-search]")
        if (input !== nextInput) {
            input?.removeEventListener("input", inputListener)
            input = nextInput
            nextInput.addEventListener("input", inputListener)
        }
        controller = graph
        nextInput.disabled = !enabled
        nextInput.placeholder = graph.searchPlaceholder()
        if (nextInput.value != graph.searchQuery) nextInput.value = graph.searchQuery
    }

    private fun searchTargetButton(
        graph: AtlasGraphController,
        target: AtlasSearchTarget?,
        enabled: Boolean,
    ) =
        buttonElement(
            label = target?.label ?: "Everything",
            pressed = target?.let(graph.searchTargets::contains) ?: graph.searchTargets.isEmpty(),
        ) {
            if (target == null) graph.selectAllSearchTargets() else graph.toggleSearchTarget(target)
        }.also { button ->
            button.className = "srcx-dashboard__architecture-search-target-button"
            button.setAttribute("data-srcx-search-target", target?.name?.lowercase() ?: "all")
            button.disabled = !enabled
            button.title = target?.description ?: "Match every searchable build, project, and source field"
        }
}

private val AtlasSearchTarget.label: String
    get() =
        when (this) {
            AtlasSearchTarget.BUILD -> "Builds"
            AtlasSearchTarget.PROJECT -> "Projects"
            AtlasSearchTarget.PACKAGE -> "Packages"
            AtlasSearchTarget.FILE -> "Files"
            AtlasSearchTarget.CLASS -> "Classes"
            AtlasSearchTarget.SYMBOL -> "Symbols"
            AtlasSearchTarget.METHOD -> "Methods / functions"
            AtlasSearchTarget.EXTENSION -> "Extensions"
        }

private val AtlasSearchTarget.description: String
    get() =
        when (this) {
            AtlasSearchTarget.METHOD -> "SRCX currently records Kotlin functions and methods as Function symbols"
            AtlasSearchTarget.EXTENSION -> "Match a file extension such as kt, .kt, java, or kts"
            else -> "Limit the graph search to ${label.lowercase()}"
        }

private fun AtlasGraphController.searchTargetSummary(): String =
    when (searchTargets.size) {
        0 -> "Everything"
        1 -> searchTargets.single().label
        else -> "${searchTargets.size} search types"
    }

private fun AtlasGraphController.searchPlaceholder(): String =
    when {
        searchTargets.isEmpty() -> "Build, project, package, file, class, symbol, method, or extension..."
        searchTargets.size == 1 -> "Search ${searchTargets.single().label.lowercase()}..."
        else -> "Search the selected types..."
    }
