package zone.clanker.report.model

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class WorkspaceServiceTransportTest :
    BehaviorSpec({
        given("a public workspace mount with an index status") {
            val mount =
                WorkspaceMount(
                    workspaceId = "sample",
                    viewerPath = "/w/sample/",
                    availability = WorkspaceAvailability.AVAILABLE,
                    generationId = "generation-a",
                    workspaceName = "sample-workspace",
                    siteState = WorkspaceSiteState.CURRENT,
                    message = "Current",
                    observedAtEpochMilliseconds = 42,
                    index = WorkspaceIndexStatus.Current("generation-a", 41),
                )

            `when`("the service transport crosses the JSON boundary") {
                val decoded = WorkspaceServiceJson.decodeMount(WorkspaceServiceJson.encodeMount(mount))

                then("the typed status is preserved without a host filesystem path") {
                    decoded shouldBe mount
                    WorkspaceServiceJson.encodeMount(mount).contains("siteDirectory") shouldBe false
                }
            }
        }

        given("a discoverable endpoint") {
            val endpoint = WorkspaceServiceEndpoint(baseUrl = "http://127.0.0.1:1234/", token = "secret")

            `when`("it is persisted and restored") {
                then("ephemeral port discovery remains typed") {
                    WorkspaceServiceJson.decodeEndpoint(WorkspaceServiceJson.encodeEndpoint(endpoint)) shouldBe endpoint
                }
            }
        }

        given("bounded index query results") {
            val symbols =
                WorkspaceSymbolSearchResponse(
                    results =
                        listOf(
                            WorkspaceSymbolHit(
                                symbolId = "symbol:sample",
                                scope =
                                    WorkspaceSymbolScope(
                                        buildId = "build:sample",
                                        projectId = "project:sample",
                                        sourceSetId = "source-set:sample",
                                        sourceSetName = "main",
                                        fileId = "file:sample",
                                        filePath = "src/main/kotlin/Sample.kt",
                                    ),
                                declaration =
                                    WorkspaceSymbolDeclaration(
                                        name = "Sample",
                                        qualifiedName = "example.Sample",
                                        packageName = "example",
                                        kind = SymbolKind.CLASS,
                                        declarationLine = 1,
                                    ),
                            ),
                        ),
                )
            val relationships =
                WorkspaceRelationshipSummaryResponse(
                    listOf(WorkspaceRelationshipCount(RelationshipKind.CALL, 2, 1, 1, 0)),
                )
            val findings =
                WorkspaceFindingSummaryResponse(
                    listOf(WorkspaceFindingCount(FindingSeverity.WARNING, 1, 1, 1)),
                )

            then("their typed JSON contracts round-trip independently from the registry protocol") {
                WorkspaceQueryJson.decodeSymbolSearch(WorkspaceQueryJson.encodeSymbolSearch(symbols)) shouldBe symbols
                WorkspaceQueryJson.decodeRelationshipSummary(
                    WorkspaceQueryJson.encodeRelationshipSummary(relationships),
                ) shouldBe relationships
                WorkspaceQueryJson.decodeFindingSummary(
                    WorkspaceQueryJson.encodeFindingSummary(findings),
                ) shouldBe findings
            }
        }
    })
