package zone.clanker.docx.web.atlas.dom.search

import kotlinx.browser.document
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLDivElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement

internal data class AtlasGlobalSearchElements(
    val surface: HTMLDivElement,
    val input: HTMLInputElement,
    val prefixRail: HTMLDivElement,
    val selectionSummary: HTMLElement,
    val clearSelection: HTMLButtonElement,
    val status: HTMLElement,
    val results: HTMLDivElement,
)

internal fun createGlobalSearchElements(): AtlasGlobalSearchElements {
    val sequence = nextGlobalSearchSequence++
    val resultsId = "srcx-global-search-results-$sequence"
    val surface = divElement("srcx-global-search")
    surface.setAttribute("data-srcx-global-search", "")
    surface.setAttribute("role", "search")
    val input = document.createElement("input") as HTMLInputElement
    input.type = "search"
    input.className = "srcx-global-search__input"
    input.setAttribute("data-srcx-global-search-input", "")
    input.setAttribute("aria-label", "Search the entire workspace")
    input.setAttribute("aria-controls", resultsId)
    input.setAttribute("autocomplete", "off")
    input.placeholder = "Search everything, or use class:, method:, file:, ext:…"
    val prefixRail = divElement("srcx-global-search__prefixes")
    prefixRail.setAttribute("data-srcx-global-search-prefixes", "")
    prefixRail.setAttribute("role", "group")
    prefixRail.setAttribute("aria-label", "Search categories")
    val selectionSummary = element("output", "srcx-global-search__selection")
    selectionSummary.setAttribute("data-srcx-global-search-selection", "")
    val clearSelection = buttonElement("Clear selected")
    clearSelection.setAttribute("data-srcx-global-search-clear", "")
    val status = element("p", "srcx-global-search__status")
    status.setAttribute("data-srcx-global-search-status", "")
    status.setAttribute("aria-live", "polite")
    val results = divElement("srcx-global-search__results")
    results.id = resultsId
    results.setAttribute("data-srcx-global-search-results", "")
    results.setAttribute("aria-label", "Workspace search results")
    results.setAttribute("role", "listbox")
    results.setAttribute("aria-multiselectable", "true")
    surface.appendChild(input)
    surface.appendChild(prefixRail)
    surface.appendChild(selectionSummary)
    surface.appendChild(clearSelection)
    surface.appendChild(status)
    surface.appendChild(results)
    return AtlasGlobalSearchElements(
        surface = surface,
        input = input,
        prefixRail = prefixRail,
        selectionSummary = selectionSummary,
        clearSelection = clearSelection,
        status = status,
        results = results,
    )
}

internal fun divElement(className: String): HTMLDivElement =
    (document.createElement("div") as HTMLDivElement).also { element ->
        element.className = className
    }

internal fun element(
    tagName: String,
    className: String,
    text: String? = null,
): HTMLElement =
    (document.createElement(tagName) as HTMLElement).also { element ->
        element.className = className
        text?.let { element.textContent = it }
    }

internal fun buttonElement(label: String): HTMLButtonElement =
    (document.createElement("button") as HTMLButtonElement).also { button ->
        button.type = "button"
        button.textContent = label
    }

private var nextGlobalSearchSequence: Int = 1
