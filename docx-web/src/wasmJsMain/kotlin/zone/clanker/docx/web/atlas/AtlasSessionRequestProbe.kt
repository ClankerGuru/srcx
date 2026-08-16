package zone.clanker.docx.web.atlas

import zone.clanker.docx.web.atlas.session.AtlasRequestState
import zone.clanker.docx.web.atlas.session.AtlasSemanticId
import zone.clanker.report.model.WorkspaceGraphRequest

internal data class AtlasSessionRequestProbe(
    val status: String,
    val revision: String,
    val error: String?,
    val requestExpandedNodeIds: String,
    val focusExpandedNodeIds: String,
)

internal data class AtlasSemanticOpenProbe(
    val hierarchyKnown: Boolean,
    val expanded: Boolean,
    val request: AtlasSessionRequestProbe,
)

internal fun AtlasController.openSemanticNodeWithProbe(nodeId: String): AtlasSemanticOpenProbe {
    val semanticId = AtlasSemanticId(nodeId)
    val hierarchyKnown = session?.hierarchy?.contains(semanticId) == true
    openSemanticNode(nodeId)
    return AtlasSemanticOpenProbe(
        hierarchyKnown = hierarchyKnown,
        expanded = semanticId in session?.focus?.effectiveExpandedIds.orEmpty(),
        request = sessionRequestProbe(),
    )
}

internal fun AtlasController.sessionRequestProbe(): AtlasSessionRequestProbe {
    val requestState = session?.request
    return AtlasSessionRequestProbe(
        status = requestState.statusName,
        revision = requestState?.revision?.toString().orEmpty(),
        error = (requestState as? AtlasRequestState.Failed)?.message,
        requestExpandedNodeIds =
            requestState.workspaceRequest
                ?.view
                ?.selection
                ?.expandedNodeIds
                .orEmpty()
                .joinToString("|"),
        focusExpandedNodeIds =
            session
                ?.focus
                ?.effectiveExpandedIds
                .orEmpty()
                .joinToString("|") { id -> id.value },
    )
}

private val AtlasRequestState?.statusName: String
    get() =
        when (this) {
            null -> "unavailable"
            is AtlasRequestState.Idle -> "idle"
            is AtlasRequestState.Pending -> "pending"
            is AtlasRequestState.Settled -> "settled"
            is AtlasRequestState.Failed -> "failed"
            is AtlasRequestState.Cancelled -> "cancelled"
        }

private val AtlasRequestState?.workspaceRequest: WorkspaceGraphRequest?
    get() =
        when (this) {
            is AtlasRequestState.Pending -> request
            is AtlasRequestState.Settled -> request
            is AtlasRequestState.Failed -> request
            is AtlasRequestState.Cancelled -> request
            is AtlasRequestState.Idle,
            null,
            -> null
        }
