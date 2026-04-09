package zone.clanker.gradle.srcx.task

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import zone.clanker.gradle.srcx.Srcx
import zone.clanker.gradle.srcx.analysis.buildDependencyGraph
import zone.clanker.gradle.srcx.analysis.classifyAll
import zone.clanker.gradle.srcx.analysis.generateDependencyDiagram
import zone.clanker.gradle.srcx.analysis.scanSources
import zone.clanker.gradle.srcx.model.DependencyEntry
import zone.clanker.gradle.srcx.model.ProjectSummary
import zone.clanker.gradle.srcx.report.DashboardRenderer
import zone.clanker.gradle.srcx.report.ReportWriter
import zone.clanker.gradle.srcx.scan.ProjectScanner
import zone.clanker.gradle.srcx.scan.SymbolExtractor
import java.io.File

/**
 * Generates the comprehensive context report with symbols, analysis, and diagrams.
 *
 * All Gradle model data (project dirs, paths, dependencies, included builds)
 * is captured at configuration time via task properties. The task action
 * operates only on files and pre-computed data — no [org.gradle.api.Project]
 * access at execution time.
 *
 * ```bash
 * ./gradlew srcx-context
 * cat .srcx/context.md
 * ```
 *
 * @see CleanTask
 * @see Srcx
 */
@org.gradle.work.DisableCachingByDefault(because = "Output depends on local file layout")
abstract class ContextTask : DefaultTask() {
    /** Output directory relative to the root project (e.g. `.srcx`). */
    @get:Input
    abstract val outputDir: Property<String>

    /** All source files that feed into context generation. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceFiles: ConfigurableFileCollection

    /** The output directory where context reports are written. */
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    /** Root project name, captured at configuration time. */
    @get:Input
    abstract val rootName: Property<String>

    /** Root project directory, captured at configuration time. */
    @get:Internal
    abstract val rootDir: Property<File>

    /** Project paths and directories: path → absolute dir. */
    @get:Internal
    abstract val projectDirs: MapProperty<String, File>

    /** Subproject paths of the root project. */
    @get:Internal
    abstract val subprojectPaths: ListProperty<String>

    /** Pre-computed dependencies per project path. */
    @get:Internal
    abstract val projectDeps: MapProperty<String, List<DependencyEntry>>

    /** Included build info: name → (dir, relPath, projects). */
    @get:Internal
    abstract val includedBuildInfos: ListProperty<IncludedBuildInfo>

    init {
        group = Srcx.GROUP
        description = "Generate comprehensive context report"
    }

    /**
     * Generate per-project symbol reports and the dashboard context file.
     *
     * 1. Run parallel symbol extraction, writing per-project reports
     * 2. Generate included build reports
     * 3. Build the dashboard with summaries, build edges, and class diagram
     * 4. Write `context.md` and `.gitignore`
     */
    @TaskAction
    fun generate() {
        val root = rootDir.get()
        val outDir = outputDir.get()
        val projects = projectDirs.get()
        val deps = projectDeps.get()
        val subs = subprojectPaths.get()

        // Per-project symbol reports
        val summaries =
            ReportWriter.runParallelMapped(projects.entries.toList()) { (path, dir) ->
                val projDeps = deps[path] ?: emptyList()
                val projSubs = if (path == ":") subs else emptyList()
                val summary =
                    SymbolExtractor.extractProjectSummaryFromData(dir, path, projSubs, projDeps)
                ReportWriter.writeProjectReportToDir(root, summary, outDir)
                path to summary
            }

        // Included build reports
        val builds = includedBuildInfos.get()
        ReportWriter.generateIncludedBuildReportsFromData(builds, outDir)

        // Context report
        val summaryList = summaries.map { it.second }
        val includedBuildRefs = builds.map { DashboardRenderer.IncludedBuildRef(it.name, it.relPath) }
        val includedBuildSummaries = collectIncludedBuildSummaries(builds)
        val buildPairs = builds.map { it.name to it.dir }
        val buildEdges = ReportWriter.computeBuildEdges(buildPairs, includedBuildSummaries)
        val classDiagram = generateClassDiagram(projects)
        val renderer =
            DashboardRenderer(
                rootName = rootName.get(),
                summaries = summaryList,
                includedBuilds = includedBuildRefs,
                includedBuildSummaries = includedBuildSummaries,
                buildEdges = buildEdges,
                classDiagram = classDiagram,
            )
        val dir = File(root, outDir)
        dir.mkdirs()
        File(dir, "context.md").writeText(renderer.render())
        ReportWriter.writeGitignore(root, outDir)
        println("srcx: context written to $outDir/context.md")
    }

    private fun collectIncludedBuildSummaries(
        builds: List<IncludedBuildInfo>,
    ): Map<String, List<ProjectSummary>> =
        builds.associate { info ->
            info.name to
                info.projects.map { (path, dir) ->
                    SymbolExtractor.extractStandaloneProjectSummary(dir, path)
                }
        }

    private fun generateClassDiagram(projects: Map<String, File>): String {
        val allDirs =
            projects.values
                .flatMap { projectDir ->
                    ProjectScanner
                        .discoverSourceSets(projectDir)
                        .flatMap { ss ->
                            ProjectScanner.sourceSetDirs(projectDir, ss.value)
                        }
                }.filter { it.exists() }
        if (allDirs.isEmpty()) return ""
        return runCatching {
            val sources = scanSources(allDirs)
            val components = classifyAll(sources)
            val depEdges = buildDependencyGraph(components)
            generateDependencyDiagram(components, depEdges)
        }.onFailure { e ->
            System.err.println("srcx: class diagram generation failed: ${e.message}")
        }.getOrDefault("")
    }
}

/** Pre-computed included build data, captured at configuration time. */
data class IncludedBuildInfo(
    val name: String,
    val dir: File,
    val relPath: String,
    val projects: List<Pair<String, File>>,
) : java.io.Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
