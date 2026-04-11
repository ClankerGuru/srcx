package zone.clanker.gradle.srcx.task

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import zone.clanker.gradle.srcx.Srcx
import zone.clanker.gradle.srcx.analysis.ProjectAnalysis
import zone.clanker.gradle.srcx.analysis.analyzeProject
import zone.clanker.gradle.srcx.analysis.buildDependencyGraph
import zone.clanker.gradle.srcx.analysis.classifyAll
import zone.clanker.gradle.srcx.analysis.findEntryPoints
import zone.clanker.gradle.srcx.analysis.generateDependencyDiagram
import zone.clanker.gradle.srcx.analysis.generateSequenceDiagrams
import zone.clanker.gradle.srcx.analysis.scanSources
import zone.clanker.gradle.srcx.model.DependencyEntry
import zone.clanker.gradle.srcx.model.ProjectSummary
import zone.clanker.gradle.srcx.report.AntiPatternsRenderer
import zone.clanker.gradle.srcx.report.CrossBuildRenderer
import zone.clanker.gradle.srcx.report.DashboardRenderer
import zone.clanker.gradle.srcx.report.EntryPointsRenderer
import zone.clanker.gradle.srcx.report.FlowRenderer
import zone.clanker.gradle.srcx.report.HotClassesRenderer
import zone.clanker.gradle.srcx.report.InterfacesRenderer
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

    /** Dependency scopes to exclude from scanning. */
    @get:Input
    abstract val excludeDepScopes: SetProperty<String>

    /** Included build info: name → (dir, relPath, projects). */
    @get:Internal
    abstract val includedBuildInfos: ListProperty<IncludedBuildInfo>

    /** Package names to flag as forbidden in anti-pattern detection. */
    @get:Input
    abstract val forbiddenPackages: SetProperty<String>

    /** Class name suffixes to flag as forbidden in anti-pattern detection. */
    @get:Input
    abstract val forbiddenClassSuffixes: SetProperty<String>

    init {
        group = Srcx.GROUP
        description = "Generate comprehensive context report"
    }

    /**
     * Generate per-project symbol reports, the dashboard, and split detail files.
     *
     * 1. Run parallel symbol extraction, writing per-project reports
     * 2. Generate included build reports
     * 3. Build the dashboard with summaries, build edges, and class diagram
     * 4. Write `context.md`, split files, and `.gitignore`
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
        val crossBuild = analyzeCrossBuild(projects, builds, root)
        val crossBuildSummary = crossBuild.second?.toSummary()
        val renderer =
            DashboardRenderer(
                rootName = rootName.get(),
                summaries = summaryList,
                includedBuilds = includedBuildRefs,
                includedBuildSummaries = includedBuildSummaries,
                buildEdges = buildEdges,
                classDiagram = crossBuild.first,
                crossBuildAnalysis = crossBuildSummary,
            )
        val dir = File(root, outDir)
        dir.mkdirs()
        File(dir, "context.md").writeText(renderer.render())

        // Split detail files
        writeSplitFiles(dir, summaryList, includedBuildSummaries, buildEdges, crossBuild, crossBuildSummary)

        ReportWriter.writeGitignore(root, outDir)
        logger.lifecycle("srcx: context written to $outDir/context.md")
    }

    @Suppress("LongParameterList")
    private fun writeSplitFiles(
        dir: File,
        summaryList: List<ProjectSummary>,
        includedBuildSummaries: Map<String, List<ProjectSummary>>,
        buildEdges: List<DashboardRenderer.BuildEdge>,
        crossBuild: Pair<String, ProjectAnalysis?>,
        crossBuildSummary: zone.clanker.gradle.srcx.model.AnalysisSummary?,
    ) {
        // hot-classes.md
        val allHubs = crossBuildSummary?.hubs ?: emptyList()
        File(dir, "hot-classes.md").writeText(HotClassesRenderer(allHubs).render())

        // entry-points.md
        val entryPoints = buildEntryPoints(crossBuild.second)
        File(dir, "entry-points.md").writeText(
            EntryPointsRenderer(summaryList, entryPoints).render(),
        )

        // anti-patterns.md
        File(dir, "anti-patterns.md").writeText(
            AntiPatternsRenderer(summaryList, includedBuildSummaries).render(),
        )

        // interfaces.md
        val allSummaries = summaryList + includedBuildSummaries.values.flatten()
        val interfaceInfos = InterfacesRenderer.fromSummaries(allSummaries)
        File(dir, "interfaces.md").writeText(InterfacesRenderer(interfaceInfos).render())

        // cross-build.md
        File(dir, "cross-build.md").writeText(
            CrossBuildRenderer(buildEdges, crossBuildSummary).render(),
        )

        // flows/
        writeFlowFiles(dir, crossBuild.second)
    }

    private fun buildEntryPoints(analysis: ProjectAnalysis?): List<EntryPointsRenderer.EntryPoint> {
        if (analysis == null) return emptyList()
        val allDirs = collectAllSourceDirs(projectDirs.get(), includedBuildInfos.get())
        if (allDirs.isEmpty()) return emptyList()
        return runCatching {
            val sources = scanSources(allDirs)
            val components = classifyAll(sources)
            val depEdges = buildDependencyGraph(components)
            val entryPoints = findEntryPoints(components, depEdges)
            entryPoints.map { ep ->
                EntryPointsRenderer.EntryPoint(
                    className = ep.source.simpleName,
                    packageName = ep.source.packageName,
                    firstCall = ep.source.methods.firstOrNull() ?: "",
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun writeFlowFiles(dir: File, analysis: ProjectAnalysis?) {
        if (analysis == null) return
        val allDirs = collectAllSourceDirs(projectDirs.get(), includedBuildInfos.get())
        if (allDirs.isEmpty()) return
        runCatching {
            val sources = scanSources(allDirs)
            val components = classifyAll(sources)
            val depEdges = buildDependencyGraph(components)
            val diagrams = generateSequenceDiagrams(components, depEdges)
            val splitDiagrams = FlowRenderer.splitDiagrams(diagrams)
            if (splitDiagrams.isNotEmpty()) {
                val flowsDir = File(dir, "flows")
                flowsDir.mkdirs()
                for ((name, content) in splitDiagrams) {
                    val renderer = FlowRenderer(name, content)
                    File(flowsDir, "$name.md").writeText(renderer.render())
                }
            }
        }
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

    private fun analyzeCrossBuild(
        projects: Map<String, File>,
        builds: List<IncludedBuildInfo>,
        rootDir: File,
    ): Pair<String, ProjectAnalysis?> {
        val allDirs = collectAllSourceDirs(projects, builds)
        if (allDirs.isEmpty()) return "" to null
        return runCatching {
            val analysis =
                analyzeProject(
                    allDirs,
                    rootDir,
                    forbiddenPackages.get(),
                    forbiddenClassSuffixes.get(),
                )
            val sources = scanSources(allDirs)
            val components = classifyAll(sources)
            val depEdges = buildDependencyGraph(components)
            val diagram = generateDependencyDiagram(components, depEdges)
            diagram to analysis
        }.onFailure { e ->
            logger.warn("srcx: cross-build analysis failed: ${e.message}")
        }.getOrDefault("" to null)
    }

    private fun collectAllSourceDirs(
        projects: Map<String, File>,
        builds: List<IncludedBuildInfo>,
    ): List<File> {
        val rootDirs =
            projects.values.flatMap { projectDir ->
                ProjectScanner
                    .discoverSourceSets(projectDir)
                    .flatMap { ss -> ProjectScanner.sourceSetDirs(projectDir, ss.value) }
            }
        val includedDirs =
            builds.flatMap { build ->
                build.projects.flatMap { (_, dir) ->
                    ProjectScanner
                        .discoverSourceSets(dir)
                        .flatMap { ss -> ProjectScanner.sourceSetDirs(dir, ss.value) }
                }
            }
        return (rootDirs + includedDirs).filter { it.exists() }
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
