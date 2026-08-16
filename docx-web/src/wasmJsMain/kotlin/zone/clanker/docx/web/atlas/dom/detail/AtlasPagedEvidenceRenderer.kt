package zone.clanker.docx.web.atlas.dom.detail

import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import zone.clanker.docx.web.atlas.dom.htmlElement
import zone.clanker.docx.web.evidence.WorkspaceEvidencePageState
import zone.clanker.report.model.SourceFileSnapshot
import zone.clanker.report.model.SourceRangeSnapshot
import zone.clanker.report.model.WorkspaceRelationshipOccurrence

internal data class AtlasPagedEvidenceRenderer(
    val state: WorkspaceEvidencePageState,
    val file: (WorkspaceRelationshipOccurrence) -> SourceFileSnapshot?,
    val sourceContent: (WorkspaceRelationshipOccurrence) -> String?,
    val declarationRanges: (WorkspaceRelationshipOccurrence) -> List<SourceRangeSnapshot>,
    val actions: AtlasPagedEvidenceActions,
)

/** Bounded exact-evidence page; Previous/Next may keyset-load adjacent pages through the supplied actions. */
internal fun appendPagedRelationshipEvidence(
    parent: HTMLElement,
    renderer: AtlasPagedEvidenceRenderer,
) {
    val state = renderer.state
    val section = htmlElement("section", "srcx-dashboard__architecture-detail-section")
    section.appendChild(htmlElement("h4", text = "Source occurrences"))
    section.appendChild(pagedEvidenceControls(state, renderer.actions))
    section.appendChild(usageCategorySummary(state))
    section.appendChild(pagedOccurrenceList(state, renderer.actions))
    section.appendChild(pagedOccurrenceMetadata(state.active))
    val sourceFile = renderer.file(state.active)
    if (sourceFile == null) {
        section.appendChild(
            htmlElement(
                "p",
                "srcx-dashboard__architecture-muted",
                "Loading the active occurrence's bounded source file…",
            ),
        )
    } else {
        appendSourceEvidence(
            section,
            SourceEvidenceSpec(
                file = sourceFile,
                content = renderer.sourceContent(state.active),
                unavailableMessage = "The active occurrence's source text is unavailable.",
                declarationRanges = renderer.declarationRanges(state.active),
                activeLine = state.active.location.line,
                activeRange = state.active.location.range,
                activeKind = SourceEvidenceKind.RELATIONSHIP,
                relationshipLineCounts =
                    state.occurrences
                        .filter { occurrence -> occurrence.location.fileId == state.active.location.fileId }
                        .groupingBy { occurrence -> occurrence.location.line }
                        .eachCount(),
            ),
        )
    }
    parent.appendChild(section)
}

private fun pagedOccurrenceList(
    state: WorkspaceEvidencePageState,
    actions: AtlasPagedEvidenceActions,
): HTMLElement {
    val list = htmlElement("ol", "srcx-dashboard__architecture-occurrence-list")
    list.setAttribute("aria-label", "Loaded exact relationship occurrences")
    state.occurrences.forEachIndexed { index, occurrence ->
        val item = htmlElement("li", "srcx-dashboard__architecture-occurrence-list-item")
        val button = htmlElement("button", "srcx-dashboard__architecture-occurrence-row") as HTMLButtonElement
        button.type = "button"
        button.classList.toggle("is-active", index == state.activeIndex)
        if (index == state.activeIndex) button.setAttribute("aria-current", "true")
        button.textContent =
            "${state.pageStartIndex + index + 1}. ${occurrence.location.filePath}:" +
            "${occurrence.location.line} · ${occurrence.kind.label} · " +
            occurrence.category.name
                .lowercase()
                .replace('_', ' ')
        button.onclick = {
            actions.onSelect(index)
            null
        }
        item.appendChild(button)
        list.appendChild(item)
    }
    return list
}

private fun pagedEvidenceControls(
    state: WorkspaceEvidencePageState,
    actions: AtlasPagedEvidenceActions,
): HTMLElement {
    val controls = htmlElement("div", "srcx-dashboard__architecture-occurrence-controls")
    val previous = htmlElement("button", text = "Previous") as HTMLButtonElement
    previous.type = "button"
    previous.disabled = !state.canMovePrevious
    previous.onclick = {
        actions.onPrevious()
        null
    }
    val status =
        htmlElement(
            "p",
            "srcx-dashboard__architecture-occurrence-status",
            "Record ${state.absoluteActiveIndex + 1} of ${state.totalCount} / " +
                "${state.active.location.filePath}:${state.active.location.line}",
        )
    status.setAttribute("role", "status")
    status.setAttribute("aria-live", "polite")
    val next = htmlElement("button", text = "Next") as HTMLButtonElement
    next.type = "button"
    next.disabled = !state.canMoveNext
    next.onclick = {
        actions.onNext()
        null
    }
    controls.appendChild(previous)
    controls.appendChild(status)
    controls.appendChild(next)
    return controls
}

private fun usageCategorySummary(state: WorkspaceEvidencePageState): HTMLElement {
    val section = htmlElement("section", "srcx-dashboard__architecture-usage-categories")
    section.appendChild(htmlElement("h5", text = "Usage categories in this page"))
    state.categorized().forEach { usage ->
        section.appendChild(
            htmlElement(
                "p",
                "srcx-dashboard__architecture-usage-category",
                "${usage.category.name.lowercase().replace('_', ' ')} · ${usage.occurrences.size}",
            ),
        )
    }
    return section
}

private fun pagedOccurrenceMetadata(occurrence: WorkspaceRelationshipOccurrence): HTMLElement =
    detailList(
        "Direction" to "consumer/reference source → used declaration target",
        "Kind" to occurrence.kind.label,
        "Category" to
            occurrence.category.name
                .lowercase()
                .replace('_', ' '),
        "Evidence" to occurrence.evidence.label,
        "Source" to "${occurrence.location.filePath}:${occurrence.location.line}",
        "Source context" to occurrence.context,
    )
