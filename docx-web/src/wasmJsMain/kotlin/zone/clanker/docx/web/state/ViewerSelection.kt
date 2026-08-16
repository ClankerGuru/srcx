package zone.clanker.docx.web.state

internal fun ViewerState.Ready.selectScope(
    buildIds: Set<String>,
    projectIds: Set<String>,
): ViewerState.Ready {
    val selectedProjectId = projectIds.singleOrNull()
    return copy(
        selectedProjectId = selectedProjectId,
        project = null,
        projectLoading = false,
        projectError = null,
        loadedProjectIds = emptySet(),
        loadedBuildId =
            buildIds
                .singleOrNull()
                ?.takeIf { projectIds.isEmpty() },
        buildProjects = emptyList(),
        buildLoading = false,
        buildError = null,
    )
}
