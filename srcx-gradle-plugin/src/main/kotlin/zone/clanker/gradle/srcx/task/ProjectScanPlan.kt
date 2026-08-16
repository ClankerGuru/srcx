package zone.clanker.gradle.srcx.task

import zone.clanker.gradle.srcx.model.DependencyEntry
import zone.clanker.gradle.srcx.report.ReportWriter
import zone.clanker.gradle.srcx.scan.ProjectExtractionRequest
import zone.clanker.gradle.srcx.scan.ProjectScan
import zone.clanker.gradle.srcx.scan.ProjectSourceLayout
import zone.clanker.gradle.srcx.scan.SymbolExtractor
import java.io.File

/** Configuration-time root build facts needed by the execution-time project scan plan. */
internal data class RootBuildScanInput(
    val name: String,
    val directory: File,
    val projects: Map<String, File>,
    val subprojects: List<String>,
    val dependencies: Map<String, List<DependencyEntry>>,
    val sourceLayouts: Map<String, ProjectSourceLayout> = emptyMap(),
) {
    init {
        require(name.isNotBlank()) { "root build name must not be blank" }
    }
}

/** Identifies whether a project belongs to the root build or an included build. */
internal enum class ProjectScanScope {
    ROOT,
    INCLUDED,
}

/** One bounded unit of project-local extraction and report I/O. */
internal data class ProjectScanRequest(
    val scope: ProjectScanScope,
    val build: String,
    val reportRoot: File,
    val projectPath: String,
    val projectDirectory: File,
    val subprojects: List<String> = emptyList(),
    val dependencies: List<DependencyEntry> = emptyList(),
    val sourceLayout: ProjectSourceLayout? = null,
)

/** A completed request paired with its scan so workspace ownership cannot be lost. */
internal data class CompletedProjectScan(
    val request: ProjectScanRequest,
    val scan: ProjectScan,
) {
    init {
        require(scan.build == request.build) { "scan build must match its request" }
        require(scan.projectPath.value == request.projectPath) { "scan project path must match its request" }
    }
}

/**
 * Build one stable work sequence: root projects first, then each included build in declared order.
 * Project paths are sorted inside every build, matching the pre-parallel report order.
 */
internal fun buildProjectScanRequests(
    root: RootBuildScanInput,
    includedBuilds: List<IncludedBuildInfo>,
): List<ProjectScanRequest> =
    buildList {
        root.projects.entries
            .sortedBy { entry -> entry.key }
            .forEach { (path, directory) ->
                add(
                    ProjectScanRequest(
                        scope = ProjectScanScope.ROOT,
                        build = root.name,
                        reportRoot = root.directory,
                        projectPath = path,
                        projectDirectory = directory,
                        subprojects = if (path == ":") root.subprojects else emptyList(),
                        dependencies = root.dependencies[path].orEmpty(),
                        sourceLayout = root.sourceLayouts[path],
                    ),
                )
            }
        includedBuilds.forEach { includedBuild ->
            includedBuild.projects
                .sortedBy { project -> project.first }
                .forEach { (path, directory) ->
                    add(
                        ProjectScanRequest(
                            scope = ProjectScanScope.INCLUDED,
                            build = includedBuild.name,
                            reportRoot = includedBuild.dir,
                            projectPath = path,
                            projectDirectory = directory,
                            sourceLayout = includedBuild.sourceLayouts[path],
                        ),
                    )
                }
        }
    }

/** Reject work plans whose project paths would write the same report file concurrently. */
internal fun validateDistinctProjectReportTargets(
    requests: List<ProjectScanRequest>,
    outputDir: String,
) {
    val targets =
        requests.map { request ->
            ReportWriter
                .projectReportFile(request.reportRoot, request.projectPath, outputDir)
                .canonicalFile
        }
    require(targets.distinct().size == targets.size) {
        "Project scan requests must own distinct report files"
    }
}

/** Execute one request without creating a second PSI environment. */
internal fun extractProjectScan(request: ProjectScanRequest): ProjectScan =
    when (request.scope) {
        ProjectScanScope.ROOT ->
            SymbolExtractor.extractProjectScanFromData(
                ProjectExtractionRequest(
                    projectDir = request.projectDirectory,
                    projectPath = request.projectPath,
                    subprojectPaths = request.subprojects,
                    dependencies = request.dependencies,
                    build = request.build,
                    sourceLayout = request.sourceLayout,
                ),
            )
        ProjectScanScope.INCLUDED ->
            SymbolExtractor.extractStandaloneProjectScan(
                projectDir = request.projectDirectory,
                projectPath = request.projectPath,
                build = request.build,
                sourceLayout = request.sourceLayout,
            )
    }

/** Restore workspace ownership while retaining the work sequence, independent of completion order. */
internal fun assembleWorkspaceScans(
    rootBuild: String,
    includedBuilds: List<IncludedBuildInfo>,
    completed: List<CompletedProjectScan>,
): WorkspaceScans {
    val rootScans =
        completed
            .filter { result -> result.request.scope == ProjectScanScope.ROOT }
            .map { result -> result.scan }
    val includedByBuild =
        completed
            .filter { result -> result.request.scope == ProjectScanScope.INCLUDED }
            .groupBy { result -> result.request.build }
    val includedScans =
        includedBuilds.associateTo(linkedMapOf()) { build ->
            build.name to includedByBuild[build.name].orEmpty().map { result -> result.scan }
        }
    return WorkspaceScans(rootBuild, rootScans, includedScans)
}
