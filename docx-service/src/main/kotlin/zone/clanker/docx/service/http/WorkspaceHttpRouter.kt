package zone.clanker.docx.service.http

import com.sun.net.httpserver.HttpExchange
import zone.clanker.docx.service.index.WorkspaceEvidenceQueryIndex
import zone.clanker.docx.service.index.WorkspaceGraphQueryIndex
import zone.clanker.docx.service.index.WorkspaceQueryIndex
import zone.clanker.docx.service.workspace.WorkspaceEventHub
import zone.clanker.docx.service.workspace.WorkspaceGenerationIndexer
import zone.clanker.docx.service.workspace.WorkspaceRegistry
import zone.clanker.docx.service.workspace.WorkspaceWatcher
import zone.clanker.docx.service.workspace.requireWorkspaceId
import zone.clanker.report.model.WorkspaceServiceError
import zone.clanker.report.model.WorkspaceServiceJson
import java.security.MessageDigest
import java.util.concurrent.CancellationException

internal class WorkspaceHttpRouter(
    private val registry: WorkspaceRegistry,
    private val events: WorkspaceEventHub,
    private val watcher: WorkspaceWatcher,
    private val indexer: WorkspaceGenerationIndexer,
    token: String,
) {
    private val expectedToken = token.encodeToByteArray()
    private val staticSites = StaticSiteResponder(registry)
    private val eventStream = WorkspaceEventStream(registry, events)
    private val evidenceApi =
        WorkspaceEvidenceHttpApi(
            registry = registry,
            index = indexer as? WorkspaceEvidenceQueryIndex,
            authenticate = ::authenticated,
        )
    private val indexApi =
        WorkspaceIndexHttpApi(
            registry = registry,
            index = indexer as? WorkspaceQueryIndex,
            graphIndex = indexer as? WorkspaceGraphQueryIndex,
            authenticate = ::authenticated,
        )

    fun handle(exchange: HttpExchange) {
        runCatching { dispatch(exchange) }
            .onFailure { error -> respondFailure(exchange, error) }
        exchange.close()
    }

    private fun dispatch(exchange: HttpExchange) {
        val path = exchange.requestURI.rawPath
        when {
            path == "/healthz" -> requireMethod(exchange, "GET") { respondJson(exchange, OK, "{\"status\":\"ok\"}") }
            path == "/api/v1/workspaces" -> handleWorkspaces(exchange)
            path.startsWith("/api/v1/workspaces/") ->
                path.removePrefix("/api/v1/workspaces/").let { workspaceRoute ->
                    if (!evidenceApi.respond(exchange, workspaceRoute) && !indexApi.respond(exchange, workspaceRoute)) {
                        handleWorkspace(exchange, workspaceRoute)
                    }
                }
            path == "/api/v1/events" -> requireMethod(exchange, "GET") { eventStream.respond(exchange) }
            path == "/" -> redirect(exchange, "/api/v1/workspaces")
            path.startsWith("/w/") ->
                path.removePrefix("/w/").let { mountedRoute ->
                    if (
                        !evidenceApi.respondMounted(exchange, mountedRoute) &&
                        !indexApi.respondMountedGraph(exchange, mountedRoute)
                    ) {
                        staticSites.respond(exchange)
                    }
                }
            else -> respondError(exchange, NOT_FOUND, "Not found.")
        }
    }

    private fun handleWorkspaces(exchange: HttpExchange) {
        when (exchange.requestMethod) {
            "GET" -> respondJson(exchange, OK, WorkspaceServiceJson.encodeCatalog(registry.catalog()))
            "POST" -> authenticated(exchange) { register(exchange) }
            else -> methodNotAllowed(exchange, "GET, POST")
        }
    }

    private fun handleWorkspace(
        exchange: HttpExchange,
        workspaceId: String,
    ) {
        if ('/' in workspaceId || '%' in workspaceId) {
            respondError(exchange, BAD_REQUEST, "Invalid workspace ID.")
            return
        }
        when (exchange.requestMethod) {
            "GET" -> {
                val mount = registry.mount(requireWorkspaceId(workspaceId))
                if (mount == null) {
                    respondError(exchange, NOT_FOUND, "Workspace is not registered.")
                } else {
                    respondJson(exchange, OK, WorkspaceServiceJson.encodeMount(mount))
                }
            }
            "DELETE" -> authenticated(exchange) { unregister(exchange, workspaceId) }
            else -> methodNotAllowed(exchange, "GET, DELETE")
        }
    }

    private fun register(exchange: HttpExchange) {
        val request = WorkspaceServiceJson.decodeRegistration(readRequestBody(exchange))
        val existed = registry.mount(request.workspaceId) != null
        val mutation = registry.register(request)
        mutation.event?.let(events::publish)
        watcher.requestRefresh()
        exchange.responseHeaders.set("Location", mutation.mount.viewerPath)
        respondJson(
            exchange,
            if (existed) OK else CREATED,
            WorkspaceServiceJson.encodeMount(mutation.mount),
        )
    }

    private fun unregister(
        exchange: HttpExchange,
        workspaceId: String,
    ) {
        val event = registry.unregister(workspaceId)
        if (event == null) {
            respondError(exchange, NOT_FOUND, "Workspace is not registered.")
            return
        }
        runCatching { indexer.remove(event.workspaceId) }
        events.publish(event)
        respondEmpty(exchange, NO_CONTENT)
    }

    private fun authenticated(
        exchange: HttpExchange,
        block: () -> Unit,
    ) {
        val supplied =
            exchange.requestHeaders
                .getFirst("Authorization")
                ?.removePrefix("Bearer ")
                ?.takeIf { it.isNotBlank() }
                ?.encodeToByteArray()
        if (supplied == null || !MessageDigest.isEqual(expectedToken, supplied)) {
            exchange.responseHeaders.set("WWW-Authenticate", "Bearer")
            respondError(exchange, UNAUTHORIZED, "A valid service token is required.")
        } else {
            block()
        }
    }

    private fun respondFailure(
        exchange: HttpExchange,
        error: Throwable,
    ) {
        if (exchange.responseCode != RESPONSE_NOT_SENT) return
        val status =
            when (error) {
                is IllegalArgumentException -> BAD_REQUEST
                is CancellationException -> SERVICE_UNAVAILABLE
                else -> INTERNAL_ERROR
            }
        val message = error.message ?: error.javaClass.simpleName
        runCatching { respondError(exchange, status, message) }
    }

    private fun requireMethod(
        exchange: HttpExchange,
        method: String,
        block: () -> Unit,
    ) {
        if (exchange.requestMethod == method) block() else methodNotAllowed(exchange, method)
    }

    private fun methodNotAllowed(
        exchange: HttpExchange,
        allowed: String,
    ) {
        exchange.responseHeaders.set("Allow", allowed)
        respondError(exchange, METHOD_NOT_ALLOWED, "Method not allowed.")
    }

    private fun respondError(
        exchange: HttpExchange,
        status: Int,
        message: String,
    ) = respondJson(exchange, status, WorkspaceServiceJson.encodeError(WorkspaceServiceError(message)))

    private fun respondJson(
        exchange: HttpExchange,
        status: Int,
        content: String,
    ) {
        val bytes = content.encodeToByteArray()
        exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        exchange.responseHeaders.set("Cache-Control", "no-store")
        securityHeaders(exchange)
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { output -> output.write(bytes) }
    }

    private fun respondEmpty(
        exchange: HttpExchange,
        status: Int,
    ) {
        securityHeaders(exchange)
        exchange.sendResponseHeaders(status, NO_RESPONSE_BODY)
    }

    private fun redirect(
        exchange: HttpExchange,
        location: String,
    ) {
        exchange.responseHeaders.set("Location", location)
        securityHeaders(exchange)
        exchange.sendResponseHeaders(TEMPORARY_REDIRECT, NO_RESPONSE_BODY)
    }

    private companion object {
        const val OK: Int = 200
        const val CREATED: Int = 201
        const val NO_CONTENT: Int = 204
        const val TEMPORARY_REDIRECT: Int = 307
        const val BAD_REQUEST: Int = 400
        const val UNAUTHORIZED: Int = 401
        const val NOT_FOUND: Int = 404
        const val METHOD_NOT_ALLOWED: Int = 405
        const val SERVICE_UNAVAILABLE: Int = 503
        const val INTERNAL_ERROR: Int = 500
        const val RESPONSE_NOT_SENT: Int = -1
        const val NO_RESPONSE_BODY: Long = -1
    }
}

private fun readRequestBody(exchange: HttpExchange): String {
    val bytes = exchange.requestBody.readNBytes(MAX_REQUEST_BYTES + 1)
    require(bytes.size <= MAX_REQUEST_BYTES) { "Registration request is too large." }
    return bytes.decodeToString()
}

internal fun securityHeaders(exchange: HttpExchange) {
    exchange.responseHeaders.set("X-Content-Type-Options", "nosniff")
    exchange.responseHeaders.set("Referrer-Policy", "no-referrer")
}

private const val MAX_REQUEST_BYTES: Int = 65_536
