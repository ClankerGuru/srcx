package zone.clanker.docx.web.fixture

import zone.clanker.report.model.AtlasCount
import zone.clanker.report.model.BuildSnapshot
import zone.clanker.report.model.WorkspaceBuildDashboard
import zone.clanker.report.model.WorkspaceDashboardCoverage
import zone.clanker.report.model.WorkspaceDashboardShard
import zone.clanker.report.model.WorkspaceIdentity
import zone.clanker.report.model.WorkspaceProjectDashboard

internal class DocxScaleDashboardFactory(
    private val dimensions: DocxScaleDimensions,
    private val workspace: WorkspaceIdentity,
    private val builds: List<BuildSnapshot>,
    private val projects: List<DocxScaleProject>,
) {
    fun dashboard(): WorkspaceDashboardShard =
        WorkspaceDashboardShard(
            workspaceId = workspace.id,
            projectCount = dimensions.projectCount,
            symbolCount = dimensions.symbolCount,
            dependencyCount = dimensions.projectDependencyCount,
            builds = builds.mapIndexed(::buildDashboard),
            projects = projects.map(::projectDashboard),
            findings = projects.map(DocxScaleProject::dashboardFinding),
            hubs = emptyList(),
            coverage = dashboardCoverage(),
        )

    private fun buildDashboard(
        index: Int,
        build: BuildSnapshot,
    ): WorkspaceBuildDashboard =
        WorkspaceBuildDashboard(
            buildId = build.id,
            color = buildColor(index),
            projectCount = dimensions.projectsPerBuild,
            symbolCount = projects.filter { it.buildIndex == index }.sumOf(DocxScaleProject::symbolCount),
            findingCounts = listOf(AtlasCount("WARNING", "Warning", dimensions.projectsPerBuild)),
            sourceSetCounts =
                listOf(
                    AtlasCount("main", "main", dimensions.projectsPerBuild),
                    AtlasCount("test", "test", dimensions.projectsPerBuild),
                ),
        )

    private fun projectDashboard(project: DocxScaleProject): WorkspaceProjectDashboard =
        WorkspaceProjectDashboard(
            projectId = project.id,
            buildId = project.buildId,
            fileCount = project.fileCount,
            symbolCount = project.symbolCount,
            sourceSetCount = SOURCE_SET_NAMES.size,
            sourceSetNames = SOURCE_SET_NAMES,
            findingIds = listOf(project.dashboardFindingId),
        )

    private fun dashboardCoverage(): WorkspaceDashboardCoverage =
        WorkspaceDashboardCoverage(
            projectsWithSymbols = dimensions.projectCount,
            sourceSetCount = dimensions.projectCount * SOURCE_SET_NAMES.size,
            packageCount = dimensions.projectCount,
            projectAnalysisCount = 0,
        )
}
