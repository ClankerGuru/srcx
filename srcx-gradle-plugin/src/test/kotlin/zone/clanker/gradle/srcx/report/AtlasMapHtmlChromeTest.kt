package zone.clanker.gradle.srcx.report

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import zone.clanker.gradle.srcx.model.AnalysisSummary
import zone.clanker.gradle.srcx.model.ArchitectureSummary
import zone.clanker.gradle.srcx.model.BuildEdge
import zone.clanker.gradle.srcx.model.FilePath
import zone.clanker.gradle.srcx.model.Finding
import zone.clanker.gradle.srcx.model.FindingSeverity
import zone.clanker.gradle.srcx.model.ImportantSymbol
import zone.clanker.gradle.srcx.model.ImportantSymbolReason
import zone.clanker.gradle.srcx.model.IncludedBuildSummary
import zone.clanker.gradle.srcx.model.PackageName
import zone.clanker.gradle.srcx.model.ProjectPath
import zone.clanker.gradle.srcx.model.ProjectSummary
import zone.clanker.gradle.srcx.model.ReferenceEvidence
import zone.clanker.gradle.srcx.model.ReferenceKind
import zone.clanker.gradle.srcx.model.SourceSetName
import zone.clanker.gradle.srcx.model.SourceSetSummary
import zone.clanker.gradle.srcx.model.SymbolDetailKind
import zone.clanker.gradle.srcx.model.SymbolEntry
import zone.clanker.gradle.srcx.model.SymbolKind
import zone.clanker.gradle.srcx.model.SymbolName
import zone.clanker.gradle.srcx.model.WorkspaceIndex
import zone.clanker.gradle.srcx.model.WorkspaceReference
import zone.clanker.gradle.srcx.model.WorkspaceRelationship
import zone.clanker.gradle.srcx.model.WorkspaceRelationshipKind
import zone.clanker.gradle.srcx.model.WorkspaceReport
import zone.clanker.gradle.srcx.model.WorkspaceSourceFile
import zone.clanker.gradle.srcx.model.WorkspaceSymbol
import zone.clanker.gradle.srcx.model.WorkspaceSymbolUsage
import java.nio.file.Files
import java.nio.file.Path

class AtlasMapHtmlChromeTest :
    BehaviorSpec({
        given("a multi-build workspace report") {
            `when`("it is rendered as a standalone atlas document") {
                val report = multiBuildAtlasReport()
                val rendered = WorkspaceHtmlRenderer().render(report)
                val preview = writeAtlasPreview(rendered.document)

                then("the map chrome is present and the graph JSON keeps more than one build") {
                    rendered.document shouldContain "data-srcx-architecture-graph"
                    rendered.document shouldContain "data-srcx-atlas-state=\"three-builds-with-problems\""
                    rendered.document shouldContain "data-srcx-clear-selected"
                    rendered.document shouldNotContain "data-srcx-filter-toggle"
                    rendered.document shouldNotContain "data-srcx-filter-close"
                    rendered.document shouldContain "data-srcx-map-legend"
                    rendered.document shouldContain "data-srcx-graph-search"
                    rendered.document shouldContain "data-srcx-find-chrome"
                    rendered.document shouldContain "data-srcx-find-kind=\"class\""
                    rendered.document shouldContain "data-srcx-find-kind=\"sealed-class\""
                    rendered.document shouldContain "data-srcx-used-at-least"
                    rendered.document shouldContain "data-srcx-legend-toggle"
                    rendered.document shouldNotContain "data-srcx-find-kind=\"json\""
                    rendered.document shouldContain "data-srcx-scope-toggle"
                    rendered.document shouldContain "srcx-dashboard__architecture-search"
                    rendered.document shouldContain "data-srcx-graph-navigator"
                    rendered.document shouldContain "FILE SOURCE"
                    rendered.document shouldNotContain "data-srcx-graph-navigator hidden"
                    rendered.document shouldNotContain "Workspace <strong>atlas.</strong>"
                    rendered.document shouldNotContain "Explore files first ·"
                    rendered.document shouldContain "architecture-filter--projects\" hidden"
                    rendered.document shouldContain "architecture-filter--source-sets\" hidden"
                    rendered.document shouldNotContain "data-srcx-graph-navigator hidden"
                    rendered.document shouldContain "AtlasApp.kt"
                    rendered.document shouldContain "CoreIndex.kt"
                    rendered.document shouldContain "PluginMain.kt"
                    val graphJson = buildWorkspaceArchitectureGraph(report).toJson()
                    graphJson shouldContain "\"name\":\"atlas-root\""
                    graphJson shouldContain "\"name\":\"atlas-lib\""
                    graphJson shouldContain "\"name\":\"atlas-plugin\""
                    graphJson shouldContain "\"fileNodeCount\":3"
                    graphJson shouldContain "\"fileNodeCount\":2"
                    graphJson shouldContain "\"fileNodeCount\":1"
                    rendered.document shouldNotContain "data-srcx-architecture-data"
                    rendered.document shouldNotContain "\"availableNodes\":[{"
                    val styles = rendered.styles
                    styles shouldContain
                        ".srcx-theme .srcx-dashboard__architecture-graph\n" +
                        "    .srcx-dashboard__architecture-navigator"
                    styles shouldContain "position: absolute"
                    styles shouldContain "inset: 0 0 auto 0"
                    styles shouldContain "overflow-x: auto"
                    styles shouldContain "overflow-x: hidden"
                    styles shouldContain "@media (max-width: 390px)"
                    styles shouldContain "@media (max-width: 768px)"
                    styles shouldContain "@media (max-width: 1024px)"
                    styles shouldContain "@media (max-width: 1440px)"
                    styles shouldContain "@container (max-width: 390px)"
                    styles shouldContain "@container (max-width: 768px)"
                    styles shouldContain "@container (max-width: 1024px)"
                    styles shouldContain "@container (max-width: 1440px)"
                    styles shouldContain
                        "block-size: auto !important"
                    styles shouldContain
                        "min-block-size: 0 !important"
                    styles shouldContain
                        ".srcx-dashboard__architecture-graph:not([data-srcx-fullscreen=\"true\"])"
                    styles shouldContain
                        ".srcx-dashboard__architecture-graph[data-srcx-fullscreen=\"true\"]\n" +
                        "    [data-srcx-graph-action],"
                    styles shouldContain
                        "z-index: 2147483647"
                    styles shouldContain
                        "color: transparent !important"
                    styles shouldContain
                        "-webkit-text-fill-color: transparent !important"
                    styles shouldContain
                        "max-inline-size: 44px !important"
                    Files.exists(preview) shouldBe true
                }
            }
        }

        given("an empty typed workspace report") {
            `when`("it is rendered as a standalone atlas document") {
                val rendered = WorkspaceHtmlRenderer().render(emptyAtlasReport())

                then("the atlas publishes the empty data state from the scan") {
                    rendered.document shouldContain "data-srcx-atlas-state=\"empty\""
                    rendered.document shouldContain "Index a build"
                    rendered.document shouldContain
                        "Add a Kotlin/Gradle build to this workspace and run srcx-context."
                    rendered.document.substringBefore("<script") shouldNotContain "Box select"
                    rendered.document shouldContain "FILE SOURCE"
                    rendered.document shouldNotContain "Shift-drag"
                }
            }
        }

        given("a single-build workspace report") {
            `when`("it is rendered as a standalone atlas document") {
                val rendered = WorkspaceHtmlRenderer().render(oneBuildAtlasReport())

                then("the atlas publishes the one-build data state from the scan") {
                    rendered.document shouldContain "data-srcx-atlas-state=\"one-build\""
                    rendered.document shouldContain "AtlasApp.kt"
                    val graphJson = buildWorkspaceArchitectureGraph(oneBuildAtlasReport()).toJson()
                    graphJson shouldContain "\"name\":\"atlas-root\""
                    graphJson shouldNotContain "\"name\":\"atlas-lib\""
                }
            }
        }
    })

private fun writeAtlasPreview(document: String): Path {
    val directory = Path.of("build", "atlas-preview")
    Files.createDirectories(directory)
    val target = directory.resolve("index.html")
    Files.writeString(target, document)
    return target
}

private fun emptyAtlasReport(): WorkspaceReport =
    WorkspaceReport("empty-workspace", emptyList(), emptyList(), emptyList(), null, emptyList(), emptyList())

private fun oneBuildAtlasReport(): WorkspaceReport {
    val app =
        atlasSymbol(
            "atlas-root",
            ":consumer",
            "AtlasApp",
            "demo.atlas.AtlasApp",
            "src/main/kotlin/demo/atlas/AtlasApp.kt",
        )
    val bind =
        atlasSymbol(
            "atlas-root",
            ":consumer",
            "AtlasBind",
            "demo.atlas.AtlasBind",
            "src/main/kotlin/demo/atlas/AtlasBind.kt",
        )
    val symbols = listOf(app, bind)
    val relationships =
        listOf(
            atlasRelationship(app, bind, WorkspaceRelationshipKind.CALL, ReferenceKind.CALL, 10, "bind.attach()"),
        )
    return WorkspaceReport(
        name = "atlas-root",
        rootProjects = listOf(atlasProject(":consumer", symbols, null)),
        includedBuilds = emptyList(),
        buildEdges = emptyList(),
        aggregateAnalysis = AnalysisSummary(emptyList(), emptyList(), emptyList()),
        entryPoints = emptyList(),
        interfaces = emptyList(),
        workspaceIndex =
            WorkspaceIndex(
                symbols = symbols,
                references = relationships.map { it.sourceEvidence },
                relationships = relationships,
                usages = symbols.map { symbol -> atlasUsage(symbol, relationships) },
            ),
        importantSymbols =
            listOf(
                ImportantSymbol(
                    app,
                    listOf(ImportantSymbolReason.ENTRY_POINT),
                    ImportantSymbolReason.ENTRY_POINT.score,
                    atlasUsage(app, relationships),
                ),
            ),
        sourceFiles =
            symbols
                .map(::atlasSourceFile)
                .sortedWith(compareBy({ it.build }, { it.project }, { it.sourceSet }, { it.projectRelativeFile })),
    )
}

@Suppress("LongMethod")
private fun multiBuildAtlasReport(): WorkspaceReport {
    val app = atlasSymbol("atlas-root", ":consumer", "AtlasApp", "demo.atlas.AtlasApp", "src/main/kotlin/demo/atlas/AtlasApp.kt")
    val bind = atlasSymbol("atlas-root", ":consumer", "AtlasBind", "demo.atlas.AtlasBind", "src/main/kotlin/demo/atlas/AtlasBind.kt")
    val desktop = atlasSymbol("atlas-root", ":desktop", "DesktopShell", "demo.desktop.DesktopShell", "src/main/kotlin/demo/desktop/DesktopShell.kt")
    val core = atlasSymbol("atlas-lib", ":core", "CoreIndex", "demo.core.CoreIndex", "src/main/kotlin/demo/core/CoreIndex.kt")
    val graph = atlasSymbol("atlas-lib", ":core", "CoreGraph", "demo.core.CoreGraph", "src/main/kotlin/demo/core/CoreGraph.kt")
    val plugin = atlasSymbol("atlas-plugin", ":plugin", "PluginMain", "demo.plugin.PluginMain", "src/main/kotlin/demo/plugin/PluginMain.kt")
    val symbols = listOf(app, bind, desktop, core, graph, plugin)
    val relationships =
        listOf(
            atlasRelationship(app, bind, WorkspaceRelationshipKind.CALL, ReferenceKind.CALL, 10, "bind.attach()"),
            atlasRelationship(app, core, WorkspaceRelationshipKind.TYPE_REFERENCE, ReferenceKind.TYPE_REF, 14, "val index: CoreIndex"),
            atlasRelationship(bind, graph, WorkspaceRelationshipKind.CALL, ReferenceKind.CALL, 8, "graph.walk()"),
            atlasRelationship(desktop, app, WorkspaceRelationshipKind.CALL, ReferenceKind.CALL, 6, "AtlasApp.start()"),
            atlasRelationship(plugin, core, WorkspaceRelationshipKind.TYPE_REFERENCE, ReferenceKind.TYPE_REF, 12, "fun apply(index: CoreIndex)"),
            atlasRelationship(graph, core, WorkspaceRelationshipKind.TYPE_REFERENCE, ReferenceKind.TYPE_REF, 4, "class CoreGraph(val index: CoreIndex)"),
        )
    val finding =
        Finding(
            severity = FindingSeverity.WARNING,
            message = "AtlasApp talks across builds",
            suggestion = "Keep the consumer boundary explicit",
            filePath = app.projectRelativeFile,
            line = 14,
        )
    return WorkspaceReport(
        name = "atlas-root",
        rootProjects =
            listOf(
                atlasProject(":consumer", listOf(app, bind), finding),
                atlasProject(":desktop", listOf(desktop), null),
            ),
        includedBuilds =
            listOf(
                IncludedBuildSummary("atlas-lib", "../atlas-lib", listOf(atlasProject(":core", listOf(core, graph), null))),
                IncludedBuildSummary("atlas-plugin", "../atlas-plugin", listOf(atlasProject(":plugin", listOf(plugin), null))),
            ),
        buildEdges =
            listOf(
                BuildEdge("atlas-root", "atlas-lib"),
                BuildEdge("atlas-plugin", "atlas-lib"),
            ),
        aggregateAnalysis = AnalysisSummary(listOf(finding), emptyList(), emptyList()),
        entryPoints = emptyList(),
        interfaces = emptyList(),
        workspaceIndex =
            WorkspaceIndex(
                symbols = symbols,
                references = relationships.map { it.sourceEvidence },
                relationships = relationships,
                usages = symbols.map { symbol -> atlasUsage(symbol, relationships) },
            ),
        importantSymbols =
            listOf(
                ImportantSymbol(app, listOf(ImportantSymbolReason.ENTRY_POINT), ImportantSymbolReason.ENTRY_POINT.score, atlasUsage(app, relationships)),
                ImportantSymbol(core, listOf(ImportantSymbolReason.CROSS_BUILD_INBOUND), ImportantSymbolReason.CROSS_BUILD_INBOUND.score, atlasUsage(core, relationships)),
            ),
        sourceFiles =
            symbols
                .map(::atlasSourceFile)
                .sortedWith(compareBy({ it.build }, { it.project }, { it.sourceSet }, { it.projectRelativeFile })),
    )
}

private fun atlasProject(
    path: String,
    symbols: List<WorkspaceSymbol>,
    finding: Finding?,
): ProjectSummary {
    val entries =
        symbols.map { symbol ->
            SymbolEntry(SymbolName(symbol.name), SymbolKind.CLASS, PackageName(symbol.qualifiedName.substringBeforeLast('.')), FilePath(symbol.projectRelativeFile), 1)
        }
    return ProjectSummary(
        projectPath = ProjectPath(path),
        symbols = entries,
        dependencies = emptyList(),
        buildFile = "build.gradle.kts",
        sourceDirs = emptyList(),
        subprojects = emptyList(),
        sourceSets = listOf(SourceSetSummary(SourceSetName("main"), entries, listOf("src/main/kotlin"))),
        analysis = AnalysisSummary(listOfNotNull(finding), emptyList(), emptyList(), ArchitectureSummary()),
    )
}

private fun atlasSymbol(
    build: String,
    project: String,
    name: String,
    qualifiedName: String,
    file: String,
): WorkspaceSymbol =
    WorkspaceSymbol(
        build = build,
        project = project,
        sourceSet = "main",
        name = name,
        qualifiedName = qualifiedName,
        kind = SymbolDetailKind.CLASS,
        projectRelativeFile = file,
        declarationLine = 1,
    )

@Suppress("LongParameterList")
private fun atlasRelationship(
    source: WorkspaceSymbol,
    target: WorkspaceSymbol,
    kind: WorkspaceRelationshipKind,
    referenceKind: ReferenceKind,
    line: Int,
    context: String,
): WorkspaceRelationship =
    WorkspaceRelationship(
        source = source,
        target = target,
        kind = kind,
        sourceEvidence =
            WorkspaceReference(
                build = source.build,
                project = source.project,
                sourceSet = source.sourceSet,
                sourceSymbol = source,
                targetName = target.name,
                targetQualifiedName = target.qualifiedName,
                kind = referenceKind,
                projectRelativeFile = source.projectRelativeFile,
                line = line,
                context = context,
                evidence = ReferenceEvidence.DIRECT,
            ),
        evidence = ReferenceEvidence.DIRECT,
    )

private fun atlasUsage(
    symbol: WorkspaceSymbol,
    relationships: List<WorkspaceRelationship>,
): WorkspaceSymbolUsage =
    WorkspaceSymbolUsage(
        symbol,
        relationships.filter { relationship -> relationship.targetIdentity == symbol.identity },
        relationships.filter { relationship -> relationship.sourceIdentity == symbol.identity },
    )

private fun atlasSourceFile(symbol: WorkspaceSymbol): WorkspaceSourceFile =
    WorkspaceSourceFile(
        build = symbol.build,
        project = symbol.project,
        sourceSet = symbol.sourceSet,
        projectRelativeFile = symbol.projectRelativeFile,
        content = "class ${symbol.name}\n",
    )
