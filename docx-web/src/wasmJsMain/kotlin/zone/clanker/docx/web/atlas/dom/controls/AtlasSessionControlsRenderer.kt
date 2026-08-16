package zone.clanker.docx.web.atlas.dom.controls

import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLDivElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import zone.clanker.docx.web.atlas.AtlasController
import zone.clanker.docx.web.atlas.dom.AtlasDomActions
import zone.clanker.docx.web.atlas.dom.AtlasDomSurfaceModel
import zone.clanker.docx.web.atlas.dom.buttonElement
import zone.clanker.docx.web.atlas.dom.clearContent
import zone.clanker.docx.web.atlas.dom.requiredButton
import zone.clanker.docx.web.atlas.dom.requiredHtmlElement
import zone.clanker.docx.web.atlas.focusSemanticNode
import zone.clanker.docx.web.atlas.replaceLayers
import zone.clanker.docx.web.atlas.session.AtlasLayer
import zone.clanker.docx.web.atlas.session.AtlasOverlay
import zone.clanker.docx.web.atlas.session.AtlasOverlayChanged
import zone.clanker.docx.web.atlas.session.AtlasSemanticId
import zone.clanker.docx.web.atlas.toggleLayer

internal class AtlasSessionControlsRenderer {
    private var root: HTMLDivElement? = null

    fun render(
        root: HTMLDivElement,
        model: AtlasDomSurfaceModel,
        controller: AtlasController,
        actions: AtlasDomActions,
        onGraphCommand: (String) -> Unit,
    ) {
        this.root = root
        if (controller.session?.overlay == AtlasOverlay.Search) closeScopeSheet(root)
        renderLayerSheet(root, controller)
        renderFilterSheet(root, controller)
        renderHistory(root, model, controller, actions, onGraphCommand)
        root.onpointerdown = ::closeSheetOutside
        root.onkeydown = ::closeSheetOnEscape
    }

    fun release(root: HTMLDivElement) {
        root.onpointerdown = null
        root.onkeydown = null
        this.root = null
    }

    private fun renderLayerSheet(
        root: HTMLDivElement,
        controller: AtlasController,
    ) {
        val open = controller.session?.overlay == AtlasOverlay.Layers
        val toggle = root.requiredButton("[data-srcx-layers-toggle]")
        val sheet = root.requiredHtmlElement("[data-srcx-layers-sheet]")
        toggle.setAttribute("aria-expanded", open.toString())
        sheet.hidden = !open
        sheet.setAttribute("aria-hidden", (!open).toString())
        toggle.onclick = {
            closeScopeSheet(root)
            controller.dispatch(AtlasOverlayChanged(if (open) AtlasOverlay.Closed else AtlasOverlay.Layers))
            null
        }
        root.requiredButton("[data-srcx-layers-close]").onclick = {
            controller.dispatch(AtlasOverlayChanged(AtlasOverlay.Closed))
            toggle.focus()
            null
        }
        renderPresets(root, controller)
        renderLayerChoices(root, controller)
    }

    private fun renderFilterSheet(
        root: HTMLDivElement,
        controller: AtlasController,
    ) {
        val open = controller.session?.overlay == AtlasOverlay.RelationshipSettings
        val toggle = root.requiredButton("[data-srcx-filters-toggle]")
        val sheet = root.requiredHtmlElement("[data-srcx-filters-sheet]")
        toggle.setAttribute("aria-expanded", open.toString())
        sheet.hidden = !open
        sheet.setAttribute("aria-hidden", (!open).toString())
        toggle.onclick = {
            closeScopeSheet(root)
            controller.dispatch(
                AtlasOverlayChanged(if (open) AtlasOverlay.Closed else AtlasOverlay.RelationshipSettings),
            )
            null
        }
        root.requiredButton("[data-srcx-filters-close]").onclick = {
            controller.dispatch(AtlasOverlayChanged(AtlasOverlay.Closed))
            toggle.focus()
            null
        }
    }

    private fun renderPresets(
        root: HTMLDivElement,
        controller: AtlasController,
    ) {
        val host = root.requiredHtmlElement("[data-srcx-layer-presets]")
        host.clearContent()
        layerPresets.forEach { preset ->
            host.appendChild(
                layerButton(preset.label, controller.session?.layers?.enabled == preset.layers) {
                    controller.replaceLayers(preset.layers)
                },
            )
        }
    }

    private fun renderLayerChoices(
        root: HTMLDivElement,
        controller: AtlasController,
    ) {
        val host = root.requiredHtmlElement("[data-srcx-layer-options]")
        val enabled =
            controller.session
                ?.layers
                ?.enabled
                .orEmpty()
        host.clearContent()
        AtlasLayer.entries.forEach { layer ->
            host.appendChild(
                layerButton(layer.displayName, layer in enabled) {
                    controller.toggleLayer(layer)
                },
            )
        }
    }

    private fun renderHistory(
        root: HTMLDivElement,
        model: AtlasDomSurfaceModel,
        controller: AtlasController,
        actions: AtlasDomActions,
        onGraphCommand: (String) -> Unit,
    ) {
        val session = controller.session ?: return
        val backButton = root.requiredButton("[data-srcx-history-back]")
        val forwardButton = root.requiredButton("[data-srcx-history-forward]")
        backButton.historyAction(session.history.canNavigateBack) {
            actions.onNavigateBack()
            renderHistory(root, model, controller, actions, onGraphCommand)
        }
        forwardButton.historyAction(session.history.canNavigateForward) {
            actions.onNavigateForward()
            renderHistory(root, model, controller, actions, onGraphCommand)
        }
        root.setAttribute("data-docx-atlas-history-can-back", (!backButton.disabled).toString())
        root.setAttribute("data-docx-atlas-history-can-forward", (!forwardButton.disabled).toString())
        root.requiredButton("[data-srcx-history-home]").onclick = {
            controller.focusSemanticNode(null)
            onGraphCommand("fit")
            null
        }
        val breadcrumb = root.requiredHtmlElement("[data-srcx-history-breadcrumb]")
        breadcrumb.clearContent()
        breadcrumb.appendChild(
            breadcrumbButton(model.summary.workspace.name) {
                controller.focusSemanticNode(null)
                onGraphCommand("fit")
            },
        )
        session.focus.path.forEach { id ->
            breadcrumb.appendChild(
                breadcrumbButton(model.labelFor(id)) {
                    controller.focusSemanticNode(id.value)
                    onGraphCommand("fly-to-node:${id.value}")
                },
            )
        }
    }

    private fun closeSheetOutside(event: Event) {
        val target = event.target as? HTMLElement ?: return
        if (target.closest(MAP_SHEET_SELECTOR) != null) return
        val controllerRoot = root ?: return
        closeOpenMapSheet(controllerRoot)
    }

    private fun closeSheetOnEscape(event: KeyboardEvent) {
        if (event.key != "Escape") return
        val controllerRoot = root ?: return
        if (closeOpenMapSheet(controllerRoot)) {
            event.preventDefault()
            event.stopPropagation()
        }
    }
}

private const val MAP_SHEET_SELECTOR =
    "[data-srcx-layers-sheet], [data-srcx-layers-toggle], " +
        "[data-srcx-filters-sheet], [data-srcx-filters-toggle]"

private fun closeOpenMapSheet(root: HTMLDivElement): Boolean {
    val filters = root.requiredHtmlElement("[data-srcx-filters-sheet]")
    if (!filters.hidden) {
        root.requiredButton("[data-srcx-filters-close]").click()
        return true
    }
    val layers = root.requiredHtmlElement("[data-srcx-layers-sheet]")
    if (!layers.hidden) {
        root.requiredButton("[data-srcx-layers-close]").click()
        return true
    }
    return false
}

private fun layerButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
): HTMLButtonElement =
    buttonElement(label, selected, onClick).also { button ->
        button.className = "srcx-dashboard__architecture-layer-button"
        button.setAttribute("aria-label", "$label layer; ${if (selected) "shown" else "hidden"}")
    }

private fun breadcrumbButton(
    label: String,
    onClick: () -> Unit,
): HTMLButtonElement =
    buttonElement(label, false, onClick).also { button ->
        button.removeAttribute("aria-pressed")
        button.className = "srcx-dashboard__architecture-breadcrumb-button"
    }

private fun HTMLButtonElement.historyAction(
    enabled: Boolean,
    action: () -> Unit,
) {
    disabled = !enabled
    onclick = {
        action()
        null
    }
}

private fun closeScopeSheet(root: HTMLDivElement) {
    val open = root.querySelector("[data-srcx-scope-level-button][aria-expanded=\"true\"]") as? HTMLButtonElement
    open?.click()
}

private fun AtlasDomSurfaceModel.labelFor(id: AtlasSemanticId): String {
    val semanticLabel =
        slice
            ?.content
            ?.nodes
            ?.firstOrNull { node -> node.id == id.value }
            ?.presentation
            ?.label
    if (semanticLabel != null) return semanticLabel
    return summary.builds.firstOrNull { build -> build.id == id.value }?.name
        ?: summary.projects.firstOrNull { project -> project.id == id.value }?.path
        ?: summary.sourceSets.firstOrNull { sourceSet -> sourceSet.id == id.value }?.name
        ?: id.value
}

private data class LayerPreset(
    val label: String,
    val layers: List<AtlasLayer>,
)

private val layerPresets =
    listOf(
        LayerPreset("Focus", layers(AtlasLayer.CONTAINMENT, AtlasLayer.RELATIONSHIPS, AtlasLayer.LABELS)),
        LayerPreset(
            "Explore",
            layers(
                AtlasLayer.CONTAINMENT,
                AtlasLayer.FILES,
                AtlasLayer.SYMBOLS,
                AtlasLayer.PROBLEMS,
                AtlasLayer.RELATIONSHIPS,
                AtlasLayer.LABELS,
            ),
        ),
        LayerPreset("Everything", AtlasLayer.entries.toList()),
    )

private fun layers(vararg values: AtlasLayer): List<AtlasLayer> = values.sortedBy(AtlasLayer::ordinal)

private val AtlasLayer.displayName: String
    get() = name.lowercase().replaceFirstChar { character -> character.uppercase() }
