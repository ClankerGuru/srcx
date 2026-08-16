package zone.clanker.gradle.docx.site

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import zone.clanker.gradle.docx.crossProjectCycleWorkspaceFixture
import zone.clanker.gradle.docx.crossProjectWorkspaceFixture
import zone.clanker.gradle.docx.docxTestPlan
import zone.clanker.gradle.docx.workspaceFixture
import zone.clanker.report.model.AtlasEdge
import zone.clanker.report.model.AtlasFrame
import zone.clanker.report.model.AtlasLens
import zone.clanker.report.model.AtlasScopeKind
import zone.clanker.report.model.ReferenceKind
import zone.clanker.report.model.ReferenceSnapshot
import zone.clanker.report.model.RelationshipEvidence
import zone.clanker.report.model.RelationshipKind
import zone.clanker.report.model.RelationshipSnapshot

class WorkspaceAtlasOverviewProjectionTest :
    BehaviorSpec({
        given("a workspace with a resolved project relationship and an import") {
            val snapshot = crossProjectWorkspaceFixture()

            `when`("the initial bounded Atlas overview is projected") {
                val projection = DocxSiteProjection.apply(snapshot, docxTestPlan())
                val atlas = WorkspaceAtlasOverviewProjection.apply(snapshot, projection)

                then("it is a truthful workspace file frame and imports are not drawn") {
                    atlas.scope.kind shouldBe AtlasScopeKind.WORKSPACE
                    atlas.scope.workspaceId shouldBe snapshot.workspace.id
                    atlas.lens shouldBe AtlasLens.FILES
                    atlas.nodes.map { it.id } shouldContainExactly listOf("file:a", "file:b")
                    atlas.edges.map(AtlasEdge::relationshipIds) shouldContainExactly listOf(listOf("relationship:a"))
                    atlas.totalRelationshipRecordCount shouldBe 1
                    atlas.shownRelationshipRecordCount shouldBe 1
                    atlas.nodes.size shouldBe 2
                    (atlas.nodes.size <= AtlasFrame.MAX_VISIBLE_NODES) shouldBe true
                }
            }
        }

        given("a relationship cycle spanning project shards") {
            val snapshot = crossProjectCycleWorkspaceFixture()

            `when`("the workspace overview deduplicates shard closure") {
                val projection = DocxSiteProjection.apply(snapshot, docxTestPlan())
                val atlas = WorkspaceAtlasOverviewProjection.apply(snapshot, projection)

                then("every non-import record appears once with exact cycle marks") {
                    atlas.nodes.map { it.id } shouldContainExactly listOf("file:a", "file:b", "file:c")
                    atlas.edges.flatMap(AtlasEdge::relationshipIds).sorted() shouldContainExactly
                        listOf("relationship:a", "relationship:cycle-back", "relationship:cycle-out")
                    atlas.edges
                        .filter(AtlasEdge::isObservedCycleEdge)
                        .flatMap(AtlasEdge::relationshipIds)
                        .sorted() shouldContainExactly
                        listOf("relationship:cycle-back", "relationship:cycle-out")
                    atlas.totalRelationshipRecordCount shouldBe 3
                    atlas.shownRelationshipRecordCount shouldBe 3
                }
            }
        }

        given("a same-file relationship") {
            val fixture = workspaceFixture()
            val reference =
                ReferenceSnapshot(
                    id = "reference:self",
                    sourceFileId = "file:a",
                    sourceSymbolId = "symbol:a",
                    line = 1,
                    context = "Consumer()",
                    targetName = "Consumer",
                    targetQualifiedName = "fixture.Consumer",
                    kind = ReferenceKind.CALL,
                    evidence = RelationshipEvidence.DIRECT,
                )
            val relationship =
                RelationshipSnapshot(
                    id = "relationship:self",
                    referenceId = reference.id,
                    sourceSymbolId = "symbol:a",
                    targetSymbolId = "symbol:a",
                    kind = RelationshipKind.CALL,
                    resolutionEvidence = RelationshipEvidence.DIRECT,
                )
            val snapshot =
                fixture.copy(
                    references = (fixture.references + reference).sortedBy(ReferenceSnapshot::id),
                    relationships = (fixture.relationships + relationship).sortedBy(RelationshipSnapshot::id),
                )

            `when`("the overview aggregates shown records") {
                val projection = DocxSiteProjection.apply(snapshot, docxTestPlan())
                val atlas = WorkspaceAtlasOverviewProjection.apply(snapshot, projection)

                then("the self reference stays on its file instead of becoming a drawn route") {
                    atlas.edges.flatMap(AtlasEdge::relationshipIds) shouldContainExactly listOf("relationship:a")
                    atlas.nodes.single { it.id == "file:a" }.internalRecordCount shouldBe 1
                    atlas.totalRelationshipRecordCount shouldBe 2
                    atlas.shownRelationshipRecordCount shouldBe 2
                }
            }
        }
    })
