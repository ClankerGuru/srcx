package zone.clanker.gradle.docx.site

import zone.clanker.report.model.AtlasCount
import zone.clanker.report.model.BuildSnapshot
import zone.clanker.report.model.FindingSnapshot
import zone.clanker.report.model.HubSnapshot
import zone.clanker.report.model.ProjectGraphShard
import zone.clanker.report.model.ProjectSnapshot
import zone.clanker.report.model.SourceFileSnapshot
import zone.clanker.report.model.SourceSetSnapshot
import zone.clanker.report.model.SymbolSnapshot
import zone.clanker.report.model.WorkspaceBuildDashboard
import zone.clanker.report.model.WorkspaceDashboardCoverage
import zone.clanker.report.model.WorkspaceDashboardFinding
import zone.clanker.report.model.WorkspaceDashboardHub
import zone.clanker.report.model.WorkspaceDashboardShard
import zone.clanker.report.model.WorkspaceProjectDashboard
import zone.clanker.report.model.WorkspaceSnapshot

/** Projects the typed facts used by every non-graph section of the legacy workspace dashboard. */
internal object WorkspaceDashboardProjection {
    fun apply(
        snapshot: WorkspaceSnapshot,
        projection: ProjectedDocxSite,
    ): WorkspaceDashboardShard {
        val catalog = DashboardCatalog(snapshot, projection)
        val findings = catalog.findings()
        val projects = catalog.projects(findings)
        return WorkspaceDashboardShard(
            workspaceId = snapshot.workspace.id,
            projectCount = projects.size,
            symbolCount = projects.sumOf(WorkspaceProjectDashboard::symbolCount),
            dependencyCount = projection.projectDependencies.size,
            builds = catalog.builds(projects, findings),
            projects = projects,
            findings = findings,
            hubs = catalog.productionHubs(),
            coverage = catalog.coverage(projects),
        )
    }
}

private class DashboardCatalog(
    private val snapshot: WorkspaceSnapshot,
    private val projection: ProjectedDocxSite,
) {
    private val selectedProjectIds = projection.projects.mapTo(mutableSetOf(), ProjectSnapshot::id)
    private val graphByProjectId = projection.projectGraphs.associateBy(ProjectGraphShard::projectId)
    private val sourceSetById = snapshot.sourceSets.associateBy(SourceSetSnapshot::id)
    private val fileById = snapshot.files.associateBy(SourceFileSnapshot::id)

    fun findings(): List<WorkspaceDashboardFinding> =
        projection.projects
            .flatMap { project ->
                val graph = graphByProjectId.getValue(project.id)
                val sourceSetNames = graph.ownedSourceSetNames()
                graph.findings.map { finding -> finding.dashboardFinding(project, sourceSetNames) }
            }.sortedBy(WorkspaceDashboardFinding::id)

    fun projects(findings: List<WorkspaceDashboardFinding>): List<WorkspaceProjectDashboard> =
        projection.projects
            .map { project ->
                val graph = graphByProjectId.getValue(project.id)
                WorkspaceProjectDashboard(
                    projectId = project.id,
                    buildId = project.buildId,
                    fileCount = graph.ownedFileIds.size,
                    symbolCount = graph.ownedSymbolIds.size,
                    sourceSetCount = graph.ownedSourceSetIds.size,
                    sourceSetNames = graph.ownedSourceSetNames(),
                    findingIds =
                        findings
                            .filter { finding -> finding.projectId == project.id }
                            .map(WorkspaceDashboardFinding::id),
                )
            }.sortedBy(WorkspaceProjectDashboard::projectId)

    fun builds(
        projects: List<WorkspaceProjectDashboard>,
        findings: List<WorkspaceDashboardFinding>,
    ): List<WorkspaceBuildDashboard> =
        projection.builds
            .map { build -> build.dashboardBuild(projects, findings) }
            .sortedBy(WorkspaceBuildDashboard::buildId)

    fun productionHubs(): List<WorkspaceDashboardHub> {
        val candidates =
            if (snapshot.aggregateAnalysisPresent) {
                aggregateHubs()
            } else {
                projectHubs()
            }
        return candidates
            .distinctBy { item -> Triple(item.hub.name, item.hub.filePath, item.hub.line) }
            .sortedBy(WorkspaceDashboardHub::id)
    }

    fun coverage(projects: List<WorkspaceProjectDashboard>): WorkspaceDashboardCoverage {
        val ownedSymbolIds = projection.projectGraphs.flatMapTo(mutableSetOf(), ProjectGraphShard::ownedSymbolIds)
        val packages =
            snapshot.symbols
                .filter { symbol -> symbol.id in ownedSymbolIds }
                .map(SymbolSnapshot::packageName)
                .distinct()
        return WorkspaceDashboardCoverage(
            projectsWithSymbols = projects.count { project -> project.symbolCount > 0 },
            sourceSetCount = projects.sumOf(WorkspaceProjectDashboard::sourceSetCount),
            packageCount = packages.size,
            projectAnalysisCount =
                snapshot.projectAnalyses.count { analysis -> analysis.projectId in selectedProjectIds },
        )
    }

    private fun aggregateHubs(): List<WorkspaceDashboardHub> {
        val allProjectsSelected = selectedProjectIds == snapshot.projects.mapTo(mutableSetOf(), ProjectSnapshot::id)
        return snapshot.aggregateHubs
            .filterNot(HubSnapshot::isTest)
            .mapNotNull { hub ->
                val project = hub.sourceFileId?.let(::projectForFile)
                when {
                    project != null && project.id in selectedProjectIds -> hub.dashboardHub("aggregate", project)
                    project == null && allProjectsSelected -> hub.dashboardHub("aggregate", null)
                    else -> null
                }
            }
    }

    private fun projectHubs(): List<WorkspaceDashboardHub> =
        projection.projectGraphs.flatMap { graph ->
            val project = projection.projects.single { candidate -> candidate.id == graph.projectId }
            graph.analysis
                ?.hubs
                .orEmpty()
                .filterNot(HubSnapshot::isTest)
                .map { hub -> hub.dashboardHub("project", project) }
        }

    private fun projectForFile(fileId: String): ProjectSnapshot? {
        val sourceSetId = fileById[fileId]?.sourceSetId ?: return null
        val projectId = sourceSetById[sourceSetId]?.projectId ?: return null
        return snapshot.projects.singleOrNull { project -> project.id == projectId }
    }

    private fun ProjectGraphShard.ownedSourceSetNames(): List<String> =
        sourceSets
            .filter { sourceSet -> sourceSet.id in ownedSourceSetIds }
            .map(SourceSetSnapshot::name)
            .distinct()
            .sorted()

    private fun FindingSnapshot.dashboardFinding(
        project: ProjectSnapshot,
        sourceSetNames: List<String>,
    ): WorkspaceDashboardFinding =
        WorkspaceDashboardFinding(
            id = "dashboard-finding:${project.id}:$id",
            buildId = project.buildId,
            projectId = project.id,
            sourceSetNames = sourceSetNames,
            finding = this,
        )

    private fun BuildSnapshot.dashboardBuild(
        projects: List<WorkspaceProjectDashboard>,
        findings: List<WorkspaceDashboardFinding>,
    ): WorkspaceBuildDashboard {
        val ownedProjects = projects.filter { project -> project.buildId == id }
        val ownedFindings = findings.filter { finding -> finding.buildId == id }
        val sourceSetCounts =
            ownedProjects
                .flatMap { project ->
                    val graph = graphByProjectId.getValue(project.projectId)
                    graph.sourceSets
                        .filter { sourceSet -> sourceSet.id in graph.ownedSourceSetIds }
                        .map(SourceSetSnapshot::name)
                }.groupingBy { name -> name }
                .eachCount()
                .toAtlasCounts { name -> name }
        return WorkspaceBuildDashboard(
            buildId = id,
            color = stableBuildColor(name),
            projectCount = ownedProjects.size,
            symbolCount = ownedProjects.sumOf(WorkspaceProjectDashboard::symbolCount),
            findingCounts =
                ownedFindings
                    .groupingBy { finding -> finding.finding.severity.name }
                    .eachCount()
                    .toAtlasCounts { severity -> severity.lowercase().replaceFirstChar(Char::uppercase) },
            sourceSetCounts = sourceSetCounts,
        )
    }

    private fun HubSnapshot.dashboardHub(
        origin: String,
        project: ProjectSnapshot?,
    ): WorkspaceDashboardHub =
        WorkspaceDashboardHub(
            id = "dashboard-hub:$origin:${project?.id ?: "workspace"}:$id",
            buildId = project?.buildId,
            projectId = project?.id,
            hub = this,
        )
}

private fun Map<String, Int>.toAtlasCounts(label: (String) -> String): List<AtlasCount> =
    entries
        .filter { (_, count) -> count > 0 }
        .sortedBy(Map.Entry<String, Int>::key)
        .map { (key, count) -> AtlasCount(key = key, label = label(key), count = count) }
