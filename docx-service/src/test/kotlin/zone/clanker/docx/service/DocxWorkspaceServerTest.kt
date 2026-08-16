package zone.clanker.docx.service

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import zone.clanker.docx.service.client.EndpointDiscovery
import zone.clanker.docx.service.client.ServiceCommand
import zone.clanker.docx.service.client.ServiceConnection
import zone.clanker.docx.service.client.WorkspaceServiceClient
import zone.clanker.docx.service.index.FindingQuery
import zone.clanker.docx.service.index.RelationshipQuery
import zone.clanker.docx.service.index.SymbolQuery
import zone.clanker.docx.service.index.WorkspaceQueryIndex
import zone.clanker.docx.service.workspace.DisabledWorkspaceGenerationIndexer
import zone.clanker.docx.service.workspace.WorkspaceGeneration
import zone.clanker.docx.service.workspace.WorkspaceGenerationIndexer
import zone.clanker.report.model.FindingSeverity
import zone.clanker.report.model.RelationshipKind
import zone.clanker.report.model.SymbolKind
import zone.clanker.report.model.WorkspaceFindingCount
import zone.clanker.report.model.WorkspaceFindingSummaryResponse
import zone.clanker.report.model.WorkspaceIndexStatus
import zone.clanker.report.model.WorkspaceQueryJson
import zone.clanker.report.model.WorkspaceRegistrationRequest
import zone.clanker.report.model.WorkspaceRelationshipCount
import zone.clanker.report.model.WorkspaceRelationshipSummaryResponse
import zone.clanker.report.model.WorkspaceServiceJson
import zone.clanker.report.model.WorkspaceSymbolDeclaration
import zone.clanker.report.model.WorkspaceSymbolHit
import zone.clanker.report.model.WorkspaceSymbolScope
import zone.clanker.report.model.WorkspaceSymbolSearchResponse
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

class DocxWorkspaceServerTest :
    BehaviorSpec({
        given("one headless service hosting multiple generated workspaces") {
            `when`("its authenticated API, static mounts, and watcher are exercised") {
                then("the complete lifecycle remains isolated, reactive, and cleanly stoppable") {
                    verifyServerLifecycle()
                }
            }
        }

        given("the executable client boundary") {
            `when`("help is requested") {
                then("usage is written without starting a server") {
                    val outputBytes = ByteArrayOutputStream()
                    execute(ServiceCommand.Help, PrintStream(outputBytes))
                    outputBytes.toString() shouldContain "docx-service serve"
                }
            }
        }

        given("a static-only service with indexing disabled") {
            then("the viewer remains available and bounded queries report their unavailable capability") {
                verifyStaticOnlyService()
            }
        }
    })

private fun verifyStaticOnlyService() {
    val root = temporaryDirectory("docx-static-service")
    val site = root.resolve("site").also { writeSite(it, workspaceName = "Static") }
    val config =
        DocxServiceConfig(
            registryFile = root.resolve("registry.json"),
            endpointFile = root.resolve("endpoint.json"),
            indexFile = root.resolve("index.sqlite"),
            token = "static-secret",
        )
    val server = DocxWorkspaceServer.start(config, DisabledWorkspaceGenerationIndexer)
    val result =
        runCatching {
            val connection = ServiceConnection(URI(server.endpoint.baseUrl), server.endpoint.token)
            val client = WorkspaceServiceClient(connection)
            client.register("static", site).index shouldBe WorkspaceIndexStatus.Disabled
            val http = HttpClient.newHttpClient()
            get(http, connection.baseUrl.resolve("w/static/")).body() shouldContain "Static"
            get(http, connection.baseUrl.resolve("api/v1/workspaces/static/symbols")).statusCode() shouldBe
                SERVICE_UNAVAILABLE
        }
    server.close()
    result.getOrThrow()
}

private fun verifyServerLifecycle() {
    val root = temporaryDirectory("docx-service")
    val registry = root.resolve("registry.json")
    val endpoint = root.resolve("endpoint.json")
    val siteA = root.resolve("site-a").also { writeSite(it, workspaceName = "Alpha") }
    val siteB = root.resolve("site-b").also { writeSite(it, workspaceName = "Bravo") }
    val indexer = RecordingIndexer()
    val config =
        DocxServiceConfig(
            port = 0,
            registryFile = registry,
            endpointFile = endpoint,
            token = "service-secret",
            limits = DocxServiceLimits(pollIntervalMilliseconds = 10, maxEventSubscribers = 1),
        )
    val server = DocxWorkspaceServer.start(config, indexer)
    val fixture = ServerFixture(config, endpoint, siteA, siteB, indexer)
    val result = runCatching { verifyRunningServer(server, fixture) }
    server.close()
    server.close()
    indexer.closed shouldBe true
    Files.exists(endpoint) shouldBe false
    result.getOrThrow()
}

private fun verifyRunningServer(
    server: DocxWorkspaceServer,
    fixture: ServerFixture,
) {
    val connection = ServiceConnection(URI(server.endpoint.baseUrl), server.endpoint.token)
    val serviceClient = WorkspaceServiceClient(connection)
    val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
    val emptyOutput = ByteArrayOutputStream()
    execute(ServiceCommand.ListWorkspaces(connection), PrintStream(emptyOutput))
    emptyOutput.toString() shouldContain "no registered workspaces"
    val alpha = serviceClient.register("alpha", fixture.siteA)
    val bravo = serviceClient.register("bravo", fixture.siteB)
    alpha.viewerPath shouldBe "/w/alpha/"
    bravo.viewerPath shouldBe "/w/bravo/"
    serviceClient.list().workspaces.map { it.workspaceId } shouldContainExactly listOf("alpha", "bravo")
    verifyExecutableClients(connection, fixture.siteB)
    verifyStaticRoutes(http, connection)
    verifyAuthentication(http, connection, fixture.siteA)
    verifyEventStream(http, connection)
    verifyIndexRoutes(http, connection, serviceClient, fixture.indexer)
    verifyGenerationUpdate(serviceClient, fixture.siteA, fixture.indexer)
    serviceClient.unregister("bravo")
    serviceClient.list().workspaces.map { it.workspaceId } shouldContainExactly listOf("alpha")
    fixture.indexer.removed shouldContainExactly listOf("charlie", "bravo")
    shouldThrow<IllegalArgumentException> { serviceClient.unregister("bravo") }
    shouldThrow<IllegalStateException> { DocxWorkspaceServer.start(fixture.config, RecordingIndexer()) }
        .message shouldContain "Another DOCX service owns registry"
    Files.isRegularFile(fixture.endpoint) shouldBe true
    EndpointDiscovery.read(fixture.endpoint).baseUrl shouldBe connection.baseUrl
    System.getProperty("java.awt.headless") shouldBe "true"
}

private fun verifyExecutableClients(
    connection: ServiceConnection,
    site: java.nio.file.Path,
) {
    val outputBytes = ByteArrayOutputStream()
    val output = PrintStream(outputBytes)
    execute(ServiceCommand.ListWorkspaces(connection), output)
    execute(ServiceCommand.Register(connection, "charlie", site), output)
    execute(ServiceCommand.Unregister(connection, "charlie"), output)
    outputBytes.toString() shouldContain "alpha"
    outputBytes.toString() shouldContain "registered charlie"
    outputBytes.toString() shouldContain "unregistered charlie"
}

private data class ServerFixture(
    val config: DocxServiceConfig,
    val endpoint: java.nio.file.Path,
    val siteA: java.nio.file.Path,
    val siteB: java.nio.file.Path,
    val indexer: RecordingIndexer,
)

private fun verifyStaticRoutes(
    http: HttpClient,
    connection: ServiceConnection,
) {
    get(http, connection.baseUrl.resolve("healthz")).statusCode() shouldBe OK
    get(http, connection.baseUrl).statusCode() shouldBe TEMPORARY_REDIRECT
    get(http, connection.baseUrl.resolve("unknown")).statusCode() shouldBe NOT_FOUND
    get(http, connection.baseUrl.resolve("api/v1/workspaces/alpha")).statusCode() shouldBe OK
    get(http, connection.baseUrl.resolve("w/alpha")).statusCode() shouldBe TEMPORARY_REDIRECT
    get(http, connection.baseUrl.resolve("w/alpha/")).body() shouldContain "Alpha"
    get(http, connection.baseUrl.resolve("w/bravo/")).body() shouldContain "Bravo"
    val first = get(http, connection.baseUrl.resolve("w/alpha/"))
    first.headers().firstValue("Cache-Control").orElseThrow() shouldBe "no-cache"
    val conditional =
        request(http, connection.baseUrl.resolve("w/alpha/")) {
            header("If-None-Match", first.headers().firstValue("ETag").orElseThrow())
            GET()
        }
    conditional.statusCode() shouldBe NOT_MODIFIED
    head(http, connection.baseUrl.resolve("w/alpha/")) shouldBe OK
    get(http, connection.baseUrl.resolve("w/missing/")).statusCode() shouldBe NOT_FOUND
    get(http, URI("${connection.baseUrl}w/alpha/%252e%252e/secret")).statusCode() shouldBe BAD_REQUEST
}

private fun verifyAuthentication(
    http: HttpClient,
    connection: ServiceConnection,
    site: java.nio.file.Path,
) {
    val registration =
        WorkspaceServiceJson.encodeRegistration(
            WorkspaceRegistrationRequest("third", site.toString()),
        )
    val unauthorized =
        request(http, connection.baseUrl.resolve("api/v1/workspaces")) {
            header("Content-Type", "application/json")
            POST(HttpRequest.BodyPublishers.ofString(registration))
        }
    unauthorized.statusCode() shouldBe UNAUTHORIZED
    request(http, connection.baseUrl.resolve("api/v1/workspaces")) { PUT(emptyBody()) }
        .statusCode() shouldBe METHOD_NOT_ALLOWED
}

private fun verifyEventStream(
    http: HttpClient,
    connection: ServiceConnection,
) {
    val response =
        http.send(
            HttpRequest.newBuilder(connection.baseUrl.resolve("api/v1/events")).GET().build(),
            HttpResponse.BodyHandlers.ofInputStream(),
        )
    response.statusCode() shouldBe OK
    response.body().bufferedReader().use { reader ->
        reader.readLine() shouldContain "id:"
        reader.readLine() shouldBe "event: workspace"
        reader.readLine() shouldContain "\"kind\":\"SNAPSHOT\""
        val overflow =
            http.send(
                HttpRequest.newBuilder(connection.baseUrl.resolve("api/v1/events")).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream(),
            )
        overflow.statusCode() shouldBe SERVICE_UNAVAILABLE
        overflow.body().close()
    }
}

private fun verifyIndexRoutes(
    http: HttpClient,
    connection: ServiceConnection,
    serviceClient: WorkspaceServiceClient,
    indexer: RecordingIndexer,
) {
    awaitCondition {
        serviceClient
            .list()
            .workspaces
            .single { it.workspaceId == "alpha" }
            .index is WorkspaceIndexStatus.Current
    }
    val symbols =
        get(
            http,
            connection.baseUrl.resolve(
                "api/v1/workspaces/alpha/symbols?q=Alpha&projectId=project-alpha&kind=class&limit=5",
            ),
        )
    symbols.statusCode() shouldBe OK
    WorkspaceQueryJson
        .decodeSymbolSearch(symbols.body())
        .results
        .single()
        .declaration
        .name shouldBe "AlphaService"
    indexer.symbolQueries.single().limit shouldBe 5
    indexer.symbolQueries.single().kinds shouldBe setOf(SymbolKind.CLASS)

    val relationships =
        get(http, connection.baseUrl.resolve("api/v1/workspaces/alpha/relationships?kind=call"))
    WorkspaceQueryJson
        .decodeRelationshipSummary(relationships.body())
        .results
        .single()
        .recordCount shouldBe 2
    val findings =
        get(http, connection.baseUrl.resolve("api/v1/workspaces/alpha/findings?severity=warning"))
    WorkspaceQueryJson
        .decodeFindingSummary(findings.body())
        .results
        .single()
        .findingCount shouldBe 1

    get(http, connection.baseUrl.resolve("api/v1/workspaces/missing/symbols")).statusCode() shouldBe NOT_FOUND
    get(http, connection.baseUrl.resolve("api/v1/workspaces/alpha/symbols?limit=0")).statusCode() shouldBe BAD_REQUEST
    get(
        http,
        connection.baseUrl.resolve("api/v1/workspaces/alpha/symbols?limit=many"),
    ).statusCode() shouldBe BAD_REQUEST
    request(http, connection.baseUrl.resolve("api/v1/workspaces/alpha/findings")) { POST(emptyBody()) }
        .statusCode() shouldBe METHOD_NOT_ALLOWED
}

private fun verifyGenerationUpdate(
    serviceClient: WorkspaceServiceClient,
    site: java.nio.file.Path,
    indexer: RecordingIndexer,
) {
    awaitCondition { indexer.generations.contains("alpha:generation-a") }
    writeSite(site, generationId = "generation-b", workspaceName = "Alpha", message = "Updated")
    awaitCondition {
        serviceClient
            .list()
            .workspaces
            .single { it.workspaceId == "alpha" }
            .generationId == "generation-b"
    }
    awaitCondition { indexer.generations.contains("alpha:generation-b") }
    val indexed =
        serviceClient
            .list()
            .workspaces
            .single { it.workspaceId == "alpha" }
            .index
    indexed shouldBe WorkspaceIndexStatus.Current("generation-b", INDEXED_AT)
}

private class RecordingIndexer :
    WorkspaceGenerationIndexer,
    WorkspaceQueryIndex {
    override val enabled: Boolean = true
    val generations: MutableList<String> = CopyOnWriteArrayList()
    val removed: MutableList<String> = CopyOnWriteArrayList()
    val symbolQueries: MutableList<SymbolQuery> = CopyOnWriteArrayList()
    var closed: Boolean = false

    override fun index(request: WorkspaceGeneration): WorkspaceIndexStatus {
        generations += "${request.workspaceId}:${request.generationId}"
        return WorkspaceIndexStatus.Current(request.generationId, INDEXED_AT)
    }

    override fun remove(workspaceId: String) {
        removed += workspaceId
    }

    override fun searchSymbols(request: SymbolQuery): WorkspaceSymbolSearchResponse {
        symbolQueries += request
        return WorkspaceSymbolSearchResponse(
            listOf(
                WorkspaceSymbolHit(
                    symbolId = "symbol-alpha",
                    scope =
                        WorkspaceSymbolScope(
                            buildId = "build-alpha",
                            projectId = "project-alpha",
                            sourceSetId = "source-set-alpha",
                            sourceSetName = "main",
                            fileId = "file-alpha",
                            filePath = "src/main/kotlin/Alpha.kt",
                        ),
                    declaration =
                        WorkspaceSymbolDeclaration(
                            name = "AlphaService",
                            qualifiedName = "example.AlphaService",
                            packageName = "example",
                            kind = SymbolKind.CLASS,
                            declarationLine = 1,
                        ),
                ),
            ),
        )
    }

    override fun relationshipSummary(request: RelationshipQuery): WorkspaceRelationshipSummaryResponse =
        WorkspaceRelationshipSummaryResponse(
            listOf(WorkspaceRelationshipCount(RelationshipKind.CALL, 2, 1, 1, 0)),
        )

    override fun findingSummary(request: FindingQuery): WorkspaceFindingSummaryResponse =
        WorkspaceFindingSummaryResponse(
            listOf(WorkspaceFindingCount(FindingSeverity.WARNING, 1, 1, 1)),
        )

    override fun close() {
        closed = true
    }
}

private fun get(
    client: HttpClient,
    uri: URI,
): HttpResponse<String> =
    client.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString())

private fun head(
    client: HttpClient,
    uri: URI,
): Int =
    client
        .send(
            HttpRequest.newBuilder(uri).method("HEAD", emptyBody()).build(),
            HttpResponse.BodyHandlers.discarding(),
        ).statusCode()

private fun request(
    client: HttpClient,
    uri: URI,
    configure: HttpRequest.Builder.() -> HttpRequest.Builder,
): HttpResponse<String> =
    client.send(
        HttpRequest
            .newBuilder(uri)
            .timeout(Duration.ofSeconds(2))
            .configure()
            .build(),
        HttpResponse.BodyHandlers.ofString(),
    )

private fun emptyBody(): HttpRequest.BodyPublisher = HttpRequest.BodyPublishers.noBody()

private fun awaitCondition(condition: () -> Boolean) {
    val deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos()
    while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
    check(condition()) { "Condition was not met before the test deadline" }
}

private const val INDEXED_AT: Long = 99
private const val OK: Int = 200
private const val TEMPORARY_REDIRECT: Int = 307
private const val NOT_MODIFIED: Int = 304
private const val BAD_REQUEST: Int = 400
private const val UNAUTHORIZED: Int = 401
private const val NOT_FOUND: Int = 404
private const val METHOD_NOT_ALLOWED: Int = 405
private const val SERVICE_UNAVAILABLE: Int = 503
