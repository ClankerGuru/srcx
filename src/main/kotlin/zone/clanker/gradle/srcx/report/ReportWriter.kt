package zone.clanker.gradle.srcx.report

import org.gradle.api.Project
import zone.clanker.gradle.srcx.model.ProjectSummary
import zone.clanker.gradle.srcx.scan.ProjectScanner
import zone.clanker.gradle.srcx.scan.SymbolExtractor
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Report generation utilities: writing per-project reports,
 * dashboard files, included build reports, and class diagrams.
 */
object ReportWriter {
    private const val THREAD_POOL_SIZE = 4

    /** Write a per-project symbol report to the output directory. */
    internal fun writeProjectReport(
        rootProject: Project,
        summary: ProjectSummary,
        outputDir: String,
    ) {
        val renderer = ProjectReportRenderer(summary)
        val sanitized =
            summary.projectPath.value
                .replace(":", "/")
                .trimStart('/')
                .ifEmpty { "root" }
        val reportDir = File(rootProject.projectDir, "$outputDir/$sanitized")
        reportDir.mkdirs()
        File(reportDir, "symbols.md").writeText(renderer.render())
    }

    /** Write a .gitignore with wildcard to the output directory. */
    internal fun writeGitignore(
        rootProjectDir: File,
        outputDir: String,
    ) {
        val dir = File(rootProjectDir, outputDir)
        dir.mkdirs()
        File(dir, ".gitignore").writeText("*\n")
    }

    /** Generate per-project reports for each included build. */
    internal fun generateIncludedBuildReports(
        builds: Collection<org.gradle.api.initialization.IncludedBuild>,
        outputDir: String,
    ) {
        for (build in builds) {
            val buildDir = build.projectDir
            val buildOutputDir = File(buildDir, outputDir)
            buildOutputDir.mkdirs()

            val projectDirs =
                ProjectScanner.discoverIncludedBuildProjects(build).map { (path, dir) ->
                    dir to path
                }

            val summaries =
                projectDirs.map { (dir, path) ->
                    SymbolExtractor.extractStandaloneProjectSummary(dir, path)
                }

            for (summary in summaries) {
                val renderer = ProjectReportRenderer(summary)
                val sanitized =
                    summary.projectPath.value
                        .replace(":", "/")
                        .trimStart('/')
                        .ifEmpty { "root" }
                val reportDir = File(buildOutputDir, sanitized)
                reportDir.mkdirs()
                File(reportDir, "symbols.md").writeText(renderer.render())
            }

            val buildRenderer = IncludedBuildRenderer(build.name, summaries)
            File(buildOutputDir, "context.md").writeText(buildRenderer.render())
            writeGitignoreAt(buildOutputDir)
        }
        if (builds.isNotEmpty()) {
            println("srcx: generated reports for ${builds.size} included build(s)")
        }
    }

    /** Collect summaries for all projects in each included build. */
    internal fun collectIncludedBuildSummaries(
        builds: Collection<org.gradle.api.initialization.IncludedBuild>,
    ): Map<String, List<ProjectSummary>> {
        val result = mutableMapOf<String, List<ProjectSummary>>()
        for (build in builds) {
            val projectEntries = ProjectScanner.discoverIncludedBuildProjects(build)
            result[build.name] =
                projectEntries.map { (path, dir) ->
                    SymbolExtractor.extractStandaloneProjectSummary(dir, path)
                }
        }
        return result
    }

    /** Compute cross-build dependency edges for the dashboard diagram. */
    internal fun computeBuildEdges(
        builds: List<Pair<String, File>>,
        buildSummaries: Map<String, List<ProjectSummary>>,
    ): List<DashboardRenderer.BuildEdge> {
        val buildNames = builds.map { it.first }.toSet()
        return buildSummaries
            .flatMap { (name, projects) ->
                projects.flatMap { summary ->
                    summary.dependencies
                        .map { it.artifact.value }
                        .filter { it in buildNames && it != name }
                        .map { DashboardRenderer.BuildEdge(name, it) }
                }
            }.distinct()
    }

    /** Generate a Mermaid class dependency diagram from all sources in the root project. */
    internal fun generateClassDiagram(rootProject: Project): String {
        val allDirs =
            ProjectScanner
                .collectProjects(rootProject)
                .flatMap { project ->
                    ProjectScanner.discoverSourceSets(project.projectDir).flatMap { ss ->
                        ProjectScanner.sourceSetDirs(project.projectDir, ss.value)
                    }
                }.filter { it.exists() }
        if (allDirs.isEmpty()) return ""
        return runCatching {
            val sources =
                zone.clanker.gradle.srcx.analysis
                    .scanSources(allDirs)
            val components =
                zone.clanker.gradle.srcx.analysis
                    .classifyAll(sources)
            val depEdges =
                zone.clanker.gradle.srcx.analysis
                    .buildDependencyGraph(components)
            zone.clanker.gradle.srcx.analysis
                .generateDependencyDiagram(components, depEdges)
        }.onFailure { e ->
            System.err.println("srcx: class diagram generation failed: ${e.message}")
        }.getOrDefault("")
    }

    /** Run work in parallel across a list of projects. */
    internal fun runParallel(
        projects: List<Project>,
        work: (Project) -> String,
    ) {
        if (projects.isEmpty()) {
            println("srcx: No projects to process.")
            return
        }
        val pool = Executors.newFixedThreadPool(THREAD_POOL_SIZE)
        val futures =
            projects.map { project ->
                pool.submit(
                    Callable {
                        runCatching { work(project) }
                            .getOrElse { e -> "FAIL ${project.path}: ${e.message}" }
                    },
                )
            }
        val results = futures.map { it.get() }
        pool.shutdown()
        runCatching { pool.awaitTermination(Long.MAX_VALUE, TimeUnit.MILLISECONDS) }
        results.forEach { println(it) }
        val failed = results.count { it.startsWith("FAIL") }
        println("srcx: symbols complete -- ${projects.size} projects, $failed failed")
        if (failed > 0) error("srcx: $failed project(s) failed during generation")
    }

    private fun writeGitignoreAt(outputDir: File) {
        outputDir.mkdirs()
        File(outputDir, ".gitignore").writeText("*\n")
    }
}
