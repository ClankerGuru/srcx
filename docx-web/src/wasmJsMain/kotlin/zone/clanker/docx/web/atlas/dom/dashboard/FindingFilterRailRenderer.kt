package zone.clanker.docx.web.atlas.dom.dashboard

import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLDivElement
import org.w3c.dom.HTMLElement
import zone.clanker.docx.web.atlas.dom.buttonElement
import zone.clanker.docx.web.atlas.dom.clearContent
import zone.clanker.docx.web.atlas.dom.htmlElement
import zone.clanker.docx.web.atlas.dom.requiredHtmlElement

internal class FindingFilterRailRenderer {
    fun render(
        root: HTMLDivElement,
        spec: FindingFilterRail,
    ) {
        val rail =
            root.requiredHtmlElement(
                "[data-srcx-finding-filter-group=\"${spec.kind}\"] [data-srcx-finding-filter-rail]",
            )
        rail.clearContent()
        val total = spec.items.sumOf(FindingFilterItem::count)
        val buttons =
            listOf(FindingFilterItem(id = null, label = spec.allLabel, detail = null, count = total)) + spec.items
        buttons.forEach { item ->
            val selected = item.id == spec.selectedId
            rail.appendChild(filterButton(spec.kind, item, selected) { spec.onSelected(item.id) })
        }
        wireRail(rail)
    }

    private fun filterButton(
        kind: String,
        item: FindingFilterItem,
        selected: Boolean,
        onClick: () -> Unit,
    ): HTMLButtonElement =
        buttonElement("", selected, onClick).also { button ->
            button.setAttribute("data-srcx-finding-filter", kind)
            button.setAttribute("data-srcx-finding-filter-value", item.id ?: "all")
            button.setAttribute("data-srcx-finding-filter-count", item.count.toString())
            button.setAttribute("data-srcx-roving-item", "")
            button.setAttribute("aria-controls", "srcx-finding-list")
            button.setAttribute(
                "aria-label",
                "${item.label}${item.detail?.let { ", build $it" } ?: ""}, " +
                    "${item.count} ${item.count.dashboardPlural("finding")}",
            )
            button.tabIndex = if (selected) 0 else -1
            button.appendChild(htmlElement("span", text = item.label))
            item.detail?.let { detail -> button.appendChild(htmlElement("small", text = detail)) }
            button.appendChild(htmlElement("b", text = item.count.toString()))
        }

    private fun wireRail(rail: HTMLElement) {
        val buttons =
            (0 until rail.children.length).mapNotNull { index -> rail.children.item(index) as? HTMLButtonElement }
        buttons.forEachIndexed { index, button ->
            button.onkeydown = { event ->
                val nextIndex = event.dashboardRovingIndex(index, buttons.size)
                if (nextIndex != null) {
                    event.preventDefault()
                    buttons.forEachIndexed { candidateIndex, candidate ->
                        candidate.tabIndex = if (candidateIndex == nextIndex) 0 else -1
                    }
                    buttons[nextIndex].focus()
                }
                null
            }
        }
    }
}
