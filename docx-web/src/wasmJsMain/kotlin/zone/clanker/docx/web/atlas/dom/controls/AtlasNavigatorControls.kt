package zone.clanker.docx.web.atlas.dom.controls

import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLDivElement
import zone.clanker.docx.web.atlas.dom.AtlasDomSurfaceModel
import zone.clanker.docx.web.atlas.dom.buttonElement
import zone.clanker.docx.web.atlas.dom.htmlElement
import zone.clanker.docx.web.atlas.dom.requiredButton
import zone.clanker.docx.web.atlas.dom.requiredElement
import zone.clanker.report.model.BuildSnapshot

internal data class FilterButtonSpec(
    val identity: FilterIdentity,
    val copy: FilterCopy,
    val state: FilterButtonState,
) {
    val compactDetail: String?
        get() = copy.compactDetail?.takeIf(String::isNotBlank)
}

internal data class FilterIdentity(
    val kind: String,
    val value: String?,
)

internal data class FilterCopy(
    val label: String,
    val detail: String,
    val compactDetail: String? =
        detail
            .substringBefore(';')
            .substringBefore('/')
            .trim(),
)

internal data class FilterButtonState(
    val color: String?,
    val selected: Boolean,
    val selectionMode: FilterSelectionMode,
)

internal enum class FilterSelectionMode {
    SINGLE,
    MULTIPLE,
}

internal fun filterButton(
    spec: FilterButtonSpec,
    onClick: () -> Unit,
): HTMLButtonElement {
    val button = buttonElement("", spec.state.selected, onClick)
    button.className = "srcx-dashboard__architecture-filter-button"
    button.setAttribute("data-srcx-filter-${spec.identity.kind}", spec.identity.value ?: "all")
    button.setAttribute("data-srcx-filter-search-text", "${spec.copy.label} ${spec.copy.detail}".lowercase())
    button.setAttribute("data-srcx-roving-item", "")
    button.setAttribute("aria-label", "${spec.copy.label}; ${spec.copy.detail}")
    button.title = spec.copy.detail
    if (spec.state.selectionMode == FilterSelectionMode.SINGLE) {
        button.removeAttribute("aria-pressed")
        button.setAttribute("role", "radio")
        button.setAttribute("aria-checked", spec.state.selected.toString())
    }
    spec.state.color?.let { button.style.setProperty("--srcx-build-color", it) }
    button.appendChild(
        htmlElement(
            "i",
            "srcx-dashboard__architecture-filter-swatch${if (spec.state.color == null) " is-all" else ""}",
        ).also { swatch -> swatch.setAttribute("aria-hidden", "true") },
    )
    val copy = htmlElement("span")
    copy.appendChild(htmlElement("strong", text = spec.copy.label))
    spec.compactDetail?.let { detail -> copy.appendChild(htmlElement("small", text = detail)) }
    button.appendChild(copy)
    return button
}

internal fun focusAndCloseScopeChooser(
    root: HTMLDivElement,
    kind: String,
) {
    root.requiredButton("[data-srcx-scope-level-button=\"$kind\"]").also { button ->
        button.focus()
        if (button.getAttribute("aria-expanded") == "true") button.click()
    }
}

internal fun updateScopeSelectionStatus(
    root: HTMLDivElement,
    kind: String,
    selectedCount: Int,
    availableCount: Int,
) {
    val status =
        when {
            availableCount == 0 -> "No options · multi-select"
            selectedCount == 0 -> "All $availableCount available · multi-select"
            else -> "$selectedCount of $availableCount selected · multi-select"
        }
    root.requiredElement("[data-srcx-$kind-filter-count]").textContent = status
    root.requiredButton("[data-srcx-scope-level-button=\"$kind\"]").also { button ->
        button.setAttribute("data-srcx-selected-count", selectedCount.toString())
        button.setAttribute("data-srcx-available-count", availableCount.toString())
    }
}

internal fun selectedBuild(model: AtlasDomSurfaceModel): BuildSnapshot? =
    model.summary.builds.firstOrNull { build -> build.id == model.selectedBuildId }

internal fun Int.projectsLabel(): String = if (this == 1) "project" else "projects"
