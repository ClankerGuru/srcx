package zone.clanker.docx.web.fixture

import zone.clanker.report.model.AtlasCount
import zone.clanker.report.model.FindingSeverity
import zone.clanker.report.model.FindingSnapshot
import zone.clanker.report.model.ProjectGraphShard
import zone.clanker.report.model.SymbolSnapshot
import zone.clanker.report.model.WorkspaceBuildDashboard
import zone.clanker.report.model.WorkspaceDashboardCoverage
import zone.clanker.report.model.WorkspaceDashboardFinding
import zone.clanker.report.model.WorkspaceDashboardShard
import zone.clanker.report.model.WorkspaceProjectDashboard
import zone.clanker.report.model.WorkspaceSummaryShard

internal fun fixtureDashboard(
    summary: WorkspaceSummaryShard,
    projects: List<ProjectGraphShard>,
    appProject: ProjectGraphShard,
    libraryProject: ProjectGraphShard,
): WorkspaceDashboardShard {
    val appFinding = appProject.findings.single()
    val projectDashboards = fixtureProjectDashboards(appProject, libraryProject, appFinding.id)
    return WorkspaceDashboardShard(
        workspaceId = summary.workspace.id,
        projectCount = projectDashboards.size,
        symbolCount = projectDashboards.sumOf(WorkspaceProjectDashboard::symbolCount),
        dependencyCount = projects.sumOf { shard -> shard.relationships.size },
        builds = fixtureBuildDashboards(appProject, libraryProject),
        projects = projectDashboards,
        findings = listOf(fixtureDashboardFinding(appProject, appFinding)),
        hubs = emptyList(),
        coverage = fixtureDashboardCoverage(projects),
    )
}

private fun fixtureProjectDashboards(
    appProject: ProjectGraphShard,
    libraryProject: ProjectGraphShard,
    appFindingId: String,
): List<WorkspaceProjectDashboard> =
    listOf(
        appProject.dashboardProject("build:fixture", listOf(appFindingId)),
        libraryProject.dashboardProject("build:fixture:library", emptyList()),
    )

private fun ProjectGraphShard.dashboardProject(
    buildId: String,
    findingIds: List<String>,
): WorkspaceProjectDashboard =
    WorkspaceProjectDashboard(
        projectId = projectId,
        buildId = buildId,
        fileCount = files.size,
        symbolCount = symbols.size,
        sourceSetCount = sourceSets.size,
        sourceSetNames = sourceSets.map { sourceSet -> sourceSet.name }.distinct().sorted(),
        findingIds = findingIds,
    )

private fun fixtureBuildDashboards(
    appProject: ProjectGraphShard,
    libraryProject: ProjectGraphShard,
): List<WorkspaceBuildDashboard> =
    listOf(
        WorkspaceBuildDashboard(
            buildId = "build:fixture",
            color = "hsl(321 58% 66%)",
            projectCount = 1,
            symbolCount = appProject.symbols.size,
            findingCounts =
                listOf(
                    AtlasCount(
                        key = FindingSeverity.WARNING.name,
                        label = FindingSeverity.WARNING.label,
                        count = appProject.findings.size,
                    ),
                ),
            sourceSetCounts = listOf(AtlasCount("main", "main", appProject.sourceSets.size)),
        ),
        WorkspaceBuildDashboard(
            buildId = "build:fixture:library",
            color = "hsl(295 58% 66%)",
            projectCount = 1,
            symbolCount = libraryProject.symbols.size,
            findingCounts = emptyList(),
            sourceSetCounts = listOf(AtlasCount("main", "main", libraryProject.sourceSets.size)),
        ),
    )

private fun fixtureDashboardFinding(
    appProject: ProjectGraphShard,
    finding: FindingSnapshot,
): WorkspaceDashboardFinding =
    WorkspaceDashboardFinding(
        id = finding.id,
        buildId = "build:fixture",
        projectId = appProject.projectId,
        sourceSetNames = listOf("main"),
        finding = finding,
    )

private fun fixtureDashboardCoverage(projects: List<ProjectGraphShard>): WorkspaceDashboardCoverage =
    WorkspaceDashboardCoverage(
        projectsWithSymbols = projects.count { shard -> shard.symbols.isNotEmpty() },
        sourceSetCount = projects.sumOf { shard -> shard.sourceSets.size },
        packageCount =
            projects
                .flatMap { shard -> shard.symbols }
                .map(SymbolSnapshot::packageName)
                .distinct()
                .size,
        projectAnalysisCount = projects.count { shard -> shard.analysis != null },
    )
