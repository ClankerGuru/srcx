package zone.clanker.docx.service.http

import com.sun.net.httpserver.HttpExchange
import zone.clanker.docx.service.index.FindingQuery
import zone.clanker.docx.service.index.GraphQuery
import zone.clanker.docx.service.index.RelationshipQuery
import zone.clanker.docx.service.index.SymbolQuery
import zone.clanker.docx.service.index.WorkspaceGraphQueryIndex
import zone.clanker.docx.service.index.WorkspaceQueryIndex
import zone.clanker.docx.service.index.WorkspaceQueryScope
import zone.clanker.docx.service.workspace.WorkspaceRegistry
import zone.clanker.docx.service.workspace.requireWorkspaceId
import zone.clanker.report.model.FindingSeverity
import zone.clanker.report.model.RelationshipKind
import zone.clanker.report.model.SymbolKind
import zone.clanker.report.model.WorkspaceGraphJson
import zone.clanker.report.model.WorkspaceIndexStatus
import zone.clanker.report.model.WorkspaceQueryJson
import zone.clanker.report.model.WorkspaceServiceError
import zone.clanker.report.model.WorkspaceServiceJson
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

internal class WorkspaceIndexHttpApi(
    private val registry: WorkspaceRegistry,
    private val index: WorkspaceQueryIndex?,
    private val graphIndex: WorkspaceGraphQueryIndex?,
    private val authenticate: (HttpExchange, () -> Unit) -> Unit,
) {
    fun respond(
        exchange: HttpExchange,
        workspaceRoute: String,
    ): Boolean {
        val segments = workspaceRoute.split('/')
        val resource = segments.getOrNull(1)
        val recognized = segments.size == 2 && resource in RESOURCES + GRAPH_RESOURCE
        if (recognized) {
            if (resource == GRAPH_RESOURCE) {
                respondGraph(exchange, segments.first())
            } else {
                respondResource(exchange, segments.first(), requireNotNull(resource))
            }
        }
        return recognized
    }

    /** Read-only graph boundary used by the viewer mounted at `/w/{workspaceId}/`. */
    fun respondMountedGraph(
        exchange: HttpExchange,
        mountedRoute: String,
    ): Boolean {
        val segments = mountedRoute.split('/')
        val recognized =
            segments.size == MOUNTED_GRAPH_SEGMENT_COUNT &&
                segments[1] == MOUNTED_API_SEGMENT &&
                segments[2] == GRAPH_RESOURCE
        if (recognized) respondGraph(exchange, segments.first(), authenticateRequest = false)
        return recognized
    }

    private fun respondGraph(
        exchange: HttpExchange,
        workspaceIdValue: String,
        authenticateRequest: Boolean = true,
    ) {
        if (exchange.requestMethod != "POST") {
            exchange.responseHeaders.set("Allow", "POST")
            respondError(exchange, METHOD_NOT_ALLOWED, "Method not allowed.")
            return
        }
        val query = { respondGraphQuery(exchange, workspaceIdValue) }
        if (authenticateRequest) authenticate(exchange, query) else query()
    }

    private fun respondGraphQuery(
        exchange: HttpExchange,
        workspaceIdValue: String,
    ) {
        val workspaceId = requireWorkspaceId(workspaceIdValue)
        val mount = registry.mount(workspaceId)
        when {
            mount == null -> respondError(exchange, NOT_FOUND, "Workspace is not registered.")
            graphIndex == null -> respondError(exchange, SERVICE_UNAVAILABLE, "Workspace indexing is disabled.")
            mount.index !is WorkspaceIndexStatus.Current ->
                respondError(exchange, SERVICE_UNAVAILABLE, "Workspace index is not current.")
            else -> {
                val request = WorkspaceGraphJson.decodeRequest(readGraphRequest(exchange))
                val response = requireNotNull(graphIndex).graphSlice(GraphQuery(workspaceId, request))
                respondJson(exchange, OK, WorkspaceGraphJson.encodeSlice(response))
            }
        }
    }

    private fun respondResource(
        exchange: HttpExchange,
        workspaceIdValue: String,
        resource: String,
    ) {
        if (exchange.requestMethod != "GET") {
            exchange.responseHeaders.set("Allow", "GET")
            respondError(exchange, METHOD_NOT_ALLOWED, "Method not allowed.")
        } else {
            respondWorkspaceQuery(exchange, workspaceIdValue, resource)
        }
    }

    private fun respondWorkspaceQuery(
        exchange: HttpExchange,
        workspaceIdValue: String,
        resource: String,
    ) {
        val workspaceId = requireWorkspaceId(workspaceIdValue)
        val mount = registry.mount(workspaceId)
        when {
            mount == null -> respondError(exchange, NOT_FOUND, "Workspace is not registered.")
            index == null -> respondError(exchange, SERVICE_UNAVAILABLE, "Workspace indexing is disabled.")
            mount.index !is WorkspaceIndexStatus.Current ->
                respondError(exchange, SERVICE_UNAVAILABLE, "Workspace index is not current.")
            else -> respondQuery(exchange, workspaceId, resource, parseQuery(exchange.requestURI.rawQuery))
        }
    }

    private fun respondQuery(
        exchange: HttpExchange,
        workspaceId: String,
        resource: String,
        parameters: Map<String, List<String>>,
    ) {
        val scope =
            WorkspaceQueryScope(
                buildId = parameters.single("buildId"),
                projectId = parameters.single("projectId"),
                sourceSetIds = parameters.values("sourceSetId").toSet(),
            )
        val content =
            when (resource) {
                "symbols" -> symbolSearch(workspaceId, scope, parameters)
                "relationships" -> relationshipSummary(workspaceId, scope, parameters)
                "findings" -> findingSummary(workspaceId, scope, parameters)
                else -> error("Unsupported index resource: $resource")
            }
        respondJson(exchange, OK, content)
    }

    private fun symbolSearch(
        workspaceId: String,
        scope: WorkspaceQueryScope,
        parameters: Map<String, List<String>>,
    ): String {
        val limit =
            parameters.single("limit")?.let { value ->
                requireNotNull(value.toIntOrNull()) { "Symbol query limit must be an integer." }
            } ?: DEFAULT_LIMIT
        require(limit in 1..MAX_LIMIT) { "Symbol query limit must be between 1 and $MAX_LIMIT." }
        val response =
            requireNotNull(index).searchSymbols(
                SymbolQuery(
                    workspaceId = workspaceId,
                    query = parameters.single("q").orEmpty(),
                    scope = scope,
                    kinds = parameters.enumValues<SymbolKind>("kind"),
                    limit = limit,
                ),
            )
        return WorkspaceQueryJson.encodeSymbolSearch(response)
    }

    private fun relationshipSummary(
        workspaceId: String,
        scope: WorkspaceQueryScope,
        parameters: Map<String, List<String>>,
    ): String =
        requireNotNull(index)
            .relationshipSummary(
                RelationshipQuery(
                    workspaceId = workspaceId,
                    scope = scope,
                    kinds = parameters.enumValues<RelationshipKind>("kind"),
                ),
            ).let(WorkspaceQueryJson::encodeRelationshipSummary)

    private fun findingSummary(
        workspaceId: String,
        scope: WorkspaceQueryScope,
        parameters: Map<String, List<String>>,
    ): String =
        requireNotNull(index)
            .findingSummary(
                FindingQuery(
                    workspaceId = workspaceId,
                    scope = scope,
                    severities = parameters.enumValues<FindingSeverity>("severity"),
                ),
            ).let(WorkspaceQueryJson::encodeFindingSummary)

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

    private companion object {
        val RESOURCES: Set<String> = setOf("symbols", "relationships", "findings")
        const val GRAPH_RESOURCE: String = "graph"
        const val MOUNTED_API_SEGMENT: String = "api"
        const val MOUNTED_GRAPH_SEGMENT_COUNT: Int = 3
        const val DEFAULT_LIMIT: Int = 50
        const val MAX_LIMIT: Int = 500
        const val OK: Int = 200
        const val NOT_FOUND: Int = 404
        const val METHOD_NOT_ALLOWED: Int = 405
        const val SERVICE_UNAVAILABLE: Int = 503
    }
}

private fun readGraphRequest(exchange: HttpExchange): String {
    val bytes = exchange.requestBody.readNBytes(MAX_GRAPH_REQUEST_BYTES + 1)
    require(bytes.size <= MAX_GRAPH_REQUEST_BYTES) { "Workspace graph request is too large." }
    return bytes.decodeToString()
}

private fun parseQuery(rawQuery: String?): Map<String, List<String>> =
    rawQuery
        ?.split('&')
        ?.filter(String::isNotBlank)
        ?.map { field -> field.substringBefore('=') to field.substringAfter('=', "") }
        ?.groupBy(
            keySelector = { (key) -> decodeQueryValue(key) },
            valueTransform = { (_, value) -> decodeQueryValue(value) },
        ).orEmpty()

private fun decodeQueryValue(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8)

private fun Map<String, List<String>>.single(name: String): String? =
    get(name)?.let { values ->
        require(values.size == 1) { "Query parameter '$name' may be supplied only once." }
        values.single()
    }

private fun Map<String, List<String>>.values(name: String): List<String> =
    get(name).orEmpty().flatMap { value -> value.split(',').filter(String::isNotBlank) }

private inline fun <reified T : Enum<T>> Map<String, List<String>>.enumValues(name: String): Set<T> =
    values(name).mapTo(mutableSetOf()) { value -> enumValueOf<T>(value.uppercase()) }

private const val MAX_GRAPH_REQUEST_BYTES: Int = 65_536
