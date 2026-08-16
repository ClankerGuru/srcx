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
import zone.clanker.docx.web.atlas.projection.AtlasRelationshipDirection

internal class AtlasRelationshipCountControlsRenderer {
    private var input: HTMLInputElement? = null
    private var controller: AtlasGraphController? = null
    private val inputListener: (Event) -> Unit = {
        controller?.updateRelationshipCountMoreThan(input?.nonNegativeIntOrNull())
    }

    fun render(
        root: HTMLDivElement,
        graph: AtlasGraphController,
        enabled: Boolean,
    ) {
        renderInput(root, graph, enabled)
        renderPresets(root, graph, enabled)
        renderDirections(root, graph, enabled)
        root.requiredElement("[data-srcx-relationship-count-summary]").textContent = graph.countFilterSummary()
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
        val nextInput = root.requiredInput("[data-srcx-relationship-count-input]")
        if (input !== nextInput) {
            input?.removeEventListener("input", inputListener)
            input = nextInput
            nextInput.addEventListener("input", inputListener)
        }
        controller = graph
        nextInput.disabled = !enabled
        val nextValue = graph.relationshipCountMoreThan?.toString().orEmpty()
        if (nextInput.value != nextValue) nextInput.value = nextValue
    }

    private fun renderPresets(
        root: HTMLDivElement,
        graph: AtlasGraphController,
        enabled: Boolean,
    ) {
        val rail = root.requiredHtmlElement("[data-srcx-relationship-count-presets]")
        rail.clearContent()
        val buttons =
            COUNT_PRESETS.map { preset ->
                buttonElement(
                    label = preset.label,
                    pressed = graph.relationshipCountMoreThan == preset.moreThan,
                ) {
                    graph.updateRelationshipCountMoreThan(preset.moreThan)
                }.also { button ->
                    button.className = "srcx-dashboard__architecture-count-filter-button"
                    button.setAttribute("data-srcx-relationship-count-preset", preset.attributeValue)
                    button.disabled = !enabled
                    button.title = preset.description
                }
            }
        buttons.forEach { button -> rail.appendChild(button) }
        wireControlChipGroup(root, buttons)
    }

    private fun renderDirections(
        root: HTMLDivElement,
        graph: AtlasGraphController,
        enabled: Boolean,
    ) {
        val rail = root.requiredHtmlElement("[data-srcx-relationship-direction-options]")
        rail.clearContent()
        val buttons =
            AtlasRelationshipDirection.entries.map { direction ->
                buttonElement(
                    label = direction.label,
                    pressed = graph.relationshipDirection == direction,
                ) {
                    graph.updateRelationshipDirection(direction)
                }.also { button ->
                    button.className = "srcx-dashboard__architecture-direction-filter-button"
                    button.setAttribute("data-srcx-relationship-direction", direction.name.lowercase())
                    button.disabled = !enabled
                    button.title = direction.description
                }
            }
        buttons.forEach { button -> rail.appendChild(button) }
        wireControlChipGroup(root, buttons)
    }
}

private data class CountPreset(
    val moreThan: Int?,
    val label: String,
) {
    val attributeValue: String
        get() = moreThan?.toString() ?: "any"

    val description: String
        get() = moreThan?.let { count -> "Show nodes with more than $count relationships" } ?: "Show any count"
}

private fun HTMLInputElement.nonNegativeIntOrNull(): Int? = value.trim().toIntOrNull()?.takeIf { it >= 0 }

private fun AtlasGraphController.countFilterSummary(): String =
    relationshipCountMoreThan?.let { count -> ">$count · ${relationshipDirection.label}" } ?: "Any count"

private val AtlasRelationshipDirection.label: String
    get() =
        when (this) {
            AtlasRelationshipDirection.ANY -> "Any"
            AtlasRelationshipDirection.OUTGOING -> "Uses"
            AtlasRelationshipDirection.INCOMING -> "Used by"
        }

private val AtlasRelationshipDirection.description: String
    get() =
        when (this) {
            AtlasRelationshipDirection.ANY -> "Count every incoming or outgoing relationship once"
            AtlasRelationshipDirection.OUTGOING -> "Count relationships where this node depends on another node"
            AtlasRelationshipDirection.INCOMING -> "Count relationships where another node depends on this node"
        }

private val COUNT_PRESETS =
    listOf(
        CountPreset(null, "Any"),
        CountPreset(5, ">5"),
        CountPreset(10, ">10"),
        CountPreset(25, ">25"),
    )
