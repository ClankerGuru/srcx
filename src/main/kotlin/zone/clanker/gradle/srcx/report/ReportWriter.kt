package zone.clanker.gradle.srcx.report

import org.gradle.api.logging.Logging
import zone.clanker.gradle.srcx.model.ProjectSummary
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
    private val logger = Logging.getLogger(ReportWriter::class.java)
    private const val THREAD_POOL_SIZE = 4
    private const val TASK_TIMEOUT_MINUTES = 10L
    private const val SHUTDOWN_TIMEOUT_SECONDS = 30L

    /** Write a per-project symbol report to the output directory. */
    internal fun writeProjectReportToDir(
        rootDir: File,
        summary: ProjectSummary,
        outputDir: String,
    ) {
        val renderer = ProjectReportRenderer(summary)
        val sanitized =
            summary.projectPath.value
                .replace(":", "/")
                .trimStart('/')
                .ifEmpty { "root" }
        val reportDir = File(rootDir, "$outputDir/$sanitized")
        reportDir.mkdirs()
        File(reportDir, "context.md").writeText(renderer.render())
    }

    /** Write a .gitignore with wildcard to the given directory. */
    internal fun writeGitignore(
        rootProjectDir: File,
        outputDir: String,
    ) {
        val dir = File(rootProjectDir, outputDir)
        writeGitignoreAt(dir)
    }

    /** Generate included build reports from pre-computed data. */
    internal fun generateIncludedBuildReportsFromData(
        builds: List<zone.clanker.gradle.srcx.task.IncludedBuildInfo>,
        outputDir: String,
    ) {
        for (info in builds) {
            val summaries =
                info.projects.map { (path, dir) ->
                    SymbolExtractor.extractStandaloneProjectSummary(dir, path)
                }
            writeBuildReports(info.name, info.dir, summaries, outputDir)
        }
        if (builds.isNotEmpty()) {
            logger.lifecycle("srcx: generated reports for ${builds.size} included build(s)")
        }
    }

    private fun writeBuildReports(
        buildName: String,
        buildDir: File,
        summaries: List<ProjectSummary>,
        outputDir: String,
    ) {
        val buildOutputDir = File(buildDir, outputDir)
        buildOutputDir.mkdirs()
        for (summary in summaries) {
            writeProjectReportToDir(buildDir, summary, outputDir)
        }
        val renderer = IncludedBuildRenderer(buildName, summaries)
        File(buildOutputDir, "context.md").writeText(renderer.render())
        writeGitignoreAt(buildOutputDir)
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

    /** Run work in parallel across items, returning mapped results. */
    internal fun <T, R> runParallelMapped(
        items: List<T>,
        work: (T) -> R,
    ): List<R> {
        if (items.isEmpty()) return emptyList()
        val pool = Executors.newFixedThreadPool(THREAD_POOL_SIZE)
        val futures =
            items.map { item ->
                pool.submit(Callable { work(item) })
            }
        val results =
            runCatching { futures.map { it.get(TASK_TIMEOUT_MINUTES, TimeUnit.MINUTES) } }
        pool.shutdown()
        val awaitResult =
            runCatching { pool.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
        if (awaitResult.exceptionOrNull() is InterruptedException) {
            pool.shutdownNow()
            Thread.currentThread().interrupt()
        }
        if (!pool.isTerminated) pool.shutdownNow()
        return results.getOrThrow()
    }

    private fun writeGitignoreAt(outputDir: File) {
        outputDir.mkdirs()
        File(outputDir, ".gitignore").writeText("*\n")
    }
}
