package zone.clanker.docx.web.catalog

import zone.clanker.report.model.BuildKind
import zone.clanker.report.model.BuildSnapshot
import zone.clanker.report.model.ProjectSnapshot
import zone.clanker.report.model.WorkspaceSourceSetSummary
import zone.clanker.report.model.WorkspaceSummaryShard

internal fun orderedBuilds(summary: WorkspaceSummaryShard): List<BuildSnapshot> =
    summary.builds.sortedWith(buildPresentationComparator)

internal fun orderedProjects(summary: WorkspaceSummaryShard): List<ProjectSnapshot> {
    val projectsByBuild = summary.projects.groupBy(ProjectSnapshot::buildId)
    return orderedBuilds(summary).flatMap { build ->
        projectsByBuild
            .getOrElse(build.id, ::emptyList)
            .sortedWith(projectPresentationComparator)
    }
}

internal fun orderedSourceSets(summary: WorkspaceSummaryShard): List<WorkspaceSourceSetSummary> {
    val sourceSetsByProject = summary.sourceSets.groupBy(WorkspaceSourceSetSummary::projectId)
    return orderedProjects(summary).flatMap { project ->
        sourceSetsByProject
            .getOrElse(project.id, ::emptyList)
            .sortedWith(sourceSetPresentationComparator)
    }
}

internal fun initialProject(summary: WorkspaceSummaryShard): ProjectSnapshot? {
    val projects = orderedProjects(summary)
    val rootBuildId = summary.builds.single { build -> build.kind == BuildKind.ROOT }.id
    return projects.firstOrNull { project -> project.buildId == rootBuildId } ?: projects.firstOrNull()
}

internal fun visibleProjects(
    summary: WorkspaceSummaryShard,
    query: String,
): List<ProjectSnapshot> {
    val projects = orderedProjects(summary)
    val normalized = query.trim().lowercase()
    if (normalized.isEmpty()) {
        return projects
    }
    val matchingBuildIds =
        summary.builds
            .filter { build ->
                build.name.lowercase().contains(normalized) ||
                    build.relativePath.lowercase().contains(normalized)
            }.mapTo(mutableSetOf(), BuildSnapshot::id)
    return projects.filter { project ->
        project.buildId in matchingBuildIds ||
            project.path.lowercase().contains(normalized) ||
            project.buildFile.lowercase().contains(normalized)
    }
}

private val buildPresentationComparator =
    compareBy<BuildSnapshot>(
        { build -> if (build.kind == BuildKind.ROOT) 0 else 1 },
        { build -> build.name.lowercase() },
        BuildSnapshot::name,
        BuildSnapshot::id,
    )

private val projectPresentationComparator = compareBy(ProjectSnapshot::path, ProjectSnapshot::id)

private val sourceSetPresentationComparator =
    compareBy<WorkspaceSourceSetSummary>(
        { sourceSet -> sourceSet.name.lowercase() },
        WorkspaceSourceSetSummary::name,
        WorkspaceSourceSetSummary::id,
    )
