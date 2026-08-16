package zone.clanker.docx.web.state

import zone.clanker.docx.web.site.LoadedWorkspaceSite
import zone.clanker.report.model.ProjectGraphShard

internal sealed interface ViewerState {
    data object Loading : ViewerState

    data class Ready(
        val site: LoadedWorkspaceSite,
        val selectedProjectId: String? = null,
        val project: ProjectGraphShard? = null,
        val projectLoading: Boolean = false,
        val projectError: String? = null,
        val loadedProjectIds: Set<String> = emptySet(),
        val loadedBuildId: String? = null,
        val buildProjects: List<ProjectGraphShard> = emptyList(),
        val buildLoading: Boolean = false,
        val buildError: String? = null,
        val evidence: EvidenceProjectState = EvidenceProjectState(),
    ) : ViewerState

    data class Failed(
        val message: String,
    ) : ViewerState
}
