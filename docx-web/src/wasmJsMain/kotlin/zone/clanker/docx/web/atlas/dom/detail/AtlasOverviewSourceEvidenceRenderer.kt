package zone.clanker.docx.web.atlas.dom.detail

import org.w3c.dom.HTMLElement
import zone.clanker.docx.web.atlas.dom.AtlasDomSurfaceModel
import zone.clanker.report.model.AtlasNode
import zone.clanker.report.model.AtlasNodeType
import zone.clanker.report.model.SourceFileSnapshot
import zone.clanker.report.model.SourceLanguage

internal fun appendOverviewFileSourceEvidence(
    fields: HTMLElement,
    model: AtlasDomSurfaceModel,
    nodeId: String,
): Boolean {
    val spec = model.overviewFileSourceSpec(nodeId) ?: return false
    appendSourceEvidence(fields, spec)
    return true
}

private fun AtlasDomSurfaceModel.overviewFileSourceSpec(nodeId: String): SourceEvidenceSpec? =
    overviewFileTarget(nodeId)?.sourceSpec(this)

private fun AtlasDomSurfaceModel.overviewFileTarget(nodeId: String): OverviewFileTarget? {
    val node = frame?.nodes?.firstOrNull { candidate -> candidate.id == nodeId } ?: return null
    return node.takeIf { candidate -> candidate.type == AtlasNodeType.FILE }?.toOverviewFileTarget(this)
}

private fun AtlasNode.toOverviewFileTarget(model: AtlasDomSurfaceModel): OverviewFileTarget? {
    val projectId = projectId ?: return null
    return model.summary.sourceSets
        .firstOrNull { sourceSet -> sourceSet.projectId == projectId && sourceSet.name == this.sourceSet }
        ?.let { sourceSet -> OverviewFileTarget(this, projectId, sourceSet.id) }
}

private fun OverviewFileTarget.sourceSpec(model: AtlasDomSurfaceModel): SourceEvidenceSpec? {
    val path = node.path ?: return null
    val file =
        SourceFileSnapshot(
            id = node.id,
            sourceSetId = sourceSetId,
            projectRelativePath = path,
            language = node.sourceLanguage(),
        )
    return SourceEvidenceSpec(
        file = file,
        content = model.evidence.source.contentFor(projectId, file.id),
        unavailableMessage = model.directSourceUnavailableMessage(projectId, file.id),
    )
}

private fun AtlasNode.sourceLanguage(): SourceLanguage =
    SourceLanguage.entries.firstOrNull { language ->
        language.name.equals(this.language, ignoreCase = true) ||
            language.label.equals(this.language, ignoreCase = true)
    } ?: SourceLanguage.OTHER

private fun AtlasDomSurfaceModel.directSourceUnavailableMessage(
    projectId: String,
    fileId: String,
): String =
    when {
        evidence.source.matches(projectId, fileId) && evidence.source.loading -> "Loading the selected source file…"
        evidence.source.errorFor(projectId, fileId) != null ->
            "Source evidence failed: ${evidence.source.errorFor(projectId, fileId)}"
        else -> "Source text was not included for this bounded file."
    }

private data class OverviewFileTarget(
    val node: AtlasNode,
    val projectId: String,
    val sourceSetId: String,
)
