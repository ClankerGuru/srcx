package zone.clanker.docx.web.state

import zone.clanker.report.model.ProjectGraphShard

internal fun selectedProjectShard(
    selectedProjectId: String?,
    project: ProjectGraphShard?,
    loadedProjects: List<ProjectGraphShard>,
): ProjectGraphShard? =
    selectedProjectId?.let { projectId ->
        project?.takeIf { shard -> shard.projectId == projectId }
            ?: loadedProjects.firstOrNull { shard -> shard.projectId == projectId }
    }
