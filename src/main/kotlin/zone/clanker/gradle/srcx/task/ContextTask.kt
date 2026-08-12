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
import zone.clanker.gradle.srcx.analysis.ImportantSymbolPolicy
import zone.clanker.gradle.srcx.model.AnalysisSummary
import zone.clanker.gradle.srcx.model.ArchitectureEntryPointKind
import zone.clanker.gradle.srcx.model.BuildEdge
import zone.clanker.gradle.srcx.model.DependencyEntry
import zone.clanker.gradle.srcx.model.EntryPointKind
import zone.clanker.gradle.srcx.model.EntryPointSummary
import zone.clanker.gradle.srcx.model.HubClass
import zone.clanker.gradle.srcx.model.HubDependentRef
import zone.clanker.gradle.srcx.model.ImportantSymbol
import zone.clanker.gradle.srcx.model.ImportantSymbolSignals
import zone.clanker.gradle.srcx.model.IncludedBuildSummary
import zone.clanker.gradle.srcx.model.InterfaceSummary
import zone.clanker.gradle.srcx.model.ProjectSummary
import zone.clanker.gradle.srcx.model.SymbolDetailKind
import zone.clanker.gradle.srcx.model.WorkspaceIndex
import zone.clanker.gradle.srcx.model.WorkspaceRelationship
import zone.clanker.gradle.srcx.model.WorkspaceRelationshipKind
import zone.clanker.gradle.srcx.model.WorkspaceReport
import zone.clanker.gradle.srcx.model.WorkspaceSymbolIdentity
import zone.clanker.gradle.srcx.parse.PsiEnvironment
import zone.clanker.gradle.srcx.report.AntiPatternsRenderer
import zone.clanker.gradle.srcx.report.CrossBuildRenderer
import zone.clanker.gradle.srcx.report.DashboardRenderer
import zone.clanker.gradle.srcx.report.EntryPointsRenderer
import zone.clanker.gradle.srcx.report.HotClassesRenderer
import zone.clanker.gradle.srcx.report.InterfacesRenderer
import zone.clanker.gradle.srcx.report.ReportWriter
import zone.clanker.gradle.srcx.report.WorkspaceHtmlRenderer
import zone.clanker.gradle.srcx.report.WorkspaceRelationshipsRenderer
import zone.clanker.gradle.srcx.scan.ProjectScan
import zone.clanker.gradle.srcx.scan.SymbolExtractor
import zone.clanker.gradle.srcx.scan.WorkspaceIndexBuilder
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

    /** Included build identities and paths, used to invalidate reports when a worktree changes. */
    @get:Input
    abstract val includedBuildPaths: ListProperty<String>

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

    @TaskAction
    fun generate() {
        runCatching { generateReports() }
            .also { PsiEnvironment.closeShared() }
            .getOrThrow()
    }

    private fun generateReports() {
        val root = rootDir.get()
        val outDir = outputDir.get()
        val builds = includedBuildInfos.get()
        val scans =
            WorkspaceScans(
                rootBuild = rootName.get(),
                rootProjectScans = extractRootProjectScans(root, outDir, rootName.get()),
                includedProjectScans = collectIncludedBuildScans(builds),
            )
        val workspaceIndex = WorkspaceIndexBuilder.build(scans.allScans)
        val importantSymbols =
            ImportantSymbolPolicy.select(
                workspaceIndex,
                buildImportantSymbolSignals(scans.allScans, workspaceIndex),
            )
        ReportWriter.writeIncludedBuildReports(
            buildDirectories = builds.associate { build -> build.name to build.dir },
            summariesByBuild = scans.includedSummaries,
            outputDir = outDir,
        )
        val summariesByBuild = linkedMapOf(scans.rootBuild to scans.rootSummaries)
        summariesByBuild.putAll(scans.includedSummaries)
        val artifactBuildEdges =
            ReportWriter
                .computeBuildEdges(
                    builds = listOf(scans.rootBuild to root) + builds.map { build -> build.name to build.dir },
                    buildSummaries = summariesByBuild,
                ).map { edge -> BuildEdge(edge.from, edge.to) }
        val buildEdges = buildWorkspaceBuildEdges(workspaceIndex, artifactBuildEdges)
        val aggregateAnalysis = aggregateAnalysis(scans.allScans, workspaceIndex)
        val workspaceReport =
            buildWorkspaceReport(
                scans,
                builds,
                buildEdges,
                aggregateAnalysis,
                workspaceIndex,
                importantSymbols,
            )
        writeWorkspaceReports(root, outDir, workspaceReport)
        ReportWriter.writeGitignore(root, outDir)
        logger.lifecycle("srcx: context and HTML documentation written to $outDir")
    }

    private fun extractRootProjectScans(
        root: File,
        outDir: String,
        buildName: String,
    ): List<ProjectScan> {
        val dependencies = projectDeps.get()
        val rootSubprojects = subprojectPaths.get()
        val projects = projectDirs.get().entries.sortedBy { it.key }
        return ReportWriter.runParallelMapped(projects) { (path, directory) ->
            val projectDependencies = dependencies[path].orEmpty()
            val projectSubprojects = if (path == ":") rootSubprojects else emptyList()
            val scan =
                SymbolExtractor.extractProjectScanFromData(
                    directory,
                    path,
                    projectSubprojects,
                    projectDependencies,
                    buildName,
                )
            ReportWriter.writeProjectReportToDir(root, scan.summary, outDir)
            scan
        }
    }

    @Suppress("LongParameterList")
    private fun buildWorkspaceReport(
        scans: WorkspaceScans,
        builds: List<IncludedBuildInfo>,
        buildEdges: List<BuildEdge>,
        aggregateAnalysis: AnalysisSummary?,
        workspaceIndex: WorkspaceIndex,
        importantSymbols: List<ImportantSymbol>,
    ): WorkspaceReport {
        val includedBuilds =
            builds.map { build ->
                IncludedBuildSummary(
                    name = build.name,
                    relativePath = build.relPath,
                    projects = scans.includedSummaries[build.name].orEmpty(),
                )
            }
        val allSummaries = scans.rootSummaries + includedBuilds.flatMap { it.projects }
        return WorkspaceReport(
            name = scans.rootBuild,
            rootProjects = scans.rootSummaries,
            includedBuilds = includedBuilds,
            buildEdges = buildEdges,
            aggregateAnalysis = aggregateAnalysis,
            entryPoints = buildEntryPoints(allSummaries),
            interfaces = buildInterfaces(allSummaries),
            workspaceIndex = workspaceIndex,
            importantSymbols = importantSymbols,
        )
    }

    private fun writeWorkspaceReports(
        root: File,
        outDir: String,
        report: WorkspaceReport,
    ) {
        val outputDirectory = File(root, outDir).apply { mkdirs() }
        val workspaceRelationships = WorkspaceRelationshipsRenderer().render(report)
        ReportWriter.writeWorkspaceRelationshipReports(outputDirectory, workspaceRelationships)
        val markdownBuildEdges =
            report.buildEdges.map { edge -> DashboardRenderer.BuildEdge(edge.from, edge.to) }
        val markdownRenderer =
            DashboardRenderer(
                rootName = report.name,
                summaries = report.rootProjects,
                includedBuilds =
                    report.includedBuilds.map { build ->
                        DashboardRenderer.IncludedBuildRef(build.name, build.relativePath)
                    },
                includedBuildSummaries = report.includedBuilds.associate { it.name to it.projects },
                buildEdges = markdownBuildEdges,
                crossBuildAnalysis = report.aggregateAnalysis,
                workspaceRelationships = workspaceRelationships,
            )
        File(outputDirectory, "context.md").writeText(markdownRenderer.render())
        writeSplitFiles(outputDirectory, report, markdownBuildEdges)
        writeHtmlDocumentation(outputDirectory, report)
    }

    private fun writeSplitFiles(
        dir: File,
        report: WorkspaceReport,
        buildEdges: List<DashboardRenderer.BuildEdge>,
    ) {
        val allHubs = report.aggregateAnalysis?.hubs.orEmpty()
        File(dir, "hub-classes.md").writeText(HotClassesRenderer(allHubs).render())
        File(dir, "entry-points.md").writeText(
            EntryPointsRenderer(report.entryPoints.map { entry -> entry.toMarkdownEntry() }).render(),
        )
        File(dir, "anti-patterns.md").writeText(
            AntiPatternsRenderer(
                report.rootProjects,
                report.includedBuilds.associate { it.name to it.projects },
            ).render(),
        )
        File(dir, "interfaces.md").writeText(
            InterfacesRenderer(report.interfaces.map { info -> info.toMarkdownInterface() }).render(),
        )
        File(dir, "cross-build.md").writeText(
            CrossBuildRenderer(buildEdges, report.aggregateAnalysis).render(),
        )
    }

    private fun writeHtmlDocumentation(
        outputDirectory: File,
        report: WorkspaceReport,
    ) {
        val rendered = WorkspaceHtmlRenderer().render(report)
        val siteDirectory = File(outputDirectory, Srcx.HTML_SITE_DIR).apply { mkdirs() }
        File(siteDirectory, Srcx.HTML_INDEX_FILE).writeText(rendered.document)
        File(siteDirectory, Srcx.HTML_FRAGMENT_FILE).writeText(rendered.fragment)
    }

    private fun buildEntryPoints(summaries: List<ProjectSummary>): List<EntryPointSummary> =
        summaries
            .flatMap { summary ->
                val architecture = summary.analysis?.architecture ?: return@flatMap emptyList()
                val components = architecture.components.associateBy { it.id }
                architecture.entryPoints
                    .filter { it.kind == ArchitectureEntryPointKind.EXPLICIT }
                    .mapNotNull { entryPoint ->
                        components[entryPoint.componentId]?.let { component ->
                            EntryPointSummary(
                                component.name,
                                component.packageName.ifBlank { "_root_" },
                                EntryPointKind.APP,
                            )
                        }
                    }
            }.distinctBy { "${it.packageName}.${it.name}" }

    private fun buildInterfaces(summaries: List<ProjectSummary>): List<InterfaceSummary> =
        InterfacesRenderer.fromSummaries(summaries).map { info ->
            InterfaceSummary(
                name = info.name,
                packageName = info.packageName,
                implementationCount = info.implementationCount,
                hasMock = info.hasMock,
                sourceSet = info.sourceSet,
            )
        }

    private fun EntryPointSummary.toMarkdownEntry(): EntryPointsRenderer.ClassifiedEntry =
        EntryPointsRenderer.ClassifiedEntry(
            className = name,
            packageName = packageName,
            kind = EntryPointsRenderer.EntryKind.valueOf(kind.name),
        )

    private fun InterfaceSummary.toMarkdownInterface(): InterfacesRenderer.InterfaceInfo =
        InterfacesRenderer.InterfaceInfo(name, packageName, implementationCount, hasMock, sourceSet)

    private fun collectIncludedBuildScans(
        builds: List<IncludedBuildInfo>,
    ): Map<String, List<ProjectScan>> =
        builds.associate { info ->
            info.name to
                info.projects.sortedBy { it.first }.map { (path, dir) ->
                    SymbolExtractor.extractStandaloneProjectScan(dir, path, info.name)
                }
        }

    internal fun aggregateAnalysis(
        scans: List<ProjectScan>,
        workspaceIndex: WorkspaceIndex,
    ): AnalysisSummary? {
        val allAnalyses = scans.mapNotNull { it.summary.analysis }
        if (allAnalyses.isEmpty()) return null

        val allCycles = allAnalyses.flatMap { it.cycles }.distinct()

        return AnalysisSummary(
            findings = allAnalyses.flatMap { it.findings }.distinctBy { it.message },
            hubs = buildCumulativeHubs(workspaceIndex, HUB_LIMIT),
            cycles = allCycles,
        )
    }

    companion object {
        private const val HUB_LIMIT = 30
    }
}

/** One extraction pass over the root build and every active included build. */
internal data class WorkspaceScans(
    val rootBuild: String,
    val rootProjectScans: List<ProjectScan>,
    val includedProjectScans: Map<String, List<ProjectScan>>,
) {
    init {
        require(rootBuild.isNotBlank()) { "rootBuild must not be blank" }
        require(rootProjectScans.all { it.build == rootBuild }) { "root scans must use the root build name" }
        require(
            includedProjectScans.all { (build, scans) ->
                build.isNotBlank() && scans.all { it.build == build }
            },
        ) { "included scans must use their owning build name" }
    }

    val allScans: List<ProjectScan>
        get() = rootProjectScans + includedProjectScans.values.flatten()

    val rootSummaries: List<ProjectSummary>
        get() = rootProjectScans.map { it.summary }

    val includedSummaries: Map<String, List<ProjectSummary>>
        get() = includedProjectScans.mapValues { (_, scans) -> scans.map { it.summary } }
}

/** Build exact analysis signals without deriving symbol identities from prose findings. */
internal fun buildImportantSymbolSignals(
    scans: List<ProjectScan>,
    workspaceIndex: WorkspaceIndex,
): ImportantSymbolSignals {
    val symbolsByScope =
        workspaceIndex.symbols
            .filter { it.kind in TYPE_DECLARATION_KINDS }
            .groupBy { it.build to it.project }
    val entryPoints = mutableSetOf<WorkspaceSymbolIdentity>()
    val cycleParticipants = mutableSetOf<WorkspaceSymbolIdentity>()

    scans.forEach { scan ->
        val scopedSymbols = symbolsByScope[scan.build to scan.projectPath.value].orEmpty()
        val analysis = scan.summary.analysis ?: return@forEach
        analysis.architecture.entryPoints
            .filter { it.kind == ArchitectureEntryPointKind.EXPLICIT }
            .forEach { entryPoint ->
                scopedSymbols
                    .filter { it.qualifiedName == entryPoint.componentId }
                    .singleOrNull()
                    ?.identity
                    ?.let(entryPoints::add)
            }
        analysis.cycles.flatten().distinct().forEach { cycleName ->
            scopedSymbols
                .filter { it.qualifiedName == cycleName || it.name == cycleName }
                .singleOrNull()
                ?.identity
                ?.let(cycleParticipants::add)
        }
    }

    return ImportantSymbolSignals(
        entryPoints = entryPoints,
        cycleParticipants = cycleParticipants,
    )
}

/** Merge resolved source dependencies with deterministic artifact fallbacks. */
internal fun buildWorkspaceBuildEdges(
    workspaceIndex: WorkspaceIndex,
    artifactEdges: List<BuildEdge>,
): List<BuildEdge> {
    val sourceEdges =
        workspaceIndex.relationships.mapNotNull { relationship ->
            val source = relationship.source
            if (
                relationship.kind == WorkspaceRelationshipKind.IMPORT ||
                source == null ||
                source.build == relationship.target.build
            ) {
                null
            } else {
                BuildEdge(source.build, relationship.target.build)
            }
        }
    return (sourceEdges + artifactEdges)
        .distinct()
        .sortedWith(compareBy<BuildEdge> { it.from }.thenBy { it.to })
}

private fun buildCumulativeHubs(
    workspaceIndex: WorkspaceIndex,
    limit: Int,
): List<HubClass> =
    workspaceIndex.usages
        .asSequence()
        .filter { it.symbol.kind in TYPE_DECLARATION_KINDS }
        .mapNotNull { usage ->
            val inbound =
                usage.incoming
                    .asSequence()
                    .filter { it.kind != WorkspaceRelationshipKind.IMPORT }
                    .map { it.toHubInbound() }
                    .distinctBy { it.sourceKey }
                    .sortedWith(
                        compareBy<HubInbound> { it.dependent.name }
                            .thenBy { it.dependent.filePath }
                            .thenBy { it.dependent.line },
                    ).toList()
            if (inbound.isEmpty()) {
                null
            } else {
                CumulativeHub(
                    identity = usage.symbol.identity.value,
                    hub =
                        HubClass(
                            name = usage.symbol.name,
                            dependentCount = inbound.size,
                            role = "",
                            filePath = usage.symbol.projectRelativeFile,
                            line = usage.symbol.declarationLine,
                            dependents = inbound.map { it.dependent },
                            isTest = usage.symbol.sourceSet.contains("test", ignoreCase = true),
                        ),
                )
            }
        }.sortedWith(
            compareByDescending<CumulativeHub> { it.hub.dependentCount }
                .thenBy { it.identity },
        ).take(limit)
        .map { it.hub }
        .toList()

private fun WorkspaceRelationship.toHubInbound(): HubInbound {
    val sourceDeclaration = source ?: sourceEvidence.sourceSymbol
    if (sourceDeclaration != null) {
        return HubInbound(
            sourceKey = sourceDeclaration.identity.value,
            dependent =
                HubDependentRef(
                    sourceDeclaration.name,
                    sourceDeclaration.projectRelativeFile,
                    sourceDeclaration.declarationLine,
                ),
        )
    }

    val evidence = sourceEvidence
    val sourceName =
        evidence.projectRelativeFile
            .substringAfterLast('/')
            .substringBeforeLast('.')
            .ifBlank { "source" }
    return HubInbound(
        sourceKey =
            "${evidence.build}::${evidence.project}::${evidence.sourceSet}::" +
                "${evidence.projectRelativeFile}:${evidence.line}",
        dependent = HubDependentRef(sourceName, evidence.projectRelativeFile, evidence.line),
    )
}

private data class CumulativeHub(
    val identity: String,
    val hub: HubClass,
)

private data class HubInbound(
    val sourceKey: String,
    val dependent: HubDependentRef,
)

private val TYPE_DECLARATION_KINDS =
    setOf(
        SymbolDetailKind.CLASS,
        SymbolDetailKind.INTERFACE,
        SymbolDetailKind.ENUM,
        SymbolDetailKind.DATA_CLASS,
        SymbolDetailKind.OBJECT,
    )

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
