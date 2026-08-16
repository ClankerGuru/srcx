package zone.clanker.docx.index.importing

import zone.clanker.report.model.ProjectSnapshot
import zone.clanker.report.model.WorkspaceSummaryShard

internal class WorkspaceOwnership(
    summary: WorkspaceSummaryShard,
) {
    private val projectsById = summary.projects.associateBy(ProjectSnapshot::id)

    fun project(projectId: String): ProjectSnapshot =
        checkNotNull(projectsById[projectId]) { "Project shard references an unknown project: $projectId" }

    fun buildId(projectId: String): String = project(projectId).buildId
}
