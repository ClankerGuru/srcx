package zone.clanker.docx.web.atlas.dom.search

import zone.clanker.report.model.WorkspaceSearchEntry

internal data class AtlasGlobalSearchActions(
    val onQueryChanged: (String) -> Unit,
    val onActiveResultChanged: (String?) -> Unit,
    val onResultToggled: (WorkspaceSearchEntry) -> Unit,
    val onSelectionCleared: () -> Unit,
    val onTargetRequested: (WorkspaceSearchEntry) -> Unit,
    val onOpened: () -> Unit,
    val onDismissed: () -> Unit,
)

internal data class AtlasGlobalSearchRenderModel(
    val state: zone.clanker.docx.web.state.WorkspaceGlobalSearchState,
    val selectedNodeIds: Set<String>,
    val actions: AtlasGlobalSearchActions,
    val expanded: Boolean,
)
