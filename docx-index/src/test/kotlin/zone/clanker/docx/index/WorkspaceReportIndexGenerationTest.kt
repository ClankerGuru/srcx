package zone.clanker.docx.index

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.sql.DriverManager
import java.sql.SQLException

class WorkspaceReportIndexGenerationTest :
    BehaviorSpec({
        given("an active generation and a replacement that fails inside a shard transaction") {
            val site = WorkspaceIndexFixture.createSite()
            val database = WorkspaceIndexFixture.createDatabase()
            WorkspaceIndexFixture.writeGeneration(site, generationId = "generation-one")

            then("the prior generation stays active and queryable until a complete replacement commits") {
                WorkspaceReportIndex.open(database).use { index ->
                    index.indexSite(WorkspaceIndexFixture.INDEX_WORKSPACE_ID, site)
                    WorkspaceIndexFixture.writeGeneration(
                        root = site,
                        generationId = "generation-two",
                        alphaClassName = "NextAlphaService",
                        duplicateBetaSymbolId = true,
                    )

                    shouldThrow<SQLException> {
                        index.indexSite(WorkspaceIndexFixture.INDEX_WORKSPACE_ID, site)
                    }
                    index.activeGeneration(WorkspaceIndexFixture.INDEX_WORKSPACE_ID)?.generationId shouldBe
                        "generation-one"
                    index
                        .searchSymbols(
                            WorkspaceIndexFixture.INDEX_WORKSPACE_ID,
                            "AlphaService",
                        ).map(SymbolSearchResult::name)
                        .shouldContainExactly(listOf("AlphaService"))

                    WorkspaceIndexFixture.writeGeneration(
                        root = site,
                        generationId = "generation-two",
                        alphaClassName = "NextAlphaService",
                    )
                    index.indexSite(WorkspaceIndexFixture.INDEX_WORKSPACE_ID, site).generation.generationId shouldBe
                        "generation-two"
                    index.searchSymbols(
                        WorkspaceIndexFixture.INDEX_WORKSPACE_ID,
                        "AlphaService",
                    ) shouldBe emptyList()
                    index
                        .searchSymbols(
                            WorkspaceIndexFixture.INDEX_WORKSPACE_ID,
                            "NextAlpha",
                        ).map(SymbolSearchResult::name)
                        .shouldContainExactly(listOf("NextAlphaService"))

                    WorkspaceIndexFixture.writeGeneration(
                        root = site,
                        generationId = "generation-three",
                        alphaClassName = "FinalAlphaService",
                    )
                    index.indexSite(WorkspaceIndexFixture.INDEX_WORKSPACE_ID, site)
                }

                DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
                    connection
                        .prepareStatement(
                            "SELECT generation_id FROM workspace_generations ORDER BY generation_id",
                        ).use { statement ->
                            statement
                                .executeQuery()
                                .use { result ->
                                    result.next() shouldBe true
                                    result.getString(1) shouldBe "generation-three"
                                    result.next() shouldBe true
                                    result.getString(1) shouldBe "generation-two"
                                    result.next() shouldBe false
                                }
                        }
                }
            }
        }

        given("an active generation and a replacement with a concurrently decoded identity failure") {
            val site = WorkspaceIndexFixture.createSite()
            val database = WorkspaceIndexFixture.createDatabase()
            WorkspaceIndexFixture.writeGeneration(site, generationId = "generation-one")

            then("the incomplete replacement never becomes active") {
                WorkspaceReportIndex.open(database, IndexImportOptions(parallelism = 2)).use { index ->
                    index.indexSite(WorkspaceIndexFixture.INDEX_WORKSPACE_ID, site)
                    WorkspaceIndexFixture.writeGeneration(
                        root = site,
                        generationId = "generation-two",
                        alphaClassName = "NextAlphaService",
                    )
                    val alphaShard = site.resolve("data/shards/alpha.json")
                    val betaShard = site.resolve("data/shards/beta.json")
                    Files.writeString(betaShard, Files.readString(alphaShard))

                    shouldThrow<IllegalArgumentException> {
                        index.indexSite(WorkspaceIndexFixture.INDEX_WORKSPACE_ID, site)
                    }
                    index.activeGeneration(WorkspaceIndexFixture.INDEX_WORKSPACE_ID)?.generationId shouldBe
                        "generation-one"
                    index
                        .searchSymbols(
                            WorkspaceIndexFixture.INDEX_WORKSPACE_ID,
                            "AlphaService",
                        ).map(SymbolSearchResult::name)
                        .shouldContainExactly(listOf("AlphaService"))
                }
            }
        }
    })
