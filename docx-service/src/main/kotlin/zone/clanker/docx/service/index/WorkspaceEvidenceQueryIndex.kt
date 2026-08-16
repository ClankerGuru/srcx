package zone.clanker.docx.service.index

import zone.clanker.report.model.WorkspaceDeclarationEvidencePage
import zone.clanker.report.model.WorkspaceDeclarationEvidenceRequest
import zone.clanker.report.model.WorkspaceRelationshipOccurrencePage
import zone.clanker.report.model.WorkspaceRelationshipOccurrenceRequest
import zone.clanker.report.model.WorkspaceReverseUsagePage
import zone.clanker.report.model.WorkspaceReverseUsageRequest

internal interface WorkspaceEvidenceQueryIndex {
    fun declarationEvidence(
        workspaceId: String,
        request: WorkspaceDeclarationEvidenceRequest,
    ): WorkspaceDeclarationEvidencePage

    fun reverseUsages(
        workspaceId: String,
        request: WorkspaceReverseUsageRequest,
    ): WorkspaceReverseUsagePage

    fun relationshipOccurrences(
        workspaceId: String,
        request: WorkspaceRelationshipOccurrenceRequest,
    ): WorkspaceRelationshipOccurrencePage
}
