package zone.clanker.docx.web.atlas.projection

import zone.clanker.report.model.AtlasLens
import zone.clanker.report.model.AtlasScope
import zone.clanker.report.model.AtlasScopeKind
import zone.clanker.report.model.ProjectGraphShard
import zone.clanker.report.model.ProjectSnapshot
import zone.clanker.report.model.WorkspaceSummaryShard

/** Explicit graph roots. Projects narrow builds; an empty selection is the bounded workspace overview. */
data class AtlasScopeSelection(
    val buildIds: Set<String> = emptySet(),
    val projectIds: Set<String> = emptySet(),
) {
    val isOverview: Boolean
        get() = buildIds.isEmpty() && projectIds.isEmpty()

    internal fun selectedProjects(summary: WorkspaceSummaryShard): List<ProjectSnapshot> =
        summary.projects.filter { project ->
            if (projectIds.isNotEmpty()) project.id in projectIds else project.buildId in buildIds
        }

    internal fun effectiveBuildIds(summary: WorkspaceSummaryShard): Set<String> =
        if (projectIds.isEmpty()) {
            buildIds
        } else {
            summary.projects
                .filter { project -> project.id in projectIds }
                .mapTo(mutableSetOf(), ProjectSnapshot::buildId)
        }

    internal fun atlasScope(summary: WorkspaceSummaryShard): AtlasScope {
        val projects = selectedProjects(summary)
        val singleProject = projects.singleOrNull()?.takeIf { projectIds.size == 1 }
        val singleBuildId = buildIds.singleOrNull()?.takeIf { projectIds.isEmpty() }
        return when {
            singleProject != null ->
                AtlasScope(
                    kind = AtlasScopeKind.PROJECT,
                    workspaceId = summary.workspace.id,
                    workspaceName = summary.workspace.name,
                    buildId = singleProject.buildId,
                    projectId = singleProject.id,
                )

            singleBuildId != null ->
                AtlasScope(
                    kind = AtlasScopeKind.BUILD,
                    workspaceId = summary.workspace.id,
                    workspaceName = summary.workspace.name,
                    buildId = singleBuildId,
                )

            else ->
                AtlasScope(
                    kind = AtlasScopeKind.WORKSPACE,
                    workspaceId = summary.workspace.id,
                    workspaceName = summary.workspace.name,
                )
        }
    }

    internal fun frameId(
        lens: AtlasLens,
        projects: List<ProjectGraphShard>,
    ): String {
        val scopeKey =
            when {
                projectIds.size == 1 -> projectIds.single()
                projectIds.isNotEmpty() -> "projects:${projects.map(ProjectGraphShard::projectId).hashCode()}"
                buildIds.size == 1 -> buildIds.single()
                else -> "builds:${buildIds.sorted().hashCode()}"
            }
        return "atlas:$scopeKey:${lens.name.lowercase()}:0"
    }
}
