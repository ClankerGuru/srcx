package zone.clanker.docx.web.evidence

import zone.clanker.docx.web.site.WorkspaceEvidenceGateway
import zone.clanker.report.model.WorkspaceDeclarationEvidencePage
import zone.clanker.report.model.WorkspaceEvidenceLocation
import zone.clanker.report.model.WorkspaceRelationshipOccurrenceSelector

internal fun interface WorkspaceEvidenceGatewayProvider {
    fun current(): WorkspaceEvidenceGateway?
}

internal sealed interface WorkspaceEvidenceInspectionState {
    val loading: Boolean
    val error: String?

    data class Relationship(
        val edgeId: String,
        val selector: WorkspaceRelationshipOccurrenceSelector,
        val page: WorkspaceEvidencePageState? = null,
        override val loading: Boolean = true,
        override val error: String? = null,
    ) : WorkspaceEvidenceInspectionState

    data class Symbol(
        val symbolId: String,
        val declaration: WorkspaceDeclarationEvidencePage? = null,
        val usages: WorkspaceEvidencePageState? = null,
        override val loading: Boolean = true,
        override val error: String? = null,
    ) : WorkspaceEvidenceInspectionState
}

internal val WorkspaceEvidenceInspectionState.activeLocation: WorkspaceEvidenceLocation?
    get() =
        when (this) {
            is WorkspaceEvidenceInspectionState.Relationship -> page?.active?.location
            is WorkspaceEvidenceInspectionState.Symbol ->
                usages?.active?.location ?: declaration?.declaration?.location
        }
