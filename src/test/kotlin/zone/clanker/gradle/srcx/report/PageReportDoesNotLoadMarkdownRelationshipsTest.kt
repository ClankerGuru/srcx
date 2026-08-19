package zone.clanker.gradle.srcx.report

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import zone.clanker.gradle.srcx.Srcx
import zone.clanker.gradle.srcx.model.ImportantSymbol
import zone.clanker.gradle.srcx.model.ImportantSymbolReason
import zone.clanker.gradle.srcx.model.ReferenceEvidence
import zone.clanker.gradle.srcx.model.ReferenceKind
import zone.clanker.gradle.srcx.model.SymbolDetailKind
import zone.clanker.gradle.srcx.model.WorkspaceIndex
import zone.clanker.gradle.srcx.model.WorkspaceReference
import zone.clanker.gradle.srcx.model.WorkspaceRelationship
import zone.clanker.gradle.srcx.model.WorkspaceRelationshipKind
import zone.clanker.gradle.srcx.model.WorkspaceReport
import zone.clanker.gradle.srcx.model.WorkspaceSourceFile
import zone.clanker.gradle.srcx.model.WorkspaceSymbol
import zone.clanker.gradle.srcx.model.WorkspaceSymbolUsage
import java.io.File

/**
 * The HTML page/report path bakes relationships from the typed WorkspaceReport.
 * It does not parse relationship markdown shards.
 */
class PageReportDoesNotLoadMarkdownRelationshipsTest :
    BehaviorSpec({
        given("planted relationship markdown that is not in the typed report") {
            val plantedToken = "FAKE_MARKDOWN_ONLY_RELATIONSHIP_TOKEN"
            val report = typedRelationshipReport()
            val graph = buildWorkspaceArchitectureGraph(report)
            val outputDirectory = temporaryDirectory("srcx-markdown-independence")
            ReportWriter.writeWorkspaceRelationshipReports(
                outputDirectory,
                WorkspaceRelationshipsRenderer().render(report),
            )
            File(outputDirectory, "relationships/planted-fake.md").writeText(
                """
                # $plantedToken

                | Source declaration | Relationship kind |
                |--------------------|-------------------|
                | $plantedToken | imports |
                """.trimIndent(),
            )

            `when`("the HTML page and fragment are rendered from the typed report") {
                val rendered = WorkspaceHtmlRenderer().render(report)
                val siteDirectory = File(outputDirectory, Srcx.HTML_SITE_DIR).apply { mkdirs() }
                File(siteDirectory, Srcx.HTML_INDEX_FILE).writeText(rendered.document)
                File(siteDirectory, Srcx.HTML_FRAGMENT_FILE).writeText(rendered.fragment)
                val html = File(siteDirectory, Srcx.HTML_INDEX_FILE).readText()
                val fragment = File(siteDirectory, Srcx.HTML_FRAGMENT_FILE).readText()

                then("the island is graph.toJson() from the typed report, not the planted markdown") {
                    html.architectureGraphData() shouldBe graph.toJson()
                    fragment.architectureGraphData() shouldBe graph.toJson()
                    html shouldContain "\"kind\":\"${WorkspaceRelationshipKind.CALL.name}\""
                    html shouldNotContain plantedToken
                    fragment shouldNotContain plantedToken
                }
            }

            `when`("the page and report sources are inspected") {
                val pageRenderer =
                    File(
                        "src/main/kotlin/zone/clanker/gradle/srcx/report/WorkspaceHtmlRenderer.kt",
                    ).readText()
                val architectureHtml =
                    File(
                        "src/main/kotlin/zone/clanker/gradle/srcx/report/WorkspaceArchitectureHtmlRenderer.kt",
                    ).readText()
                val generateWriter =
                    File("src/main/kotlin/zone/clanker/gradle/srcx/task/ContextTask.kt").readText()
                val script =
                    WorkspaceHtmlResourceRenderer.readClasspathResource(
                        WorkspaceHtmlResourceRenderer.ARCHITECTURE_GRAPH_SCRIPT,
                    )

                then("the page renderer loads the typed report, not relationship markdown") {
                    pageRenderer shouldContain "fun render(report: WorkspaceReport)"
                    pageRenderer shouldContain "buildWorkspaceArchitectureGraph(report)"
                    pageRenderer shouldNotContain "relationships/"
                    pageRenderer shouldNotContain ".md"
                    architectureHtml shouldContain "append(graph.toJson())"
                    architectureHtml shouldContain "data-srcx-architecture-data"
                    architectureHtml shouldNotContain "relationships/"
                    generateWriter shouldContain "WorkspaceHtmlRenderer().render(report)"
                    generateWriter shouldNotContain "readText()"
                }

                then("the browser script reads the inlined island and does not fetch markdown") {
                    script shouldContain "root.querySelector(\"[data-srcx-architecture-data]\")"
                    script shouldNotContain "relationships/"
                    script shouldNotContain "relationships/index.md"
                    script.lowercase() shouldNotContain "fetch("
                }
            }
        }
    })

private fun String.architectureGraphData(): String {
    val marker = "<script type=\"application/json\" data-srcx-architecture-data>"
    check(marker in this) { "Architecture graph data was not generated" }
    return substringAfter(marker).substringBefore("</script>")
}

private fun typedRelationshipReport(): WorkspaceReport {
    val source = typedSymbol("Reader", SymbolDetailKind.CLASS)
    val target = typedSymbol("Book", SymbolDetailKind.CLASS)
    val relationship = typedCall(source, target)
    return typedWorkspaceReport(source, target, relationship)
}

private fun typedCall(
    source: WorkspaceSymbol,
    target: WorkspaceSymbol,
): WorkspaceRelationship =
    WorkspaceRelationship(
        source = source,
        target = target,
        kind = WorkspaceRelationshipKind.CALL,
        sourceEvidence =
            WorkspaceReference(
                build = source.build,
                project = source.project,
                sourceSet = source.sourceSet,
                sourceSymbol = source,
                targetName = target.name,
                targetQualifiedName = target.qualifiedName,
                kind = ReferenceKind.CALL,
                projectRelativeFile = source.projectRelativeFile,
                line = 6,
                context = "book.open()",
                evidence = ReferenceEvidence.DIRECT,
            ),
        evidence = ReferenceEvidence.DIRECT,
    )

private fun typedWorkspaceReport(
    source: WorkspaceSymbol,
    target: WorkspaceSymbol,
    relationship: WorkspaceRelationship,
): WorkspaceReport =
    WorkspaceReport(
        name = "markdown-independence",
        rootProjects = emptyList(),
        includedBuilds = emptyList(),
        buildEdges = emptyList(),
        aggregateAnalysis = null,
        entryPoints = emptyList(),
        interfaces = emptyList(),
        workspaceIndex =
            WorkspaceIndex(
                symbols = listOf(source, target),
                references = listOf(relationship.sourceEvidence),
                relationships = listOf(relationship),
            ),
        importantSymbols =
            listOf(
                ImportantSymbol(
                    symbol = source,
                    reasons = listOf(ImportantSymbolReason.ENTRY_POINT),
                    score = ImportantSymbolReason.ENTRY_POINT.score,
                    usage = WorkspaceSymbolUsage(source, emptyList(), listOf(relationship)),
                ),
            ),
        sourceFiles =
            listOf(
                typedSourceFile(target, "class Book\n"),
                typedSourceFile(source, "class Reader\n"),
            ),
    )

private fun typedSourceFile(
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

private fun typedSymbol(
    name: String,
    kind: SymbolDetailKind,
): WorkspaceSymbol =
    WorkspaceSymbol(
        build = "root",
        project = ":app",
        sourceSet = "main",
        name = name,
        qualifiedName = "example.$name",
        kind = kind,
        projectRelativeFile = "src/main/kotlin/example/$name.kt",
        declarationLine = 1,
    )

private fun temporaryDirectory(prefix: String): File =
    File.createTempFile(prefix, "").apply {
        check(delete())
        check(mkdirs())
        deleteOnExit()
    }
