package zone.clanker.docx.web.atlas

import zone.clanker.docx.web.atlas.session.AtlasRequestState
import zone.clanker.report.model.WorkspaceGraphSlice

internal data class AtlasSemanticMapDelivery(
    val requestRevision: Long,
    val slice: WorkspaceGraphSlice,
    val payload: String,
)

internal fun AtlasController.semanticMapDelivery(): AtlasSemanticMapDelivery? {
    val currentSession = session ?: return null
    val settled = currentSession.request as? AtlasRequestState.Settled ?: return null
    return AtlasSemanticMapDelivery(
        requestRevision = settled.revision,
        slice = settled.slice,
        payload =
            atlasMapPayload(
                slice = settled.slice,
                layoutOverrides = currentSession.layout.overrides,
                automaticExpandedIds = currentSession.focus.automaticExpandedIds,
            ),
    )
}
