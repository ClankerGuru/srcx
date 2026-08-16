package zone.clanker.docx.web.atlas.dom.detail

import org.w3c.dom.HTMLDivElement
import org.w3c.dom.HTMLElement
import zone.clanker.docx.web.atlas.AtlasGraphController
import zone.clanker.docx.web.atlas.dom.AtlasDomSurfaceModel
import zone.clanker.docx.web.atlas.dom.clearContent
import zone.clanker.docx.web.atlas.dom.evidenceProjects
import zone.clanker.docx.web.atlas.dom.htmlElement
import zone.clanker.docx.web.atlas.dom.requiredButton
import zone.clanker.docx.web.atlas.dom.requiredElement
import zone.clanker.docx.web.atlas.dom.requiredHtmlElement
import zone.clanker.docx.web.evidence.WorkspaceEvidenceInspectionState
import zone.clanker.report.model.AtlasEdge
import zone.clanker.report.model.RelationshipSnapshot
import zone.clanker.report.model.SourceFileSnapshot
import zone.clanker.report.model.WorkspaceRelationshipOccurrence

internal data class AtlasDomDetailRenderer(
    val inspection: WorkspaceEvidenceInspectionState?,
    val pagedActions: AtlasPagedEvidenceActions,
)

internal fun renderAtlasDetail(
    root: HTMLDivElement,
    model: AtlasDomSurfaceModel,
    controller: AtlasGraphController,
    evidence: AtlasDomDetailRenderer,
    onClear: () -> Unit,
) {
    val detail = root.requiredHtmlElement("[data-srcx-detail]")
    val selectedNodeId = controller.selectedNodeId
    val selectedEdgeId = controller.selectedEdgeId
    val hasSelection = selectedNodeId != null || selectedEdgeId != null
    val evidenceMessage = controller.findingEvidenceMessage
    val hasDetail = hasSelection || evidenceMessage != null
    detail.hidden = !hasDetail
    detail.classList.toggle("is-open", hasDetail)
    root.requiredButton("[data-srcx-detail-close]").onclick = {
        onClear()
        null
    }
    if (!hasDetail) return
    val fields = root.requiredHtmlElement("[data-srcx-detail-fields]")
    fields.clearContent()
    if (!hasSelection) {
        renderUnavailableDetail(
            root,
            fields,
            message = requireNotNull(evidenceMessage),
            kicker = "Analyzer finding",
        )
        return
    }
    renderSelectedDetail(root, fields, model, controller, evidence)
}

private fun renderSelectedDetail(
    root: HTMLDivElement,
    fields: HTMLElement,
    model: AtlasDomSurfaceModel,
    controller: AtlasGraphController,
    evidence: AtlasDomDetailRenderer,
) {
    val projects = model.evidenceProjects()
    val selectedNodeId = controller.selectedNodeId
    val selectedEdgeId = controller.selectedEdgeId
    when {
        selectedNodeId != null -> {
            val project = projectForNode(projects, selectedNodeId)
            if (project == null) {
                renderOverviewNodeDetail(root, fields, model, selectedNodeId)
                val directFileRendered = appendOverviewFileSourceEvidence(fields, model, selectedNodeId)
                val exactSymbol =
                    (evidence.inspection as? WorkspaceEvidenceInspectionState.Symbol)
                        ?.takeIf { evidence -> evidence.symbolId == selectedNodeId }
                when {
                    exactSymbol != null -> appendExactSymbolEvidence(fields, model, exactSymbol, evidence.pagedActions)
                    !directFileRendered -> appendEvidenceLoadStatus(fields, model)
                }
            } else {
                renderNodeDetail(
                    AtlasSourceDetailRenderer(
                        root = root,
                        fields = fields,
                        model = model,
                        project = project,
                        evidence = evidence,
                    ),
                    selectedNodeId,
                )
            }
        }
        selectedEdgeId != null -> {
            val exactRelationship =
                (evidence.inspection as? WorkspaceEvidenceInspectionState.Relationship)
                    ?.takeIf { evidence -> evidence.edgeId == selectedEdgeId }
            if (exactRelationship != null) {
                renderOverviewEdgeDetail(root, fields, model, selectedEdgeId)
                appendExactRelationshipEvidence(fields, model, exactRelationship, evidence.pagedActions)
                if (exactRelationship.error == null || exactRelationship.page != null) return
            }
            val aggregate = selectedAggregate(model, selectedEdgeId)
            val relationshipIds = aggregate?.relationshipIds?.toSet().orEmpty() + selectedEdgeId
            val records = relationshipEvidenceRecords(projects, relationshipIds, selectedEdgeId)
            if (records.isEmpty()) {
                renderOverviewEdgeDetail(root, fields, model, selectedEdgeId)
                appendEvidenceLoadStatus(fields, model)
            } else {
                renderEdgeDetail(root, fields, model, aggregate, records)
            }
        }
        else -> renderUnavailableDetail(root, fields)
    }
    val findingProject = model.project ?: projects.firstOrNull()
    if (findingProject != null) {
        controller.findingEvidenceId?.let { findingId ->
            appendFindingEvidenceDetail(fields, findingProject, findingId)
        }
    }
}

private fun appendExactRelationshipEvidence(
    fields: HTMLElement,
    model: AtlasDomSurfaceModel,
    evidence: WorkspaceEvidenceInspectionState.Relationship,
    actions: AtlasPagedEvidenceActions,
) {
    appendExactEvidenceStatus(fields, evidence.loading, evidence.error)
    if (!evidence.loading && evidence.error == null && evidence.page == null) {
        fields.appendChild(detailList("Source occurrences" to "No matching records in this generation."))
    }
    evidence.page?.let { page ->
        appendPagedRelationshipEvidence(
            parent = fields,
            renderer =
                AtlasPagedEvidenceRenderer(
                    state = page,
                    file = { occurrence -> model.evidenceFile(occurrence) },
                    sourceContent = { occurrence -> model.evidenceSourceContent(occurrence) },
                    declarationRanges = { occurrence -> model.evidenceDeclarationRanges(occurrence) },
                    actions = actions,
                ),
        )
    }
}

internal fun appendExactEvidenceStatus(
    fields: HTMLElement,
    loading: Boolean,
    error: String?,
) {
    val message =
        when {
            loading -> "Loading generation-pinned exact evidence…"
            error != null -> "Exact evidence failed: $error"
            else -> return
        }
    fields.appendChild(detailList("Exact evidence" to message))
}

internal fun AtlasDomSurfaceModel.evidenceFile(occurrence: WorkspaceRelationshipOccurrence): SourceFileSnapshot? =
    evidenceProjects()
        .firstOrNull { project -> project.projectId == occurrence.location.projectId }
        ?.files
        ?.firstOrNull { file -> file.id == occurrence.location.fileId }

internal fun AtlasDomSurfaceModel.evidenceSourceContent(occurrence: WorkspaceRelationshipOccurrence): String? {
    val project =
        evidenceProjects().firstOrNull { candidate -> candidate.projectId == occurrence.location.projectId }
            ?: return null
    val file = project.files.firstOrNull { candidate -> candidate.id == occurrence.location.fileId } ?: return null
    return sourceContent(project.projectId, file)
}

internal fun AtlasDomSurfaceModel.evidenceDeclarationRanges(
    occurrence: WorkspaceRelationshipOccurrence,
) =
    evidenceProjects()
        .firstOrNull { project -> project.projectId == occurrence.location.projectId }
        ?.let { project -> declarationRanges(project, occurrence.location.fileId) }
        .orEmpty()

private fun appendEvidenceLoadStatus(
    fields: HTMLElement,
    model: AtlasDomSurfaceModel,
) {
    val message =
        when {
            model.evidence.loading -> "Loading the selected project's source evidence…"
            model.evidence.error != null -> "Source evidence failed: ${model.evidence.error}"
            else -> return
        }
    fields.appendChild(detailList("Evidence" to message))
}

private fun renderEdgeDetail(
    root: HTMLDivElement,
    fields: HTMLElement,
    model: AtlasDomSurfaceModel,
    aggregate: AtlasEdge?,
    records: List<RelationshipEvidenceRecord>,
) {
    val relationships = records.map(RelationshipEvidenceRecord::relationship)
    setDetailHeading(root, "Directed relationship", relationships.first().kind.label)
    fields.appendChild(edgeSummary(aggregate, relationships))
    appendRelationshipEvidence(
        parent = fields,
        records = records,
        sourceContent = { record -> model.sourceContent(record.projectId, record.sourceFile) },
        unavailableMessage = { record ->
            model.sourceUnavailableMessage(record.projectId, record.sourceFile)
        },
    )
}

private fun selectedAggregate(
    model: AtlasDomSurfaceModel,
    selectedEdgeId: String,
): AtlasEdge? =
    model.frame
        ?.edges
        ?.firstOrNull { edge -> edge.id == selectedEdgeId || selectedEdgeId in edge.relationshipIds }

private fun edgeSummary(
    aggregate: AtlasEdge?,
    records: List<RelationshipSnapshot>,
): HTMLElement {
    val section = htmlElement("section", "srcx-dashboard__architecture-detail-section")
    section.appendChild(htmlElement("h4", text = "Displayed route"))
    section.appendChild(
        detailList(
            "Relationship records" to records.size.toString(),
            "Predominant category" to (aggregate?.category?.name?.lowercase() ?: records.first().kind.label),
            "Direction" to "consumer/reference source → used declaration target",
            "Count meaning" to "serialized static-analysis records, not runtime calls or unique callers",
        ),
    )
    return section
}

internal fun setDetailHeading(
    root: HTMLDivElement,
    kicker: String,
    title: String,
) {
    root.requiredElement("[data-srcx-detail-kicker]").textContent = kicker
    root.requiredElement("[data-srcx-detail-title]").textContent = title
}

internal fun renderUnavailableDetail(
    root: HTMLDivElement,
    fields: HTMLElement,
    message: String = "The selected record is not present in the loaded typed project shard.",
    kicker: String = "Selection",
) {
    setDetailHeading(root, kicker, "Evidence unavailable")
    fields.appendChild(
        detailList("Status" to message),
    )
}
