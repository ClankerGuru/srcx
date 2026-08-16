package zone.clanker.docx.web.atlas

import zone.clanker.docx.web.atlas.session.AtlasFiltersChanged
import zone.clanker.docx.web.atlas.session.AtlasRequestState
import zone.clanker.report.model.WorkspaceGraphFilter
import zone.clanker.report.model.WorkspaceGraphSlice

internal fun AtlasController.updateProjectFilter(value: String) {
    val current = session?.filters ?: return
    val updated =
        current.copy(
            projectQuery = value.take(WorkspaceGraphFilter.MAX_QUERY_LENGTH),
        )
    if (updated != current) dispatch(AtlasFiltersChanged(updated, recordHistory = false))
}

internal fun AtlasController.updateLayoutMode(value: AtlasLayoutMode) {
    layoutMode = value
}

internal fun AtlasController.updateFocusMap(value: Boolean) {
    focusMap = value
}

internal val AtlasController.settledGraphSlice: WorkspaceGraphSlice?
    get() = (session?.request as? AtlasRequestState.Settled)?.slice
