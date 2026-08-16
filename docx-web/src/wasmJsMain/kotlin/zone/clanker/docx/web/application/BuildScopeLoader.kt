package zone.clanker.docx.web.application

import zone.clanker.docx.web.atlas.projection.AtlasScopeSelection
import zone.clanker.docx.web.state.ViewerState

internal fun settleSelectedScopeWithoutProjectShards(
    state: ViewerState.Ready,
    selection: AtlasScopeSelection,
): ViewerState.Ready =
    state.copy(
        selectedProjectId = selection.projectIds.singleOrNull(),
        project = null,
        projectLoading = false,
        projectError = null,
        loadedProjectIds = emptySet(),
        loadedBuildId = selection.buildIds.singleOrNull().takeIf { selection.projectIds.isEmpty() },
        buildProjects = emptyList(),
        buildLoading = false,
        buildError = null,
    )
