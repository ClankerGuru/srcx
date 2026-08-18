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
import org.gradle.api.tasks.OutputDirectories
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import zone.clanker.gradle.srcx.Srcx
import zone.clanker.gradle.srcx.atlas.AtlasSqliteWriter
import zone.clanker.gradle.srcx.report.AtlasComposeHostRenderer
import zone.clanker.gradle.srcx.report.AtlasStoreRenderer
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
import zone.clanker.gradle.srcx.model.WorkspaceSourceFile
import zone.clanker.gradle.srcx.model.WorkspaceSymbol
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
import zone.clanker.gradle.srcx.scan.ProjectSourceLayout
import zone.clanker.gradle.srcx.scan.WorkspaceIndexBuilder
import zone.clanker.gradle.srcx.snapshot.WorkspaceSnapshotWriter
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

    /** Maximum number of project scans admitted to the shared bounded work plan. */
    @get:Input
    abstract val projectWorkers: Property<Int>

    /** The output directory where context reports are written. */
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    /** Included-build report directories written by this root task. */
    @get:OutputDirectories
    abstract val includedBuildOutputDirectories: ConfigurableFileCollection

    /** Root project name, captured at configuration time. */
    @get:Input
    abstract val rootName: Property<String>

    /** Root project directory, captured at configuration time. */
    @get:Internal
    abstract val rootDir: Property<File>

    /** Project paths and directories: path → absolute dir. */
    @get:Internal
    abstract val projectDirs: MapProperty<String, File>

    /** Configured source-set roots captured for every root-build project. */
    @get:Internal
    abstract val projectSourceLayouts: MapProperty<String, ProjectSourceLayout>

    /** Deterministic source-set/root ownership used by Gradle up-to-date checks. */
    @get:Input
    abstract val sourceLayoutFingerprint: ListProperty<String>

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
        val buildName = rootName.get()
        validateDeclaredReportOutputs(root, outDir, builds, includedBuildOutputDirectories.files)
        val scans = extractWorkspaceScans(root, outDir, buildName, builds)
        val workspaceIndex = WorkspaceIndexBuilder.build(scans.allScans)
        val importantSymbols =
            ImportantSymbolPolicy.select(
                workspaceIndex,
                buildImportantSymbolSignals(scans.allScans, workspaceIndex),
            )
        ReportWriter.writeIncludedBuildOverviewReports(
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

    private fun extractWorkspaceScans(
        root: File,
        outDir: String,
        buildName: String,
        builds: List<IncludedBuildInfo>,
    ): WorkspaceScans {
        val rootInput =
            RootBuildScanInput(
                name = buildName,
                directory = root,
                projects = projectDirs.get(),
                subprojects = subprojectPaths.get(),
                dependencies = projectDeps.get(),
                sourceLayouts = projectSourceLayouts.get(),
            )
        val requests = buildProjectScanRequests(rootInput, builds)
        validateDistinctProjectReportTargets(requests, outDir)
        val completed =
            ReportWriter.runParallelMapped(requests, projectWorkers.get()) { request ->
                val scan = extractProjectScan(request)
                ReportWriter.writeProjectReportToDir(request.reportRoot, scan.summary, outDir)
                CompletedProjectScan(request, scan)
            }
        return assembleWorkspaceScans(buildName, builds, completed)
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
            interfaces = buildInterfaceSummaries(allSummaries, workspaceIndex),
            workspaceIndex = workspaceIndex,
            importantSymbols = importantSymbols,
            sourceFiles = buildWorkspaceSourceFiles(scans),
        )
    }

    private fun writeWorkspaceReports(
        root: File,
        outDir: String,
        report: WorkspaceReport,
    ) {
        val outputDirectory = File(root, outDir).apply { mkdirs() }
        WorkspaceSnapshotWriter.write(outputDirectory, root.canonicalPath, report)
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
        val siteDirectory = File(outputDirectory, Srcx.HTML_SITE_DIR).apply { mkdirs() }
        siteDirectory.listFiles()?.forEach { child -> child.deleteRecursively() }
        val store = AtlasStoreRenderer().contents(report)
        AtlasSqliteWriter().write(siteDirectory.toPath(), store)
        val host = AtlasComposeHostRenderer().document(report.name)
        File(siteDirectory, Srcx.HTML_INDEX_FILE).writeText(host)
        File(siteDirectory, Srcx.HTML_FRAGMENT_FILE).writeText(host)
        copyComposeHost(siteDirectory)
    }

    private fun copyComposeHost(siteDirectory: File) {
        val root = "/zone/clanker/gradle/srcx/report/html/compose/"
        val listing =
            requireNotNull(javaClass.getResourceAsStream(root + "listing.txt")) {
                "Missing Compose Atlas host listing"
            }.use { stream -> stream.readBytes().toString(Charsets.UTF_8) }
        listing
            .lineSequence()
            .map { line -> line.trim() }
            .filter { line -> line.isNotEmpty() && line != Srcx.HTML_INDEX_FILE }
            .forEach { name ->
                val stream =
                    requireNotNull(javaClass.getResourceAsStream(root + name)) {
                        "Missing Compose Atlas host resource: $name"
                    }
                val dest = File(siteDirectory, name)
                dest.parentFile.mkdirs()
                stream.use { input -> dest.outputStream().use { output -> input.copyTo(output) } }
            }
    }

    private fun buildEntryPoints(summaries: List<ProjectSummary>): List<EntryPointSummary> =
        summaries
            .mapNotNull { summary -> summary.analysis?.architecture }
            .flatMap { architecture ->
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

    private fun EntryPointSummary.toMarkdownEntry(): EntryPointsRenderer.ClassifiedEntry =
        EntryPointsRenderer.ClassifiedEntry(
            className = name,
            packageName = packageName,
            kind = EntryPointsRenderer.EntryKind.valueOf(kind.name),
        )

    private fun InterfaceSummary.toMarkdownInterface(): InterfacesRenderer.InterfaceInfo =
        InterfacesRenderer.InterfaceInfo(
            name = name,
            packageName = packageName,
            implementationCount = implementationCount,
            hasMock = hasMock,
            sourceSet = sourceSet,
            build = build,
            project = project,
            qualifiedName = qualifiedName,
            identity = identity,
        )

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

internal fun includedBuildReportDirectories(
    builds: List<IncludedBuildInfo>,
    outputDir: String,
): List<File> = builds.map { build -> File(build.dir, outputDir) }.sortedBy(File::getAbsolutePath)

private fun validateDeclaredReportOutputs(
    root: File,
    outputDir: String,
    builds: List<IncludedBuildInfo>,
    declaredDirectories: Set<File>,
) {
    val rootOutput = File(root, outputDir).canonicalFile
    val expected = includedBuildReportDirectories(builds, outputDir).mapTo(mutableSetOf()) { it.canonicalFile }
    val declared = declaredDirectories.mapTo(mutableSetOf()) { it.canonicalFile }
    require(declared == expected) { "Included-build report outputs must match their declared Gradle directories" }
    require(rootOutput !in expected) { "Root and included-build report outputs must not overlap" }
    val allOutputs = listOf(rootOutput) + expected
    require(
        allOutputs.indices.none { firstIndex ->
            allOutputs.indices.any { secondIndex ->
                firstIndex != secondIndex &&
                    allOutputs[secondIndex].toPath().startsWith(allOutputs[firstIndex].toPath())
            }
        },
    ) { "SRCX report output directories must not contain one another" }
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

/** Preserve exact interface identity while adapting renderer facts into the workspace report model. */
internal fun buildInterfaceSummaries(
    summaries: List<ProjectSummary>,
    workspaceIndex: WorkspaceIndex,
): List<InterfaceSummary> =
    InterfacesRenderer.fromSummaries(summaries, workspaceIndex).map { info ->
        InterfaceSummary(
            name = info.name,
            packageName = info.packageName,
            implementationCount = info.implementationCount,
            hasMock = info.hasMock,
            sourceSet = info.sourceSet,
            build = info.build,
            project = info.project,
            qualifiedName = info.qualifiedName,
            identity = info.identity,
        )
    }

/** Assemble exact source text from every owned scan into deterministic workspace scope order. */
internal fun buildWorkspaceSourceFiles(scans: WorkspaceScans): List<WorkspaceSourceFile> =
    scans.allScans
        .flatMap { scan ->
            scan.files.map { file ->
                WorkspaceSourceFile(
                    build = scan.build,
                    project = scan.projectPath.value,
                    sourceSet = file.sourceSet.value,
                    projectRelativeFile = file.projectRelativeFile,
                    content = file.sourceText,
                )
            }
        }.sortedWith(
            compareBy<WorkspaceSourceFile> { it.build }
                .thenBy { it.project }
                .thenBy { it.sourceSet }
                .thenBy { it.projectRelativeFile },
        )

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
    val antiPatternSymbols = mutableSetOf<WorkspaceSymbolIdentity>()

    scans.forEach { scan ->
        val scopedSymbols = symbolsByScope[scan.build to scan.projectPath.value].orEmpty()
        val analysis = scan.summary.analysis ?: return@forEach
        val componentsById = analysis.architecture.components.groupBy { component -> component.id }

        fun resolveComponent(
            componentId: String,
            exactFilePath: String? = null,
        ): WorkspaceSymbol? {
            val candidates = scopedSymbols.filter { symbol -> symbol.qualifiedName == componentId }
            val component = componentsById[componentId].orEmpty().singleOrNull()
            val findingCandidates =
                exactFilePath
                    ?.replace('\\', '/')
                    ?.let { path ->
                        candidates.filter { symbol -> symbol.projectRelativeFile.replace('\\', '/') == path }
                    }.orEmpty()
            val exactCandidates =
                component
                    ?.let { architectureComponent ->
                        candidates.filter { symbol ->
                            symbol.projectRelativeFile.endsWith(architectureComponent.filePath) &&
                                isTestSourceSet(symbol.sourceSet) == architectureComponent.isTest
                        }
                    }.orEmpty()
            return findingCandidates.singleOrNull() ?: exactCandidates.singleOrNull() ?: candidates.singleOrNull()
        }
        analysis.architecture.entryPoints
            .filter { it.kind == ArchitectureEntryPointKind.EXPLICIT }
            .forEach { entryPoint ->
                resolveComponent(entryPoint.componentId)
                    ?.identity
                    ?.let(entryPoints::add)
            }
        val exactCycleIds =
            analysis.architecture.cycles
                .flatMap { cycle -> cycle.componentIds.dropLast(1) }
                .distinct()
        exactCycleIds.forEach { componentId ->
            resolveComponent(componentId)
                ?.identity
                ?.let(cycleParticipants::add)
        }
        if (exactCycleIds.isEmpty()) {
            analysis.cycles.flatten().distinct().forEach { cycleName ->
                scopedSymbols
                    .filter { symbol -> symbol.qualifiedName == cycleName || symbol.name == cycleName }
                    .singleOrNull()
                    ?.identity
                    ?.let(cycleParticipants::add)
            }
        }
        analysis.findings
            .flatMap { finding ->
                finding.componentIds.mapNotNull { componentId ->
                    resolveComponent(componentId, finding.filePath)
                }
            }.distinctBy { symbol -> symbol.identity }
            .mapTo(antiPatternSymbols) { symbol -> symbol.identity }
    }

    return ImportantSymbolSignals(
        entryPoints = entryPoints,
        cycleParticipants = cycleParticipants,
        antiPatternSymbols = antiPatternSymbols,
    )
}

private fun isTestSourceSet(sourceSet: String): Boolean =
    sourceSet == "test" || sourceSet.startsWith("test") || sourceSet.endsWith("Test")

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
    val sourceLayouts: Map<String, ProjectSourceLayout> = emptyMap(),
) : java.io.Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
