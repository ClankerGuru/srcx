package zone.clanker.docx.index

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import zone.clanker.report.model.FindingSeverity
import zone.clanker.report.model.RelationshipKind
import zone.clanker.report.model.SymbolKind

class WorkspaceReportIndexTest :
    BehaviorSpec({
        given("a generated two-project DOCX site") {
            val site = WorkspaceIndexFixture.createSite()
            val database = WorkspaceIndexFixture.createDatabase()
            WorkspaceIndexFixture.writeGeneration(site, generationId = "generation-one")

            then("it indexes once and exposes bounded typed queries over the active generation") {
                WorkspaceReportIndex.open(database).use { index ->
                    index.activeGeneration(WorkspaceIndexFixture.INDEX_WORKSPACE_ID) shouldBe null
                    val first = index.indexSite(WorkspaceIndexFixture.INDEX_WORKSPACE_ID, site)
                    first.alreadyCurrent shouldBe false
                    first.generation shouldBe
                        IndexedGeneration(
                            workspaceId = WorkspaceIndexFixture.INDEX_WORKSPACE_ID,
                            sourceWorkspaceId = WorkspaceIndexFixture.SOURCE_WORKSPACE_ID,
                            workspaceName = "Index fixture",
                            generationId = "generation-one",
                            scopes = IndexedScopeCounts(builds = 1, projects = 2, sourceSets = 2),
                            records = IndexedRecordCounts(files = 2, symbols = 3, relationships = 2, findings = 2),
                        )
                    index.indexSite(WorkspaceIndexFixture.INDEX_WORKSPACE_ID, site) shouldBe
                        first.copy(alreadyCurrent = true)

                    index
                        .searchSymbols(
                            workspaceId = WorkspaceIndexFixture.INDEX_WORKSPACE_ID,
                            query = "AlphaServ",
                        ).map(SymbolSearchResult::symbolId)
                        .shouldContainExactly(listOf(WorkspaceIndexFixture.ALPHA_CLASS_ID))
                    index
                        .searchSymbols(
                            workspaceId = WorkspaceIndexFixture.INDEX_WORKSPACE_ID,
                            query = "",
                            scope = IndexScope(projectId = WorkspaceIndexFixture.BETA_PROJECT_ID),
                            kinds = setOf(SymbolKind.INTERFACE),
                            limit = 1,
                        ).map(SymbolSearchResult::name)
                        .shouldContainExactly(listOf("BetaPort"))
                    index.searchSymbols(WorkspaceIndexFixture.INDEX_WORKSPACE_ID, "---") shouldHaveSize 0

                    index.relationshipSummary(
                        workspaceId = WorkspaceIndexFixture.INDEX_WORKSPACE_ID,
                        scope = IndexScope(sourceSetIds = setOf(WorkspaceIndexFixture.ALPHA_SOURCE_SET_ID)),
                        kinds = setOf(RelationshipKind.CALL),
                    ) shouldContainExactly
                        listOf(
                            RelationshipSummary(
                                kind = RelationshipKind.CALL,
                                recordCount = 2,
                                sourceSymbolCount = 1,
                                targetSymbolCount = 1,
                                heuristicCount = 0,
                            ),
                        )
                    index.findingSummary(WorkspaceIndexFixture.INDEX_WORKSPACE_ID) shouldContainExactly
                        listOf(
                            FindingSummary(FindingSeverity.INFO, 1, 1, 1),
                            FindingSummary(FindingSeverity.WARNING, 1, 1, 1),
                        )
                    index.findingSummary(
                        workspaceId = WorkspaceIndexFixture.INDEX_WORKSPACE_ID,
                        scope = IndexScope(buildId = WorkspaceIndexFixture.BUILD_ID),
                        severities = setOf(FindingSeverity.WARNING),
                    ) shouldContainExactly listOf(FindingSummary(FindingSeverity.WARNING, 1, 1, 1))
                    shouldThrow<IllegalArgumentException> {
                        index.searchSymbols(
                            workspaceId = WorkspaceIndexFixture.INDEX_WORKSPACE_ID,
                            query = "Alpha",
                            limit = WorkspaceReportIndex.MAX_SEARCH_LIMIT + 1,
                        )
                    }
                }
            }
        }

        given("an indexed workspace lifecycle") {
            val site = WorkspaceIndexFixture.createSite()
            val database = WorkspaceIndexFixture.createDatabase()
            WorkspaceIndexFixture.writeGeneration(site, generationId = "generation-lifecycle")

            then("remove is idempotent and close rejects new work") {
                val index = WorkspaceReportIndex.open(database)
                index.indexSite(WorkspaceIndexFixture.INDEX_WORKSPACE_ID, site)
                index.remove(WorkspaceIndexFixture.INDEX_WORKSPACE_ID) shouldBe true
                index.remove(WorkspaceIndexFixture.INDEX_WORKSPACE_ID) shouldBe false
                index.activeGeneration(WorkspaceIndexFixture.INDEX_WORKSPACE_ID) shouldBe null
                index.close()
                shouldThrow<IllegalStateException> {
                    index.activeGeneration(WorkspaceIndexFixture.INDEX_WORKSPACE_ID)
                }
            }
        }
    })
