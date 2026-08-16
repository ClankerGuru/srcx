package zone.clanker.docx.web.atlas

import zone.clanker.docx.web.atlas.session.AtlasEvent
import zone.clanker.docx.web.atlas.session.AtlasEvidenceChanged
import zone.clanker.docx.web.atlas.session.AtlasEvidenceSelection
import zone.clanker.docx.web.atlas.session.AtlasInspectionCleared
import zone.clanker.docx.web.atlas.session.AtlasInspectionKind
import zone.clanker.docx.web.atlas.session.AtlasInspectionTarget
import zone.clanker.docx.web.atlas.session.AtlasPrimaryInspectionChanged
import zone.clanker.docx.web.atlas.session.AtlasSemanticId
import zone.clanker.docx.web.atlas.session.AtlasSession

internal interface AtlasGraphSelection {
    val selectedNodeId: String?
    val selectedEdgeId: String?
    val findingEvidenceId: String?
    val findingEvidenceMessage: String?

    fun selectNode(symbolId: String?)

    fun selectEdge(relationshipId: String?)

    fun clearSelection()
}

internal class AtlasGraphSelectionState(
    private val session: () -> AtlasSession?,
    private val dispatch: (AtlasEvent) -> Unit,
) : AtlasGraphSelection {
    override val selectedNodeId: String?
        get() = primaryId(AtlasInspectionKind.NODE)

    override val selectedEdgeId: String?
        get() =
            session()
                ?.inspection
                ?.primary
                ?.takeIf { target -> target.kind != AtlasInspectionKind.NODE }
                ?.id
                ?.value

    override val findingEvidenceId: String?
        get() =
            session()
                ?.inspection
                ?.evidence
                ?.target
                ?.id
                ?.value

    override val findingEvidenceMessage: String?
        get() =
            session()
                ?.inspection
                ?.evidence
                ?.message

    override fun selectNode(symbolId: String?) {
        select(symbolId?.inspectionTarget(AtlasInspectionKind.NODE))
    }

    override fun selectEdge(relationshipId: String?) {
        select(relationshipId?.inspectionTarget(AtlasInspectionKind.RELATION))
    }

    override fun clearSelection() {
        dispatch(AtlasInspectionCleared)
    }

    fun beginEvidence(findingId: String) {
        select(null, recordHistory = false)
        dispatch(
            AtlasEvidenceChanged(
                AtlasEvidenceSelection(
                    target = findingId.inspectionTarget(AtlasInspectionKind.FACT),
                    message = "Loading analyzer evidence…",
                ),
            ),
        )
    }

    fun focusEvidence(
        findingId: String,
        nodeId: String,
    ) {
        val nodeTarget = nodeId.inspectionTarget(AtlasInspectionKind.NODE)
        select(nodeTarget)
        dispatch(
            AtlasEvidenceChanged(
                AtlasEvidenceSelection(
                    target = findingId.inspectionTarget(AtlasInspectionKind.FACT),
                    occurrenceId = nodeTarget.id,
                ),
            ),
        )
    }

    fun evidenceUnavailable(
        findingId: String,
        message: String,
    ) {
        select(null, recordHistory = false)
        dispatch(
            AtlasEvidenceChanged(
                AtlasEvidenceSelection(
                    target = findingId.inspectionTarget(AtlasInspectionKind.FACT),
                    message = message,
                ),
            ),
        )
    }

    private fun primaryId(kind: AtlasInspectionKind): String? =
        session()
            ?.inspection
            ?.primary
            ?.takeIf { target -> target.kind == kind }
            ?.id
            ?.value

    private fun select(
        target: AtlasInspectionTarget?,
        recordHistory: Boolean = true,
    ) {
        dispatch(AtlasPrimaryInspectionChanged(target, recordHistory))
    }
}

private fun String.inspectionTarget(kind: AtlasInspectionKind): AtlasInspectionTarget =
    AtlasInspectionTarget(kind, AtlasSemanticId(this))
