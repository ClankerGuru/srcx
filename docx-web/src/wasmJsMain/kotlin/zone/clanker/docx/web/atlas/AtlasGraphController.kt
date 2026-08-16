package zone.clanker.docx.web.atlas

import androidx.compose.runtime.Stable
import zone.clanker.docx.web.atlas.session.AtlasEvent
import zone.clanker.docx.web.atlas.session.AtlasSession

@Stable
internal class AtlasGraphController private constructor(
    private val parts: AtlasGraphParts,
) : AtlasGraphFilters by parts.filters,
    AtlasGraphSelection by parts.selection,
    AtlasGraphFrameState by parts.frame,
    AtlasGraphViewport by parts.viewport,
    AtlasGraphFocus by parts.focus {
    constructor(
        session: () -> AtlasSession?,
        dispatch: (AtlasEvent) -> Unit,
    ) : this(AtlasGraphParts(session, dispatch))

    fun onSessionChanged(
        previous: AtlasSession?,
        current: AtlasSession,
    ) {
        when {
            previous == null ||
                previous.generation != current.generation ||
                previous.hierarchy != current.hierarchy ||
                previous.scope != current.scope -> parts.frame.invalidateScope()
            previous.filters != current.filters || previous.layers != current.layers -> parts.frame.resetFrame()
            previous.focus != current.focus -> parts.frame.invalidateFocus()
        }
    }
}

private class AtlasGraphParts(
    session: () -> AtlasSession?,
    dispatch: (AtlasEvent) -> Unit,
) {
    val selection = AtlasGraphSelectionState(session, dispatch)
    val frame = AtlasGraphFrameController()
    val filters = AtlasGraphFilterController(session, dispatch)
    val viewport = AtlasGraphViewportController(session, dispatch)
    val focus = AtlasGraphFocusController(filters, selection)
}
