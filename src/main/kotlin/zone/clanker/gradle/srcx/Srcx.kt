package zone.clanker.gradle.srcx

import org.gradle.api.Action
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.initialization.Settings
import zone.clanker.gradle.srcx.extractor.DependencyExtractor
import zone.clanker.gradle.srcx.extractor.SymbolExtractor
import zone.clanker.gradle.srcx.model.ProjectSummary
import zone.clanker.gradle.srcx.report.DashboardRenderer
import zone.clanker.gradle.srcx.report.ProjectReportRenderer
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Root identity object for the srcx source symbol extraction plugin.
 *
 * Contains all constants, the [SettingsExtension] DSL, and the [SettingsPlugin] entry point.
 * Tasks and reports reference [Srcx.GROUP], [Srcx.TASK_GENERATE], etc.
 *
 * ```kotlin
 * // Access constants:
 * Srcx.GROUP           // "srcx"
 * Srcx.TASK_GENERATE   // "srcx-generate"
 * Srcx.TASK_DASHBOARD  // "srcx-dashboard"
 * ```
 */
data object Srcx {
    /** Gradle task group name for all srcx tasks. */
    const val GROUP = "srcx"

    /** Name of the DSL extension registered on Settings. */
    const val EXTENSION_NAME = "srcx"

    /** Output directory for generated reports. */
    const val OUTPUT_DIR = ".srcx"

    /** Lifecycle task: generate per-project symbol reports. */
    const val TASK_GENERATE = "srcx-generate"

    /** Task: generate root dashboard index. */
    const val TASK_DASHBOARD = "srcx-dashboard"

    private const val THREAD_POOL_SIZE = 4

    /**
     * DSL extension registered as `srcx { }` on the Settings object.
     *
     * Controls the output directory for generated reports.
     *
     * ```kotlin
     * srcx {
     *     outputDir = ".srcx"
     * }
     * ```
     *
     * @see SettingsPlugin
     */
    open class SettingsExtension {
        /** Output directory relative to the root project. */
        var outputDir: String = OUTPUT_DIR
    }

    /**
     * Settings plugin entry point: `id("zone.clanker.gradle.srcx")`.
     *
     * Sequence:
     * 1. Register the [SettingsExtension]
     * 2. Use `settingsEvaluated` callback to wire tasks after DSL runs
     * 3. For each project, register a `srcx-generate-<name>` task
     * 4. Register [TASK_GENERATE] lifecycle task that runs all in parallel
     * 5. Register [TASK_DASHBOARD] task that generates the root index
     *
     * ```kotlin
     * // settings.gradle.kts
     * plugins {
     *     id("zone.clanker.gradle.srcx") version "0.1.0"
     * }
     * ```
     *
     * @see SettingsExtension
     */
    class SettingsPlugin : Plugin<Settings> {
        override fun apply(settings: Settings) {
            val extension = settings.extensions.create(EXTENSION_NAME, SettingsExtension::class.java)

            settings.gradle.rootProject(
                Action { rootProject ->
                    registerTasks(rootProject, extension)
                },
            )
        }

        internal fun registerTasks(
            rootProject: Project,
            extension: SettingsExtension,
        ) {
            registerGenerateTask(rootProject, extension)
            registerDashboardTask(rootProject, extension)
        }

        internal fun collectProjects(rootProject: Project): List<Project> =
            listOf(rootProject) + rootProject.subprojects

        internal fun registerGenerateTask(
            rootProject: Project,
            extension: SettingsExtension,
        ) {
            rootProject.tasks.register(TASK_GENERATE).configure { task ->
                task.group = GROUP
                task.description = "Generate symbol reports for all projects"
                task.doLast {
                    val projects = collectProjects(rootProject)
                    runParallel(projects) { project ->
                        val summary = extractProjectSummary(project, rootProject)
                        writeProjectReport(rootProject, summary, extension)
                        "OK ${project.path}"
                    }
                    writeGitignore(rootProject, extension)
                }
            }
        }

        internal fun registerDashboardTask(
            rootProject: Project,
            extension: SettingsExtension,
        ) {
            rootProject.tasks.register(TASK_DASHBOARD).configure { task ->
                task.group = GROUP
                task.description = "Generate root dashboard index"
                task.doLast {
                    val projects = collectProjects(rootProject)
                    val summaries = projects.map { extractProjectSummary(it, rootProject) }
                    val includedBuilds = rootProject.gradle.includedBuilds.map { it.name }
                    val renderer = DashboardRenderer(summaries, includedBuilds)
                    val outputDir = File(rootProject.projectDir, extension.outputDir)
                    outputDir.mkdirs()
                    File(outputDir, "index.md").writeText(renderer.render())
                    writeGitignore(rootProject, extension)
                    println("srcx: dashboard written to ${extension.outputDir}/index.md")
                }
            }
        }

        internal fun extractProjectSummary(
            project: Project,
            rootProject: Project,
        ): ProjectSummary {
            val symbolExtractor = SymbolExtractor(project.projectDir)
            val symbols = symbolExtractor.extract()
            val sourceDirs = symbolExtractor.sourceDirNames()
            val dependencyExtractor = DependencyExtractor(project)
            val dependencies = dependencyExtractor.extract()
            val buildFileName = buildFileName(project)
            val subprojectPaths =
                if (project == rootProject) {
                    rootProject.subprojects.map { it.path }
                } else {
                    emptyList()
                }
            return ProjectSummary(
                projectPath = project.path,
                symbols = symbols,
                dependencies = dependencies,
                buildFile = buildFileName,
                sourceDirs = sourceDirs,
                subprojects = subprojectPaths,
            )
        }

        internal fun buildFileName(project: Project): String =
            when {
                project.file("build.gradle.kts").exists() -> "build.gradle.kts"
                project.file("build.gradle").exists() -> "build.gradle"
                else -> "none"
            }

        internal fun writeProjectReport(
            rootProject: Project,
            summary: ProjectSummary,
            extension: SettingsExtension,
        ) {
            val renderer = ProjectReportRenderer(summary)
            val sanitized =
                summary.projectPath
                    .replace(":", "/")
                    .trimStart('/')
                    .ifEmpty { "root" }
            val reportDir = File(rootProject.projectDir, "${extension.outputDir}/$sanitized")
            reportDir.mkdirs()
            File(reportDir, "symbols.md").writeText(renderer.render())
        }

        internal fun writeGitignore(
            rootProject: Project,
            extension: SettingsExtension,
        ) {
            val outputDir = File(rootProject.projectDir, extension.outputDir)
            outputDir.mkdirs()
            File(outputDir, ".gitignore").writeText("*\n")
        }

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
            pool.awaitTermination(Long.MAX_VALUE, TimeUnit.MILLISECONDS)
            results.forEach { println(it) }
            val failed = results.count { it.startsWith("FAIL") }
            println("srcx: generate complete -- ${projects.size} projects, $failed failed")
            if (failed > 0) error("srcx: $failed project(s) failed during generation")
        }
    }
}
