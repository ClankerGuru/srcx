package zone.clanker.gradle.docx.site

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import zone.clanker.gradle.docx.crossProjectCycleWorkspaceFixture
import zone.clanker.gradle.docx.docxTestPlan
import zone.clanker.gradle.docx.tempDirectory
import zone.clanker.gradle.docx.workspaceFixture
import zone.clanker.report.model.DeclarationSemantic
import zone.clanker.report.model.FindingSeverity
import zone.clanker.report.model.FindingSnapshot
import zone.clanker.report.model.SymbolKind
import zone.clanker.report.model.SymbolSnapshot
import zone.clanker.report.model.WorkspaceSearchBadgeKind
import zone.clanker.report.model.WorkspaceSearchJson
import zone.clanker.report.model.WorkspaceSearchKind
import zone.clanker.report.model.WorkspaceSearchShard
import java.nio.file.Files

class DocxStaticSearchWriterTest :
    BehaviorSpec({
        given("a projected site containing every declaration category and one finding") {
            val projection = searchableProjection()
            val firstDirectory = tempDirectory("docx-search-first-").toPath()
            val secondDirectory = tempDirectory("docx-search-second-").toPath()

            `when`("the prefix index is written twice for the same generation") {
                val first = DocxStaticSearchWriter().write(firstDirectory, GENERATION_ID, projection)
                val second = DocxStaticSearchWriter().write(secondDirectory, GENERATION_ID, projection)
                val catalog =
                    WorkspaceSearchJson.decodeCatalog(
                        Files.readString(firstDirectory.resolve(first.file)),
                    )
                val entries =
                    catalog.shards
                        .flatMap { reference ->
                            WorkspaceSearchJson
                                .decodeShard(Files.readString(firstDirectory.resolve(reference.file)))
                                .entries
                        }.distinctBy { entry -> entry.key }

                then("catalog and shard addresses are byte-deterministic") {
                    first shouldBe second
                    Files.readString(firstDirectory.resolve(first.file)) shouldBe
                        Files.readString(secondDirectory.resolve(second.file))
                    catalog.shards.all { reference ->
                        reference.entryCount <= WorkspaceSearchShard.MAX_ENTRIES
                    } shouldBe true
                }

                then("structural, declaration, finding, and extension entries are searchable without source bodies") {
                    entries.map { entry -> entry.kind }.toSet() shouldContainAll
                        setOf(
                            WorkspaceSearchKind.BUILD,
                            WorkspaceSearchKind.PROJECT,
                            WorkspaceSearchKind.SOURCE_SET,
                            WorkspaceSearchKind.PACKAGE,
                            WorkspaceSearchKind.FILE,
                            WorkspaceSearchKind.CLASS,
                            WorkspaceSearchKind.INTERFACE,
                            WorkspaceSearchKind.OBJECT,
                            WorkspaceSearchKind.ENUM,
                            WorkspaceSearchKind.FUNCTION,
                            WorkspaceSearchKind.PROPERTY,
                            WorkspaceSearchKind.FINDING,
                        )
                    entries.single { entry -> entry.id == "file:a" }.target.extension shouldBe "kt"
                    entries.single { entry -> entry.id == "file:a" }.badges.map { badge -> badge.kind } shouldContainAll
                        setOf(WorkspaceSearchBadgeKind.RELATIONSHIPS, WorkspaceSearchBadgeKind.PROBLEMS)
                    catalog.shards
                        .map { reference -> Files.readString(firstDirectory.resolve(reference.file)) }
                        .none { encoded -> "class Consumer(val target: Target)" in encoded } shouldBe true
                }
            }
        }

        given("a captured cross-project relationship cycle") {
            val projection = DocxSiteProjection.apply(crossProjectCycleWorkspaceFixture(), docxTestPlan())

            then("the static search projection includes the cycle once") {
                DocxStaticSearchProjection
                    .apply(projection)
                    .entries
                    .count { entry -> entry.kind == WorkspaceSearchKind.CYCLE } shouldBe 1
            }
        }
    })

private fun searchableProjection(): ProjectedDocxSite {
    val snapshot = workspaceFixture()
    val fileId = snapshot.files.first().id
    val extraSymbols =
        listOf(
            symbol("symbol:c", fileId, "Port", SymbolKind.INTERFACE, DeclarationSemantic.INTERFACE),
            symbol("symbol:d", fileId, "Registry", SymbolKind.OBJECT, DeclarationSemantic.SINGLETON_OBJECT),
            symbol("symbol:e", fileId, "Mode", SymbolKind.ENUM, DeclarationSemantic.ENUM),
            symbol("symbol:f", fileId, "render", SymbolKind.FUNCTION, DeclarationSemantic.OTHER),
            symbol("symbol:g", fileId, "state", SymbolKind.PROPERTY, DeclarationSemantic.OTHER),
        )
    val projected =
        DocxSiteProjection.apply(
            snapshot.copy(symbols = (snapshot.symbols + extraSymbols).sortedBy(SymbolSnapshot::id)),
            docxTestPlan(),
        )
    val graph = projected.projectGraphs.single()
    val finding =
        FindingSnapshot(
            id = "finding:search",
            severity = FindingSeverity.WARNING,
            message = "Consumer depends on a concrete target",
            suggestion = "Depend on an interface",
            fileId = "file:a",
            filePath = "src/main/kotlin/Consumer.kt",
            line = 1,
            symbolIds = listOf("symbol:a"),
        )
    return projected.copy(projectGraphs = listOf(graph.copy(findings = listOf(finding))))
}

private fun symbol(
    id: String,
    fileId: String,
    name: String,
    kind: SymbolKind,
    semantic: DeclarationSemantic,
): SymbolSnapshot =
    SymbolSnapshot(
        id = id,
        fileId = fileId,
        name = name,
        qualifiedName = "fixture.$name",
        packageName = "fixture",
        kind = kind,
        declarationSemantic = semantic,
        declarationLine = 1,
    )

private const val GENERATION_ID: String = "generation-search"
