package zone.clanker.gradle.srcx.report

import org.gradle.api.logging.Logging
import zone.clanker.gradle.srcx.Srcx
import zone.clanker.gradle.srcx.model.ProjectSummary
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Report generation utilities: writing per-project reports,
 * dashboard files, included build reports, and class diagrams.
 */
object ReportWriter {
    private val logger = Logging.getLogger(ReportWriter::class.java)
    private const val TASK_TIMEOUT_MINUTES = 10L
    private const val SHUTDOWN_TIMEOUT_SECONDS = 30L

    /** Write a per-project symbol report to the output directory. */
    internal fun writeProjectReportToDir(
        rootDir: File,
        summary: ProjectSummary,
        outputDir: String,
    ) {
        val renderer = ProjectReportRenderer(summary)
        val reportFile = projectReportFile(rootDir, summary.projectPath.value, outputDir)
        reportFile.parentFile.mkdirs()
        reportFile.writeText(renderer.render())
    }

    /** Resolve the sole report file owned by a project path. */
    internal fun projectReportFile(
        rootDir: File,
        projectPath: String,
        outputDir: String,
    ): File {
        val sanitized =
            projectPath
                .replace(":", "/")
                .trimStart('/')
                .ifEmpty { "root" }
        val reportDir = File(rootDir, "$outputDir/$sanitized")
        return File(reportDir, "context.md")
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

    /** Write included-build overview reports after project reports were emitted by scan workers. */
    internal fun writeIncludedBuildOverviewReports(
        buildDirectories: Map<String, File>,
        summariesByBuild: Map<String, List<ProjectSummary>>,
        outputDir: String,
    ) {
        for ((buildName, buildDirectory) in buildDirectories) {
            val summaries = summariesByBuild[buildName].orEmpty()
            writeBuildOverviewReport(buildName, buildDirectory, summaries, outputDir)
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
        for (summary in summaries) {
            writeProjectReportToDir(buildDir, summary, outputDir)
        }
        writeBuildOverviewReport(buildName, buildDir, summaries, outputDir)
    }

    private fun writeBuildOverviewReport(
        buildName: String,
        buildDir: File,
        summaries: List<ProjectSummary>,
        outputDir: String,
    ) {
        val buildOutputDir = File(buildDir, outputDir)
        buildOutputDir.mkdirs()
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
        workerCount: Int = DEFAULT_WORKER_COUNT,
        work: (T) -> R,
    ): List<R> {
        if (items.isEmpty()) return emptyList()
        require(workerCount in 1..MAX_WORKER_COUNT) {
            "workerCount must be between 1 and $MAX_WORKER_COUNT"
        }
        val activeWorkerCount = minOf(workerCount, items.size)
        val pool = Executors.newFixedThreadPool(activeWorkerCount)
        val completion = ExecutorCompletionService<IndexedValue<R>>(pool)
        val pendingItems = items.withIndex().iterator()
        val orderedResults = MutableList<IndexedValue<R>?>(items.size) { null }

        fun submitNext() {
            val indexedItem = pendingItems.next()
            completion.submit(
                Callable {
                    IndexedValue(indexedItem.index, work(indexedItem.value))
                },
            )
        }

        repeat(activeWorkerCount) { submitNext() }
        val results =
            runCatching {
                repeat(items.size) {
                    val completed =
                        checkNotNull(completion.poll(TASK_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                            "Parallel work did not complete within $TASK_TIMEOUT_MINUTES minutes"
                        }.get()
                    orderedResults[completed.index] = completed
                    if (pendingItems.hasNext()) submitNext()
                }
                orderedResults.map { result -> checkNotNull(result).value }
            }
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

    internal const val DEFAULT_WORKER_COUNT = Srcx.DEFAULT_PROJECT_WORKERS
    internal const val MAX_WORKER_COUNT = Srcx.MAX_PROJECT_WORKERS

    private fun writeGitignoreAt(outputDir: File) {
        outputDir.mkdirs()
        File(outputDir, ".gitignore").writeText("*\n")
    }
}
