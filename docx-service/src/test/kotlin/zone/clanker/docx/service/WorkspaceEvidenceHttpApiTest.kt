package zone.clanker.docx.service

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import zone.clanker.docx.service.client.ServiceConnection
import zone.clanker.docx.service.client.WorkspaceServiceClient
import zone.clanker.docx.service.index.ServiceIndexFixture
import zone.clanker.report.model.RelationshipKind
import zone.clanker.report.model.WorkspaceDeclarationEvidenceRequest
import zone.clanker.report.model.WorkspaceEvidenceJson
import zone.clanker.report.model.WorkspaceEvidenceTarget
import zone.clanker.report.model.WorkspaceIndexStatus
import zone.clanker.report.model.WorkspaceRelationshipOccurrenceRequest
import zone.clanker.report.model.WorkspaceRelationshipOccurrenceSelector
import zone.clanker.report.model.WorkspaceReverseUsageRequest
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class WorkspaceEvidenceHttpApiTest :
    BehaviorSpec({
        given("a current SQLite-backed workspace") {
            then("all evidence routes preserve generation pinning and bounded exact records") {
                val root = temporaryDirectory("docx-evidence-http-")
                val site = root.resolve("site")
                writeSite(site, ServiceIndexFixture.GENERATION_ID, "Evidence HTTP fixture")
                ServiceIndexFixture.writeGeneration(site)
                val server = DocxWorkspaceServer.start(serviceConfig(root))
                try {
                    val connection = ServiceConnection(URI(server.endpoint.baseUrl), server.endpoint.token)
                    val client = WorkspaceServiceClient(connection)
                    client.register(LIVE_WORKSPACE_ID, site)
                    awaitEvidenceIndex(client)
                    val target = WorkspaceEvidenceTarget("source-workspace", ServiceIndexFixture.GENERATION_ID)

                    val declaration =
                        client
                            .declarationEvidence(
                                LIVE_WORKSPACE_ID,
                                WorkspaceDeclarationEvidenceRequest(target, ServiceIndexFixture.START_SYMBOL_ID),
                            ).declaration
                    requireNotNull(declaration).ownerSymbolId shouldBe ServiceIndexFixture.SYMBOL_ID
                    declaration.location.range?.startOffset shouldBe 16

                    val usages =
                        client.reverseUsages(
                            LIVE_WORKSPACE_ID,
                            WorkspaceReverseUsageRequest(target, ServiceIndexFixture.SYMBOL_ID, limit = 1),
                        )
                    usages.totalCount shouldBe 1
                    usages.occurrences.single().context shouldBe "start() = Service()"

                    val occurrences =
                        client.relationshipOccurrences(
                            LIVE_WORKSPACE_ID,
                            WorkspaceRelationshipOccurrenceRequest(
                                target,
                                WorkspaceRelationshipOccurrenceSelector(
                                    RelationshipKind.CALL,
                                    ServiceIndexFixture.START_SYMBOL_ID,
                                    ServiceIndexFixture.SYMBOL_ID,
                                ),
                            ),
                        )
                    occurrences.totalCount shouldBe 1

                    mountedDeclaration(connection, target).let { mounted ->
                        mounted.statusCode() shouldBe 200
                        WorkspaceEvidenceJson.decodeDeclarationPage(mounted.body()).declaration shouldBe declaration
                    }

                    unauthenticatedDeclaration(connection, target).statusCode() shouldBe 401
                } finally {
                    server.close()
                }
            }
        }
    })

private fun serviceConfig(root: java.nio.file.Path): DocxServiceConfig =
    DocxServiceConfig(
        port = 0,
        registryFile = root.resolve("registry.json"),
        endpointFile = root.resolve("endpoint.json"),
        indexFile = root.resolve("index.sqlite"),
        token = "evidence-secret",
        limits = DocxServiceLimits(pollIntervalMilliseconds = 10),
    )

private fun awaitEvidenceIndex(client: WorkspaceServiceClient) {
    val deadline = System.nanoTime() + EVIDENCE_INDEX_TIMEOUT_NANOS
    while (System.nanoTime() < deadline) {
        val index =
            client
                .list()
                .workspaces
                .single { workspace -> workspace.workspaceId == LIVE_WORKSPACE_ID }
                .index
        if (index is WorkspaceIndexStatus.Current) return
        Thread.sleep(EVIDENCE_POLL_MILLIS)
    }
    error("Workspace evidence index did not become current")
}

private fun unauthenticatedDeclaration(
    connection: ServiceConnection,
    target: WorkspaceEvidenceTarget,
): HttpResponse<String> =
    HttpClient.newHttpClient().send(
        HttpRequest
            .newBuilder(connection.baseUrl.resolve("api/v1/workspaces/live/evidence/declaration"))
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    WorkspaceEvidenceJson.encodeDeclarationRequest(
                        WorkspaceDeclarationEvidenceRequest(target, ServiceIndexFixture.SYMBOL_ID),
                    ),
                ),
            ).build(),
        HttpResponse.BodyHandlers.ofString(),
    )

private fun mountedDeclaration(
    connection: ServiceConnection,
    target: WorkspaceEvidenceTarget,
): HttpResponse<String> =
    HttpClient.newHttpClient().send(
        HttpRequest
            .newBuilder(connection.baseUrl.resolve("w/live/api/evidence/declaration"))
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    WorkspaceEvidenceJson.encodeDeclarationRequest(
                        WorkspaceDeclarationEvidenceRequest(target, ServiceIndexFixture.START_SYMBOL_ID),
                    ),
                ),
            ).build(),
        HttpResponse.BodyHandlers.ofString(),
    )

private const val LIVE_WORKSPACE_ID: String = "live"
private const val EVIDENCE_POLL_MILLIS: Long = 20
private const val EVIDENCE_INDEX_TIMEOUT_NANOS: Long = 5_000_000_000
