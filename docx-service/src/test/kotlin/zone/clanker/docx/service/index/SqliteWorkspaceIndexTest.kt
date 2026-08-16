package zone.clanker.docx.service.index

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import zone.clanker.docx.service.workspace.WorkspaceGeneration
import zone.clanker.report.model.FindingSeverity
import zone.clanker.report.model.RelationshipKind
import zone.clanker.report.model.SymbolKind
import zone.clanker.report.model.WorkspaceIndexStatus

class SqliteWorkspaceIndexTest :
    BehaviorSpec({
        given("a generated site and the service SQLite adapter") {
            val site = ServiceIndexFixture.createSite()
            ServiceIndexFixture.writeGeneration(site)
            val index = SqliteWorkspaceIndex.open(ServiceIndexFixture.createDatabase()) { INDEXED_AT }

            then("one immutable generation supports bounded typed service queries") {
                index.index(
                    WorkspaceGeneration(
                        workspaceId = ServiceIndexFixture.WORKSPACE_ID,
                        siteDirectory = site,
                        generationId = ServiceIndexFixture.GENERATION_ID,
                    ),
                ) shouldBe WorkspaceIndexStatus.Current(ServiceIndexFixture.GENERATION_ID, INDEXED_AT)

                val symbols =
                    index.searchSymbols(
                        SymbolQuery(
                            workspaceId = ServiceIndexFixture.WORKSPACE_ID,
                            query = "Serv",
                            scope = WorkspaceQueryScope(projectId = ServiceIndexFixture.PROJECT_ID),
                            kinds = setOf(SymbolKind.CLASS),
                            limit = 10,
                        ),
                    )
                symbols.results.map { it.symbolId } shouldContainExactly listOf(ServiceIndexFixture.SYMBOL_ID)
                symbols
                    .results
                    .single()
                    .scope
                    .buildId shouldBe ServiceIndexFixture.BUILD_ID
                symbols
                    .results
                    .single()
                    .declaration
                    .name shouldBe "Service"

                index
                    .relationshipSummary(
                        RelationshipQuery(
                            workspaceId = ServiceIndexFixture.WORKSPACE_ID,
                            scope = WorkspaceQueryScope(sourceSetIds = setOf(ServiceIndexFixture.SOURCE_SET_ID)),
                            kinds = setOf(RelationshipKind.CALL),
                        ),
                    ).results
                    .map { it.recordCount } shouldContainExactly listOf(1)
                index
                    .findingSummary(
                        FindingQuery(
                            workspaceId = ServiceIndexFixture.WORKSPACE_ID,
                            scope = WorkspaceQueryScope(buildId = ServiceIndexFixture.BUILD_ID),
                            severities = setOf(FindingSeverity.WARNING),
                        ),
                    ).results
                    .map { it.findingCount } shouldContainExactly listOf(1)

                index.remove(ServiceIndexFixture.WORKSPACE_ID)
                index.close()
                shouldThrow<IllegalStateException> {
                    index.searchSymbols(
                        SymbolQuery(
                            workspaceId = ServiceIndexFixture.WORKSPACE_ID,
                            query = "",
                            scope = WorkspaceQueryScope(),
                            kinds = emptySet(),
                            limit = 1,
                        ),
                    )
                }
            }
        }
    })

private const val INDEXED_AT: Long = 123
