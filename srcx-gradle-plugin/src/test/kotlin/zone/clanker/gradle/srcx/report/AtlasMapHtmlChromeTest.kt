package zone.clanker.gradle.srcx.report

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
                    rendered.document shouldContain "data-srcx-clear-selected"
                    rendered.document shouldContain "data-srcx-filter-toggle"
                    rendered.document shouldContain "data-srcx-map-legend"
                    rendered.document shouldContain "data-srcx-graph-search"
                    rendered.document shouldContain "Current map filters"
                    rendered.document shouldContain "\"name\":\"atlas-root\""
                    rendered.document shouldContain "\"name\":\"atlas-lib\""
                    rendered.document shouldContain "\"name\":\"atlas-plugin\""
                    rendered.document shouldContain "AtlasApp.kt"
                    rendered.document shouldContain "CoreIndex.kt"
                    rendered.document shouldContain "PluginMain.kt"
                    rendered.document shouldContain "\"fileNodeCount\":3"
                    rendered.document shouldContain "\"fileNodeCount\":2"
                    rendered.document shouldContain "\"fileNodeCount\":1"
                    Files.exists(preview) shouldBe true
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
