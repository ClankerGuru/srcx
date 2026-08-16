package zone.clanker.docx.service

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import zone.clanker.docx.service.client.ServiceConnection
import zone.clanker.docx.service.client.WorkspaceServiceClient
import zone.clanker.docx.service.index.ServiceIndexFixture
import zone.clanker.report.model.WorkspaceGraphFacet
import zone.clanker.report.model.WorkspaceGraphJson
import zone.clanker.report.model.WorkspaceGraphRequest
import zone.clanker.report.model.WorkspaceIndexStatus
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class WorkspaceGraphHttpApiTest :
    BehaviorSpec({
        given("a current SQLite-backed workspace") {
            then("the authenticated graph route returns only one bounded generation-pinned slice") {
                val root = temporaryDirectory("docx-graph-http-")
                val site = root.resolve("site")
                writeSite(site, ServiceIndexFixture.GENERATION_ID, "Graph HTTP fixture")
                ServiceIndexFixture.writeGeneration(site)
                val config =
                    DocxServiceConfig(
                        port = 0,
                        registryFile = root.resolve("registry.json"),
                        endpointFile = root.resolve("endpoint.json"),
                        indexFile = root.resolve("index.sqlite"),
                        token = "graph-secret",
                        limits = DocxServiceLimits(pollIntervalMilliseconds = 10),
                    )
                val server = DocxWorkspaceServer.start(config)
                try {
                    val connection = ServiceConnection(URI(server.endpoint.baseUrl), server.endpoint.token)
                    val client = WorkspaceServiceClient(connection)
                    client.register("live", site)
                    awaitCurrentIndex(client, "live")

                    val request =
                        WorkspaceGraphRequest(
                            workspaceId = "source-workspace",
                            generationId = ServiceIndexFixture.GENERATION_ID,
                            facet = WorkspaceGraphFacet.SOURCE,
                        )
                    val slice = client.graphSlice("live", request)
                    slice.target.workspaceId shouldBe "source-workspace"
                    slice.target.generationId shouldBe ServiceIndexFixture.GENERATION_ID
                    slice.content.nodes.size shouldBe 2
                    slice.content.relations.size shouldBe 0

                    val mountedViewerResponse =
                        HttpClient.newHttpClient().send(
                            HttpRequest
                                .newBuilder(connection.baseUrl.resolve("w/live/api/graph"))
                                .header("Content-Type", "application/json")
                                .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                        WorkspaceGraphJson.encodeRequest(request),
                                    ),
                                ).build(),
                            HttpResponse.BodyHandlers.ofString(),
                        )
                    mountedViewerResponse.statusCode() shouldBe 200
                    WorkspaceGraphJson.decodeSlice(mountedViewerResponse.body()) shouldBe slice

                    val unauthenticated =
                        HttpClient.newHttpClient().send(
                            HttpRequest
                                .newBuilder(connection.baseUrl.resolve("api/v1/workspaces/live/graph"))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                                .build(),
                            HttpResponse.BodyHandlers.ofString(),
                        )
                    unauthenticated.statusCode() shouldBe 401

                    val mountedGet =
                        HttpClient.newHttpClient().send(
                            HttpRequest
                                .newBuilder(connection.baseUrl.resolve("w/live/api/graph"))
                                .GET()
                                .build(),
                            HttpResponse.BodyHandlers.ofString(),
                        )
                    mountedGet.statusCode() shouldBe 405
                } finally {
                    server.close()
                }
            }
        }
    })

private fun awaitCurrentIndex(
    client: WorkspaceServiceClient,
    workspaceId: String,
) {
    val deadline = System.nanoTime() + INDEX_TIMEOUT_NANOS
    while (System.nanoTime() < deadline) {
        val index =
            client
                .list()
                .workspaces
                .single { workspace -> workspace.workspaceId == workspaceId }
                .index
        if (index is WorkspaceIndexStatus.Current) return
        Thread.sleep(POLL_MILLIS)
    }
    error("Workspace index did not become current")
}

private const val POLL_MILLIS: Long = 20
private const val INDEX_TIMEOUT_NANOS: Long = 5_000_000_000
