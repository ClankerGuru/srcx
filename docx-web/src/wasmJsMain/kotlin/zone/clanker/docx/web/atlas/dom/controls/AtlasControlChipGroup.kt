package zone.clanker.docx.web.atlas.dom.controls

import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLDivElement
import org.w3c.dom.events.KeyboardEvent
import zone.clanker.docx.web.atlas.dom.requiredButton

internal fun wireControlChipGroup(
    root: HTMLDivElement,
    buttons: List<HTMLButtonElement>,
) {
    val selectedIndex =
        buttons.indexOfFirst { button ->
            !button.disabled && button.getAttribute("aria-pressed") == "true"
        }
    val initialIndex =
        selectedIndex.takeIf { index -> index >= 0 }
            ?: buttons.indexOfFirst { button -> !button.disabled }.coerceAtLeast(0)
    buttons.forEachIndexed { index, button ->
        button.tabIndex = if (index == initialIndex) 0 else -1
        button.onkeydown = { event ->
            val nextIndex = event.controlNavigationIndex(index, buttons.size)
            if (nextIndex != null) {
                event.preventDefault()
                buttons.forEachIndexed { candidateIndex, candidate ->
                    candidate.tabIndex = if (candidateIndex == nextIndex) 0 else -1
                }
                buttons[nextIndex].focus()
            } else if (event.key == "Escape") {
                event.preventDefault()
                root.requiredButton("[data-srcx-chart-controls-toggle]").focus()
            }
            null
        }
    }
}

private fun KeyboardEvent.controlNavigationIndex(
    current: Int,
    size: Int,
): Int? =
    when (key) {
        "ArrowRight", "ArrowDown" -> (current + 1) % size
        "ArrowLeft", "ArrowUp" -> (current - 1 + size) % size
        "Home" -> 0
        "End" -> size - 1
        else -> null
    }
