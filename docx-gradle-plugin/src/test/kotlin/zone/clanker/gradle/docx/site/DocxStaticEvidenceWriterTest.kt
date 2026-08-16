package zone.clanker.gradle.docx.site

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import zone.clanker.gradle.docx.crossProjectWorkspaceFixture
import zone.clanker.gradle.docx.docxTestPlan
import zone.clanker.gradle.docx.tempDirectory
import zone.clanker.gradle.docx.workspaceFixture
import zone.clanker.report.model.RelationshipKind
import zone.clanker.report.model.SourceRangeSnapshot
import zone.clanker.report.model.WorkspaceEvidenceJson
import zone.clanker.report.model.WorkspaceRelationshipOccurrenceSelector
import zone.clanker.report.model.workspaceDeclarationEvidenceRouteKey
import zone.clanker.report.model.workspaceRelationshipOccurrenceRouteKey
import zone.clanker.report.model.workspaceReverseUsageRouteKey
import java.nio.file.Files

class DocxStaticEvidenceWriterTest :
    BehaviorSpec({
        given("exact declaration and relationship evidence") {
            val fixture = workspaceFixture()
            val snapshot =
                fixture.copy(
                    symbols =
                        fixture.symbols +
                            fixture.symbols
                                .single { symbol -> symbol.id == "symbol:b" }
                                .copy(
                                    id = "symbol:b:member",
                                    name = "create",
                                    qualifiedName = "fixture.Target.create",
                                    ownerSymbolId = "symbol:b",
                                    signature = "fun create(): Target",
                                    declarationRange = SourceRangeSnapshot(0, 12),
                                ),
                    references =
                        fixture.references.map { reference ->
                            reference.copy(occurrenceRange = SourceRangeSnapshot(27, 33))
                        },
                )
            val site = DocxSiteProjection.apply(snapshot, docxTestPlan())
            val firstDirectory = tempDirectory("docx-evidence-first-").toPath()
            val secondDirectory = tempDirectory("docx-evidence-second-").toPath()

            `when`("the bounded static index is emitted twice") {
                val first = DocxStaticEvidenceWriter().write(firstDirectory, GENERATION_ID, site)
                val second = DocxStaticEvidenceWriter().write(secondDirectory, GENERATION_ID, site)
                val entries = readEntries(firstDirectory, first.file)

                then("every artifact address and byte payload is deterministic") {
                    first shouldBe second
                    Files.readString(firstDirectory.resolve(first.file)) shouldBe
                        Files.readString(secondDirectory.resolve(second.file))
                }

                then("declarations, reverse usages, and aggregate occurrences retain exact ranges") {
                    entries.map { entry -> entry.routeKey } shouldContain
                        workspaceDeclarationEvidenceRouteKey("symbol:b:member")
                    entries.map { entry -> entry.routeKey } shouldContain
                        workspaceReverseUsageRouteKey("symbol:b")
                    entries.map { entry -> entry.routeKey } shouldContain
                        workspaceRelationshipOccurrenceRouteKey(
                            WorkspaceRelationshipOccurrenceSelector(
                                RelationshipKind.CONSTRUCTOR,
                                "symbol:a",
                                "symbol:b",
                            ),
                        )
                    entries
                        .single { entry -> entry.declaration?.symbolId == "symbol:b:member" }
                        .declaration
                        ?.declaration
                        ?.location
                        ?.range shouldBe SourceRangeSnapshot(0, 12)
                    entries
                        .single { entry -> entry.declaration?.symbolId == "symbol:b:member" }
                        .declaration
                        ?.declaration
                        ?.ownerSymbolId shouldBe "symbol:b"
                    entries
                        .single { entry -> entry.reverseUsages != null }
                        .reverseUsages
                        ?.occurrences
                        ?.single()
                        ?.location
                        ?.range shouldBe SourceRangeSnapshot(27, 33)
                }
            }
        }

        given("an import whose source path ends at a file while its target reaches a symbol") {
            val site = DocxSiteProjection.apply(crossProjectWorkspaceFixture(), docxTestPlan())
            val directory = tempDirectory("docx-evidence-import-").toPath()

            then("the static selector set mirrors the hierarchy projector without a Cartesian fanout") {
                val catalog = DocxStaticEvidenceWriter().write(directory, GENERATION_ID, site)
                val entries = readEntries(directory, catalog.file)
                entries.map { entry -> entry.routeKey } shouldContain
                    workspaceRelationshipOccurrenceRouteKey(
                        WorkspaceRelationshipOccurrenceSelector(
                            RelationshipKind.IMPORT,
                            "file:a",
                            "symbol:c",
                        ),
                    )
                entries.count { entry -> entry.relationshipOccurrences != null } shouldBe 7
            }
        }
    })

private fun readEntries(
    directory: java.nio.file.Path,
    catalogFile: String,
) = WorkspaceEvidenceJson
    .decodeStaticCatalog(Files.readString(directory.resolve(catalogFile)))
    .partitions
    .flatMap { partitionReference ->
        WorkspaceEvidenceJson
            .decodeStaticPartition(Files.readString(directory.resolve(partitionReference.file)))
            .pages
    }.flatMap { pageReference ->
        WorkspaceEvidenceJson
            .decodeStaticPage(Files.readString(directory.resolve(pageReference.file)))
            .entries
    }

private const val GENERATION_ID: String = "generation-evidence"
