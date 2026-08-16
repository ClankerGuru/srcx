package zone.clanker.docx.web.atlas.dom.controls

import org.w3c.dom.HTMLDivElement
import org.w3c.dom.events.KeyboardEvent
import zone.clanker.docx.web.atlas.dom.requiredButton
import zone.clanker.docx.web.atlas.dom.requiredElement
import zone.clanker.docx.web.atlas.dom.requiredHtmlElement
import zone.clanker.docx.web.atlas.dom.requiredInput

internal class AtlasScopeLevelSwitcher {
    private var activeLevel: AtlasScopeLevel? = null
    private var focusLevel = AtlasScopeLevel.BUILD

    fun render(root: HTMLDivElement) {
        wireOutsideClick(root)
        AtlasScopeLevel.entries.forEachIndexed { index, level ->
            val button = root.requiredButton(level.buttonSelector)
            button.requiredElement("span").textContent = level.label
            button.onclick = {
                toggle(root, level)
                null
            }
            button.onkeydown = { event ->
                val nextLevel = event.scopeNavigationLevel(index)
                if (nextLevel != null) {
                    event.preventDefault()
                    open(root, nextLevel, focusButton = true)
                } else if (event.key == "Escape") {
                    event.preventDefault()
                    close(root, focusButton = true)
                }
                null
            }
        }
        applyActiveLevel(root)
    }

    fun advanceTo(
        root: HTMLDivElement,
        level: AtlasScopeLevel,
    ) {
        open(root, level)
        root.requiredInput(level.searchSelector).focus()
    }

    private fun toggle(
        root: HTMLDivElement,
        level: AtlasScopeLevel,
    ) {
        closeMapSheet(root)
        focusLevel = level
        activeLevel = level.takeUnless { activeLevel == level }
        applyActiveLevel(root)
        if (activeLevel == level) root.requiredInput(level.searchSelector).focus()
    }

    private fun open(
        root: HTMLDivElement,
        level: AtlasScopeLevel,
        focusButton: Boolean = false,
    ) {
        closeMapSheet(root)
        focusLevel = level
        activeLevel = level
        applyActiveLevel(root)
        if (focusButton) root.requiredButton(level.buttonSelector).focus()
    }

    private fun close(
        root: HTMLDivElement,
        focusButton: Boolean = false,
    ) {
        val closingLevel = activeLevel ?: focusLevel
        focusLevel = closingLevel
        activeLevel = null
        applyActiveLevel(root)
        if (focusButton) root.requiredButton(closingLevel.buttonSelector).focus()
    }

    private fun wireOutsideClick(root: HTMLDivElement) {
        root.requiredHtmlElement("[data-srcx-graph-navigator]").onclick = { event ->
            event.stopPropagation()
            null
        }
        root.requiredHtmlElement("[data-srcx-scope-stepper]").onclick = { event ->
            event.stopPropagation()
            null
        }
        root.onclick = {
            if (activeLevel != null) close(root)
            null
        }
    }

    private fun applyActiveLevel(root: HTMLDivElement) {
        root.requiredHtmlElement("[data-srcx-graph-navigator]").also { navigator ->
            navigator.setAttribute("data-srcx-active-scope-level", activeLevel?.key ?: "none")
            navigator.setAttribute("data-srcx-scope-chooser-state", if (activeLevel == null) "closed" else "open")
        }
        AtlasScopeLevel.entries.forEach { level ->
            val active = level == activeLevel
            root.requiredButton(level.buttonSelector).also { button ->
                button.setAttribute("aria-expanded", active.toString())
                button.tabIndex = if (level == focusLevel) 0 else -1
                button.title = if (active) "Close ${level.label}" else "Open ${level.label}"
            }
            root.requiredHtmlElement(level.panelSelector).also { panel ->
                panel.hidden = !active
                panel.setAttribute("aria-hidden", (!active).toString())
                panel.setAttribute("data-srcx-scope-panel-state", if (active) "expanded" else "collapsed")
            }
        }
    }
}

private fun closeMapSheet(root: HTMLDivElement) {
    val openSheet =
        listOf(
            "[data-srcx-layers-sheet]" to "[data-srcx-layers-close]",
            "[data-srcx-filters-sheet]" to "[data-srcx-filters-close]",
        ).firstOrNull { (sheetSelector, _) -> !root.requiredHtmlElement(sheetSelector).hidden }
    openSheet?.let { (_, closeSelector) -> root.requiredButton(closeSelector).click() }
}

internal enum class AtlasScopeLevel(
    val key: String,
    val label: String,
) {
    BUILD("build", "Builds"),
    PROJECT("project", "Projects"),
    SOURCE_SET("source-set", "Source sets"),
    ;

    val buttonSelector: String
        get() = "[data-srcx-scope-level-button=\"$key\"]"

    val panelSelector: String
        get() = "[data-srcx-scope-control=\"$key\"]"

    val searchSelector: String
        get() = "[data-srcx-$key-filter-search]"
}

private fun KeyboardEvent.scopeNavigationLevel(current: Int): AtlasScopeLevel? =
    when (key) {
        "ArrowRight", "ArrowDown" -> AtlasScopeLevel.entries[(current + 1) % AtlasScopeLevel.entries.size]
        "ArrowLeft", "ArrowUp" ->
            AtlasScopeLevel.entries[(current - 1 + AtlasScopeLevel.entries.size) % AtlasScopeLevel.entries.size]
        "Home" -> AtlasScopeLevel.entries.first()
        "End" -> AtlasScopeLevel.entries.last()
        else -> null
    }
