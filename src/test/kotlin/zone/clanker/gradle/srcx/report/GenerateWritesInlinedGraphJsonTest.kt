package zone.clanker.gradle.srcx.report

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.file.shouldExist
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
 * Generate writes the atlas graph as an HTML JSON island.
 * A sidecar `architecture.json` / `graph.json` is not the page payload.
 */
class GenerateWritesInlinedGraphJsonTest :
    BehaviorSpec({
        given("generate writes the static site from a typed workspace report") {
            val report = inlinedGraphReport()
            val graph = buildWorkspaceArchitectureGraph(report)
            val rendered = WorkspaceHtmlRenderer().render(report)
            val outputDirectory = temporaryDirectory("srcx-inlined-graph")
            val siteDirectory = File(outputDirectory, Srcx.HTML_SITE_DIR).apply { mkdirs() }
            File(siteDirectory, Srcx.HTML_INDEX_FILE).writeText(rendered.document)
            File(siteDirectory, Srcx.HTML_FRAGMENT_FILE).writeText(rendered.fragment)

            `when`("the written HTML is inspected") {
                val index = File(siteDirectory, Srcx.HTML_INDEX_FILE)
                val fragment = File(siteDirectory, Srcx.HTML_FRAGMENT_FILE)
                val island = """<script type="application/json" data-srcx-architecture-data>"""

                then("index.html embeds graph.toJson() in data-srcx-architecture-data") {
                    index.shouldExist()
                    val html = index.readText()
                    html shouldContain island
                    html.architectureGraphData() shouldBe graph.toJson()
                    html shouldContain "\"defaultView\":\"files\""
                    html shouldContain "\"kind\":\"${WorkspaceRelationshipKind.CALL.name}\""
                }

                then("report.html carries the same inlined graph JSON") {
                    fragment.shouldExist()
                    fragment.readText().architectureGraphData() shouldBe graph.toJson()
                }

                then("the site directory has no graph JSON sidecar") {
                    siteDirectory
                        .listFiles()
                        .orEmpty()
                        .filter { file -> file.isFile }
                        .map { file -> file.name }
                        .sorted() shouldContainExactly
                        listOf(Srcx.HTML_INDEX_FILE, Srcx.HTML_FRAGMENT_FILE)
                    siteDirectory
                        .walkTopDown()
                        .filter { file -> file.isFile && file.extension == "json" }
                        .toList() shouldBe emptyList()
                    index.readText() shouldNotContain "architecture.json"
                    index.readText() shouldNotContain "graph.json"
                }
            }

            `when`("the generate writer is inspected") {
                val writer =
                    File("src/main/kotlin/zone/clanker/gradle/srcx/task/ContextTask.kt").readText()

                then("writeHtmlDocumentation writes HTML files, not a sidecar JSON payload") {
                    writer shouldContain "private fun writeHtmlDocumentation("
                    writer shouldContain "WorkspaceHtmlRenderer().render(report)"
                    writer shouldContain "File(siteDirectory, Srcx.HTML_INDEX_FILE).writeText(rendered.document)"
                    writer shouldContain "File(siteDirectory, Srcx.HTML_FRAGMENT_FILE).writeText(rendered.fragment)"
                    writer shouldNotContain "architecture.json"
                    writer shouldNotContain "graph.json"
                    writer shouldNotContain "toJson()).writeText"
                }
            }
        }
    })

private fun String.architectureGraphData(): String {
    val marker = "<script type=\"application/json\" data-srcx-architecture-data>"
    check(marker in this) { "Architecture graph data was not generated" }
    return substringAfter(marker).substringBefore("</script>")
}

private fun inlinedGraphReport(): WorkspaceReport {
    val source = inlinedSymbol("Consumer", SymbolDetailKind.CLASS)
    val target = inlinedSymbol("Contract", SymbolDetailKind.INTERFACE)
    val relationship = inlinedCall(source, target)
    return inlinedWorkspaceReport(source, target, relationship)
}

private fun inlinedCall(
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
                line = 4,
                context = "target.run()",
                evidence = ReferenceEvidence.DIRECT,
            ),
        evidence = ReferenceEvidence.DIRECT,
    )

private fun inlinedWorkspaceReport(
    source: WorkspaceSymbol,
    target: WorkspaceSymbol,
    relationship: WorkspaceRelationship,
): WorkspaceReport =
    WorkspaceReport(
        name = "inlined-graph",
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
                inlinedSourceFile(source, "class Consumer\n"),
                inlinedSourceFile(target, "interface Contract\n"),
            ),
    )

private fun inlinedSourceFile(
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

private fun inlinedSymbol(
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
