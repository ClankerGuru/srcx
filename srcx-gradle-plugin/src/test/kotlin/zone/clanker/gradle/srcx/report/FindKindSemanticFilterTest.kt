package zone.clanker.gradle.srcx.report

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import zone.clanker.gradle.srcx.model.AnalysisSummary
import zone.clanker.gradle.srcx.model.ArchitectureSummary
import zone.clanker.gradle.srcx.model.FilePath
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

class FindKindSemanticFilterTest :
    BehaviorSpec({
        given("a mixed class, interface, member, and file workspace") {
            val report = mixedKindReport()
            val rendered = WorkspaceHtmlRenderer().render(report)
            val script =
                WorkspaceHtmlResourceRenderer.readClasspathResource(
                    WorkspaceHtmlResourceRenderer.ARCHITECTURE_GRAPH_SCRIPT,
                )
            val graph = parseArchitectureData(buildWorkspaceArchitectureGraph(report).toJson())

            `when`("FIND kind predicates are applied to the rendered seed") {
                val symbols = graph.symbols
                val files = graph.files

                then("class isolates concrete class symbols and excludes interface, function, and property") {
                    symbols.filter { it.matches("class") }.map { it.name } shouldContainExactly listOf("AlphaService")
                    symbols.filter { it.matches("interface") }.map { it.name } shouldContainExactly listOf("BetaPort")
                    symbols.filter { it.matches("function") }.map { it.name } shouldContainExactly listOf("runJob")
                    symbols.filter { it.matches("property") }.map { it.name } shouldContainExactly listOf("limit")
                    symbols.none { it.matches("class") && it.matches("interface") } shouldBe true
                    symbols.none { it.matches("function") && it.matches("property") } shouldBe true
                }

                then("file type chips isolate files and leave symbols out of the file keep set") {
                    files.filter { it.matchesFile("kotlin") }.map { it.path } shouldContainExactly
                        listOf("src/main/kotlin/demo/AlphaService.kt")
                    files.filter { it.matchesFile("java") }.map { it.path } shouldContainExactly
                        listOf("src/main/java/demo/BetaPort.java")
                    files.filter { it.matchesFile("file") }.size shouldBe files.size
                    symbols.none { it.entityType == "file" } shouldBe true
                }

                then("the script switches TYPE/MEMBER chips to symbols and restores the prior lens on Clear") {
                    script shouldContain "if (findKindWantsSymbols()) setGraphView(\"symbols\")"
                    script shouldContain "else setGraphView(\"files\")"
                    script shouldContain "if (restore) setGraphView(restore)"
                    script shouldContain "state.findRestoreView = state.view"
                    script shouldContain "if (kindId === \"class\") return semantic === \"CONCRETE_CLASS\""
                }
            }
        }
    })

private data class KindNode(
    val name: String,
    val kind: String,
    val semantic: String,
    val path: String,
    val entityType: String,
) {
    fun matches(kindId: String): Boolean = subjectMatchesFindKind(semantic, kind, path, kindId)

    fun matchesFile(kindId: String): Boolean = subjectMatchesFindKind("OTHER", "", path, kindId)
}

private data class KindGraph(
    val symbols: List<KindNode>,
    val files: List<KindNode>,
)

private fun subjectMatchesFindKind(
    semantic: String,
    kind: String,
    path: String,
    kindId: String,
): Boolean {
    val normalized = kind.lowercase()
    val language =
        when {
            path.lowercase().endsWith(".gradle.kts") -> "gradle-kts"
            path.lowercase().endsWith(".kt") -> "kotlin"
            path.lowercase().endsWith(".java") -> "java"
            else -> "file"
        }
    return when (kindId) {
        "class" -> semantic == "CONCRETE_CLASS"
        "interface" -> semantic == "INTERFACE"
        "function" -> normalized == "fun" || normalized == "function" || normalized == "method"
        "property" -> normalized == "val/var" || normalized == "property" || normalized == "val"
        "file" -> path.isNotBlank()
        "kotlin", "java", "gradle-kts" -> language == kindId
        else -> false
    }
}

private fun parseArchitectureData(raw: String): KindGraph {
    val root = Json.parseToJsonElement(raw).jsonObject
    val symbols =
        symbolNodes(root).map { node ->
            KindNode(
                name = node.string("name"),
                kind = node.string("kind"),
                semantic = node.string("declarationSemantic"),
                path = node.string("file").ifBlank { node.string("path") },
                entityType = "symbol",
            )
        }
    val files =
        root.array("fileNodes").map { element ->
            val node = element.jsonObject
            KindNode(
                name = node.string("name"),
                kind = "file",
                semantic = "OTHER",
                path = node.string("path"),
                entityType = "file",
            )
        }
    return KindGraph(symbols = symbols, files = files)
}

private fun symbolNodes(root: JsonObject): List<JsonObject> {
    val fromFiles =
        root.array("fileNodes").flatMap { file ->
            file.jsonObject.array("symbols").map { it.jsonObject }
        }
    if (fromFiles.isNotEmpty()) return fromFiles
    return root.array("nodes").map { it.jsonObject }
}

private fun JsonObject.string(key: String): String = this[key]?.jsonPrimitive?.content.orEmpty()

private fun JsonObject.array(key: String) = this[key]?.jsonArray.orEmpty()

private fun mixedKindReport(): WorkspaceReport {
    val service =
        WorkspaceSymbol(
            build = "kind-lab",
            project = ":app",
            sourceSet = "main",
            name = "AlphaService",
            qualifiedName = "demo.AlphaService",
            kind = SymbolDetailKind.CLASS,
            projectRelativeFile = "src/main/kotlin/demo/AlphaService.kt",
            declarationLine = 1,
        )
    val port =
        WorkspaceSymbol(
            build = "kind-lab",
            project = ":app",
            sourceSet = "main",
            name = "BetaPort",
            qualifiedName = "demo.BetaPort",
            kind = SymbolDetailKind.INTERFACE,
            projectRelativeFile = "src/main/java/demo/BetaPort.java",
            declarationLine = 1,
        )
    val function =
        WorkspaceSymbol(
            build = "kind-lab",
            project = ":app",
            sourceSet = "main",
            name = "runJob",
            qualifiedName = "demo.AlphaService.runJob",
            kind = SymbolDetailKind.FUNCTION,
            projectRelativeFile = "src/main/kotlin/demo/AlphaService.kt",
            declarationLine = 8,
        )
    val property =
        WorkspaceSymbol(
            build = "kind-lab",
            project = ":app",
            sourceSet = "main",
            name = "limit",
            qualifiedName = "demo.AlphaService.limit",
            kind = SymbolDetailKind.PROPERTY,
            projectRelativeFile = "src/main/kotlin/demo/AlphaService.kt",
            declarationLine = 4,
        )
    val symbols = listOf(service, port, function, property)
    val relationships =
        listOf(
            kindRelationship(function, port, WorkspaceRelationshipKind.CALL, ReferenceKind.CALL, 8, "port.open()"),
            kindRelationship(property, port, WorkspaceRelationshipKind.TYPE_REFERENCE, ReferenceKind.TYPE_REF, 4, "val limit: BetaPort"),
        )
    return WorkspaceReport(
        name = "kind-lab",
        rootProjects = listOf(kindProject(symbols)),
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
                usages = symbols.map { symbol -> kindUsage(symbol, relationships) },
            ),
        importantSymbols = emptyList(),
        sourceFiles =
            listOf(
                kindSourceFile(port, "interface BetaPort {}\n"),
                kindSourceFile(service, "class AlphaService {\n    val limit = 1\n    fun runJob() {}\n}\n"),
            ),
    )
}

private fun kindProject(symbols: List<WorkspaceSymbol>): ProjectSummary {
    val entries =
        symbols.map { symbol ->
            SymbolEntry(
                SymbolName(symbol.name),
                when (symbol.kind) {
                    SymbolDetailKind.FUNCTION -> SymbolKind.FUNCTION
                    SymbolDetailKind.PROPERTY -> SymbolKind.PROPERTY
                    else -> SymbolKind.CLASS
                },
                PackageName(symbol.qualifiedName.substringBeforeLast('.')),
                FilePath(symbol.projectRelativeFile),
                symbol.declarationLine,
            )
        }
    return ProjectSummary(
        projectPath = ProjectPath(":app"),
        symbols = entries,
        dependencies = emptyList(),
        buildFile = "build.gradle.kts",
        sourceDirs = emptyList(),
        subprojects = emptyList(),
        sourceSets = listOf(SourceSetSummary(SourceSetName("main"), entries, listOf("src/main/kotlin"))),
        analysis = AnalysisSummary(emptyList(), emptyList(), emptyList(), ArchitectureSummary()),
    )
}

private fun kindRelationship(
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

private fun kindUsage(
    symbol: WorkspaceSymbol,
    relationships: List<WorkspaceRelationship>,
): WorkspaceSymbolUsage =
    WorkspaceSymbolUsage(
        symbol = symbol,
        incoming = relationships.filter { it.target.identity == symbol.identity },
        outgoing = relationships.filter { it.source?.identity == symbol.identity },
    )

private fun kindSourceFile(
    symbol: WorkspaceSymbol,
    content: String,
): WorkspaceSourceFile =
    WorkspaceSourceFile(
        build = symbol.build,
        project = symbol.project,
        sourceSet = symbol.sourceSet,
        projectRelativeFile = symbol.projectRelativeFile,
        content = content,
    )
