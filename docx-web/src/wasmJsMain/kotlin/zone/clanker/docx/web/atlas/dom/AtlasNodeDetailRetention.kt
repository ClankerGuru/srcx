package zone.clanker.docx.web.atlas.dom

import zone.clanker.docx.web.atlas.dom.detail.projectForNode
import zone.clanker.docx.web.atlas.dom.detail.sourceContent
import zone.clanker.docx.web.evidence.WorkspaceEvidenceInspectionState
import zone.clanker.docx.web.state.EvidenceSourceContentState
import zone.clanker.report.model.ProjectGraphShard
import zone.clanker.report.model.SourceFileSnapshot

internal class AtlasNodeDetailRetention {
    private var retained: RetainedNodeDetail? = null

    fun matches(
        model: AtlasDomSurfaceModel,
        selection: GraphSelection,
        exactEvidence: WorkspaceEvidenceInspectionState?,
    ): Boolean {
        val project = model.displayProject(selection)
        val current = retained ?: return false
        return current.selection == selection &&
            current.project === project &&
            current.source === model.displaySource(selection) &&
            current.exactEvidence === exactEvidence
    }

    fun record(
        model: AtlasDomSurfaceModel,
        selection: GraphSelection,
        exactEvidence: WorkspaceEvidenceInspectionState?,
    ) {
        retained =
            RetainedNodeDetail(
                selection,
                model.displayProject(selection),
                model.displaySource(selection),
                exactEvidence,
            )
    }

    fun clear() {
        retained = null
    }
}

private fun AtlasDomSurfaceModel.displaySource(selection: GraphSelection): EvidenceSourceContentState? =
    evidence.source.takeIf { source ->
        source.requestedFileId == selection.nodeId && (source.content != null || source.error != null)
    }

private fun AtlasDomSurfaceModel.detailProject(selection: GraphSelection): ProjectGraphShard? =
    selection.nodeId?.let { nodeId -> projectForNode(evidenceProjects(), nodeId) }

private fun AtlasDomSurfaceModel.displayProject(selection: GraphSelection): ProjectGraphShard? {
    val project = detailProject(selection) ?: return null
    val file = project.selectedFile(selection.nodeId) ?: return project
    val hasLazySource = project.sourceContents.any { source -> source.fileId == file.id }
    val sourcePending =
        hasLazySource &&
            sourceContent(project.projectId, file) == null &&
            evidence.source.errorFor(project.projectId, file.id) == null
    return project.takeUnless { sourcePending }
}

private fun ProjectGraphShard.selectedFile(nodeId: String?): SourceFileSnapshot? {
    val selectedId = nodeId ?: return null
    return files.firstOrNull { file -> file.id == selectedId }
        ?: symbols
            .firstOrNull { symbol -> symbol.id == selectedId }
            ?.let { symbol -> files.firstOrNull { file -> file.id == symbol.fileId } }
}

private class RetainedNodeDetail(
    val selection: GraphSelection,
    val project: ProjectGraphShard?,
    val source: EvidenceSourceContentState?,
    val exactEvidence: WorkspaceEvidenceInspectionState?,
)
