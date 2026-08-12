package zone.clanker.gradle.srcx.report

import org.gradle.api.logging.Logging
import zone.clanker.gradle.srcx.model.ProjectSummary
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

    /** Write included-build reports from the summaries already used by the workspace report. */
    internal fun writeIncludedBuildReports(
        buildDirectories: Map<String, File>,
        summariesByBuild: Map<String, List<ProjectSummary>>,
        outputDir: String,
    ) {
        for ((buildName, buildDirectory) in buildDirectories) {
            val summaries = summariesByBuild[buildName].orEmpty()
            writeBuildReports(buildName, buildDirectory, summaries, outputDir)
        }
        if (buildDirectories.isNotEmpty()) {
            logger.lifecycle("srcx: generated reports for ${buildDirectories.size} included build(s)")
        }
    }

    /** Replace the complete root relationship subtree so removed symbols cannot leave stale pages. */
    internal fun writeWorkspaceRelationshipReports(
        outputDirectory: File,
        rendered: WorkspaceRelationshipsRenderer.RenderedWorkspaceRelationships,
    ) {
        val pages = rendered.pages.sortedBy { it.fileName }
        require(pages.map { it.fileName }.distinct().size == pages.size) {
            "relationship page filenames must be unique"
        }
        require(pages.all { it.fileName == File(it.fileName).name && it.fileName.endsWith(".md") }) {
            "relationship page filenames must be safe Markdown filenames"
        }
        check(outputDirectory.isDirectory || outputDirectory.mkdirs()) {
            "Unable to create srcx output directory: ${outputDirectory.absolutePath}"
        }
        val relationshipDirectory = File(outputDirectory, "relationships")
        check(!relationshipDirectory.exists() || relationshipDirectory.deleteRecursively()) {
            "Unable to delete stale relationship directory: ${relationshipDirectory.absolutePath}"
        }
        check(relationshipDirectory.mkdir()) {
            "Unable to create relationship directory: ${relationshipDirectory.absolutePath}"
        }
        File(relationshipDirectory, "index.md").writeText(rendered.indexMarkdown)
        pages.forEach { page ->
            File(relationshipDirectory, page.fileName).writeText(page.markdown)
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
