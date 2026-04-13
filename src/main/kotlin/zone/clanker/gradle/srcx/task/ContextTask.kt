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
import zone.clanker.gradle.srcx.model.DependencyEntry
import zone.clanker.gradle.srcx.model.ProjectSummary
import zone.clanker.gradle.srcx.report.AntiPatternsRenderer
import zone.clanker.gradle.srcx.report.CrossBuildRenderer
import zone.clanker.gradle.srcx.report.DashboardRenderer
import zone.clanker.gradle.srcx.report.EntryPointsRenderer
import zone.clanker.gradle.srcx.report.HotClassesRenderer
import zone.clanker.gradle.srcx.report.InterfacesRenderer
import zone.clanker.gradle.srcx.report.ReportWriter
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

        // Aggregate analysis from per-build summaries (robust, works on large repos)
        val aggregatedSummary = aggregateAnalysis(summaryList, includedBuildSummaries)

        val renderer =
            DashboardRenderer(
                rootName = rootName.get(),
                summaries = summaryList,
                includedBuilds = includedBuildRefs,
                includedBuildSummaries = includedBuildSummaries,
                buildEdges = buildEdges,
                crossBuildAnalysis = aggregatedSummary,
            )
        val dir = File(root, outDir)
        dir.mkdirs()
        File(dir, "context.md").writeText(renderer.render())

        // Split detail files
        writeSplitFiles(dir, summaryList, includedBuildSummaries, buildEdges, aggregatedSummary)

        ReportWriter.writeGitignore(root, outDir)
        zone.clanker.gradle.srcx.parse.PsiEnvironment
            .closeShared()
        logger.lifecycle("srcx: context written to $outDir/context.md")
    }

    private fun writeSplitFiles(
        dir: File,
        summaryList: List<ProjectSummary>,
        includedBuildSummaries: Map<String, List<ProjectSummary>>,
        buildEdges: List<DashboardRenderer.BuildEdge>,
        aggregatedSummary: zone.clanker.gradle.srcx.model.AnalysisSummary?,
    ) {
        // hub-classes.md — from aggregated per-build analysis
        val allHubs = aggregatedSummary?.hubs ?: emptyList()
        File(dir, "hub-classes.md").writeText(HotClassesRenderer(allHubs).render())

        // entry-points.md — from aggregated per-build analysis
        val entryPoints = buildEntryPointsFromSummaries(summaryList, includedBuildSummaries)
        File(dir, "entry-points.md").writeText(
            EntryPointsRenderer(entryPoints).render(),
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
            CrossBuildRenderer(buildEdges, aggregatedSummary).render(),
        )
    }

    private fun buildEntryPointsFromSummaries(
        summaryList: List<ProjectSummary>,
        includedBuildSummaries: Map<String, List<ProjectSummary>>,
    ): List<EntryPointsRenderer.ClassifiedEntry> {
        val allSummaries = summaryList + includedBuildSummaries.values.flatten()
        return allSummaries
            .flatMap { summary ->
                summary.sourceSets.flatMap { ss ->
                    val isTestSourceSet = ss.name.value.contains("test", ignoreCase = true)
                    ss.symbols
                        .filter { it.kind == zone.clanker.gradle.srcx.model.SymbolKind.CLASS }
                        .mapNotNull { symbol ->
                            val name = symbol.name.value
                            val isTest =
                                isTestSourceSet ||
                                    name.endsWith("Test") ||
                                    name.endsWith("Spec")
                            val isMock =
                                name.startsWith("Mock") ||
                                    name.endsWith("Mock") ||
                                    name.startsWith("Fake") ||
                                    name.endsWith("Fake") ||
                                    name.startsWith("Stub") ||
                                    name.endsWith("Stub")
                            val kind =
                                when {
                                    isMock -> EntryPointsRenderer.EntryKind.MOCK
                                    isTest -> EntryPointsRenderer.EntryKind.TEST
                                    else -> EntryPointsRenderer.EntryKind.APP
                                }
                            EntryPointsRenderer.ClassifiedEntry(name, symbol.packageName.value, kind)
                        }
                }
            }.distinctBy { "${it.packageName}.${it.className}" }
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

    internal fun aggregateAnalysis(
        summaryList: List<ProjectSummary>,
        includedBuildSummaries: Map<String, List<ProjectSummary>>,
    ): zone.clanker.gradle.srcx.model.AnalysisSummary? {
        val allSummaries = summaryList + includedBuildSummaries.values.flatten()
        val allAnalyses = allSummaries.mapNotNull { it.analysis }
        if (allAnalyses.isEmpty()) return null

        val allHubs =
            allAnalyses
                .flatMap { it.hubs }
                .sortedWith(
                    compareByDescending<zone.clanker.gradle.srcx.model.HubClass> { it.dependentCount }
                        .thenBy { it.name },
                ).take(HUB_LIMIT)

        val allCycles = allAnalyses.flatMap { it.cycles }.distinct()

        return zone.clanker.gradle.srcx.model.AnalysisSummary(
            findings = allAnalyses.flatMap { it.findings }.distinctBy { it.message },
            hubs = allHubs,
            cycles = allCycles,
        )
    }

    companion object {
        private const val HUB_LIMIT = 30
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
