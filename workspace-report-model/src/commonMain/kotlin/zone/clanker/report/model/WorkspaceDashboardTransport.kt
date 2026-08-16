package zone.clanker.report.model

import kotlinx.serialization.Serializable

/** Navigation-sized facts needed to reproduce the complete static workspace dashboard. */
@Serializable
data class WorkspaceDashboardShard(
    val schemaVersion: Int = WorkspaceSnapshot.CURRENT_SCHEMA_VERSION,
    val workspaceId: String,
    val projectCount: Int,
    val symbolCount: Int,
    val dependencyCount: Int,
    val builds: List<WorkspaceBuildDashboard>,
    val projects: List<WorkspaceProjectDashboard>,
    val findings: List<WorkspaceDashboardFinding>,
    val hubs: List<WorkspaceDashboardHub>,
    val coverage: WorkspaceDashboardCoverage,
) {
    init {
        require(schemaVersion == WorkspaceSnapshot.CURRENT_SCHEMA_VERSION) {
            "Unsupported workspace-dashboard schema: $schemaVersion"
        }
        requireValidId(workspaceId, "Workspace-dashboard workspace")
        require(projectCount >= 0 && symbolCount >= 0 && dependencyCount >= 0) {
            "Workspace-dashboard totals must not be negative"
        }
        requireUniqueAndSorted(builds.map(WorkspaceBuildDashboard::buildId), "dashboard builds")
        requireUniqueAndSorted(projects.map(WorkspaceProjectDashboard::projectId), "dashboard projects")
        requireUniqueAndSorted(findings.map(WorkspaceDashboardFinding::id), "dashboard findings")
        requireUniqueAndSorted(hubs.map(WorkspaceDashboardHub::id), "dashboard hubs")
        val buildIds = builds.mapTo(mutableSetOf(), WorkspaceBuildDashboard::buildId)
        val projectIds = projects.mapTo(mutableSetOf(), WorkspaceProjectDashboard::projectId)
        val projectBuildIds = projects.associate { project -> project.projectId to project.buildId }
        val findingsById = findings.associateBy(WorkspaceDashboardFinding::id)
        require(projects.all { project -> project.buildId in buildIds }) {
            "Dashboard projects must reference serialized builds"
        }
        require(
            findings.all { item ->
                item.buildId in buildIds &&
                    item.projectId in projectIds &&
                    projectBuildIds[item.projectId] == item.buildId
            },
        ) {
            "Dashboard findings must reference serialized build and project scopes"
        }
        require(
            hubs.all { item ->
                item.buildId?.let(buildIds::contains) != false &&
                    item.projectId?.let(projectIds::contains) != false &&
                    item.projectId?.let { projectId -> projectBuildIds[projectId] == item.buildId } != false
            },
        ) {
            "Scoped dashboard hubs must reference serialized build and project scopes"
        }
        require(projectCount == projects.size) { "Dashboard project total must match its project records" }
        require(coverage.projectsWithSymbols <= projectCount) {
            "Dashboard projects with symbols cannot exceed the project total"
        }
        require(coverage.sourceSetCount == projects.sumOf(WorkspaceProjectDashboard::sourceSetCount)) {
            "Dashboard source-set coverage must match its project records"
        }
        require(coverage.projectAnalysisCount <= projectCount) {
            "Dashboard project-analysis coverage cannot exceed the project total"
        }
        require(symbolCount == projects.sumOf(WorkspaceProjectDashboard::symbolCount)) {
            "Dashboard symbol total must match its project records"
        }
        builds.forEach { build ->
            val buildProjects = projects.filter { project -> project.buildId == build.buildId }
            val buildFindings = findings.filter { finding -> finding.buildId == build.buildId }
            require(build.projectCount == buildProjects.size) {
                "Dashboard build project totals must match its project records"
            }
            require(build.symbolCount == buildProjects.sumOf(WorkspaceProjectDashboard::symbolCount)) {
                "Dashboard build symbol totals must match its project records"
            }
            require(
                build.findingCounts.associate { count -> count.key to count.count } ==
                    buildFindings.groupingBy { finding -> finding.finding.severity.name }.eachCount(),
            ) { "Dashboard build finding totals must match its finding records" }
            require(
                build.sourceSetCounts.sumOf(AtlasCount::count) ==
                    buildProjects.sumOf(WorkspaceProjectDashboard::sourceSetCount),
            ) { "Dashboard build source-set total must match its project records" }
        }
        require(
            projects.all { project ->
                project.findingIds.all { findingId -> findingsById[findingId]?.projectId == project.projectId }
            },
        ) { "Dashboard project finding IDs must reference findings from the same project" }
        require(projects.flatMap(WorkspaceProjectDashboard::findingIds).sorted() == findings.map { it.id }) {
            "Every dashboard finding must belong to exactly one serialized project"
        }
        require(findings.flatMap { item -> item.finding.symbolIds }.distinct().size <= symbolCount) {
            "Dashboard finding symbols cannot exceed the serialized symbol total"
        }
    }
}

@Serializable
data class WorkspaceBuildDashboard(
    val buildId: String,
    val color: String,
    val projectCount: Int,
    val symbolCount: Int,
    val findingCounts: List<AtlasCount>,
    val sourceSetCounts: List<AtlasCount>,
) {
    init {
        requireValidId(buildId, "Dashboard build")
        require(color.isNotBlank()) { "Dashboard build color must not be blank" }
        require(projectCount >= 0 && symbolCount >= 0) { "Dashboard build totals must not be negative" }
        requireDashboardCounts(findingCounts, "build finding")
        requireDashboardCounts(sourceSetCounts, "build source-set")
    }
}

@Serializable
data class WorkspaceProjectDashboard(
    val projectId: String,
    val buildId: String,
    val fileCount: Int,
    val symbolCount: Int,
    val sourceSetCount: Int,
    val sourceSetNames: List<String>,
    val findingIds: List<String>,
) {
    init {
        requireValidId(projectId, "Dashboard project")
        requireValidId(buildId, "Dashboard project build")
        require(fileCount >= 0 && symbolCount >= 0 && sourceSetCount >= 0) {
            "Dashboard project totals must not be negative"
        }
        require(sourceSetNames.size <= sourceSetCount) {
            "Dashboard project source-set names cannot exceed its source-set record total"
        }
        requireDistinctAndSorted(sourceSetNames, "Dashboard project source sets")
        requireDistinctAndSorted(findingIds, "Dashboard project findings")
    }
}

@Serializable
data class WorkspaceDashboardFinding(
    val id: String,
    val buildId: String,
    val projectId: String,
    val sourceSetNames: List<String>,
    val finding: FindingSnapshot,
) {
    init {
        requireValidId(id, "Dashboard finding")
        requireValidId(buildId, "Dashboard finding build")
        requireValidId(projectId, "Dashboard finding project")
        requireDistinctAndSorted(sourceSetNames, "Dashboard finding source sets")
    }
}

@Serializable
data class WorkspaceDashboardHub(
    val id: String,
    val buildId: String? = null,
    val projectId: String? = null,
    val hub: HubSnapshot,
) {
    init {
        requireValidId(id, "Dashboard hub")
        buildId?.let { requireValidId(it, "Dashboard hub build") }
        projectId?.let { requireValidId(it, "Dashboard hub project") }
        require((buildId == null) == (projectId == null)) {
            "Dashboard hub build and project scopes must either both be present or both be absent"
        }
        require(!hub.isTest) { "The dashboard production-hub catalog cannot contain test hubs" }
    }
}

@Serializable
data class WorkspaceDashboardCoverage(
    val projectsWithSymbols: Int,
    val sourceSetCount: Int,
    val packageCount: Int,
    val projectAnalysisCount: Int,
) {
    init {
        require(projectsWithSymbols >= 0 && sourceSetCount >= 0) {
            "Dashboard project and source-set coverage totals must not be negative"
        }
        require(packageCount >= 0 && projectAnalysisCount >= 0) {
            "Dashboard coverage totals must not be negative"
        }
    }
}

private fun requireDashboardCounts(
    counts: List<AtlasCount>,
    label: String,
) {
    require(counts.map(AtlasCount::key) == counts.map(AtlasCount::key).distinct().sorted()) {
        "Dashboard $label counts must use distinct deterministic keys"
    }
}
