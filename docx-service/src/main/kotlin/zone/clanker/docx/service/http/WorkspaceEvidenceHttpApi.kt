package zone.clanker.docx.service.http

import com.sun.net.httpserver.HttpExchange
import zone.clanker.docx.service.index.WorkspaceEvidenceQueryIndex
import zone.clanker.docx.service.workspace.WorkspaceRegistry
import zone.clanker.docx.service.workspace.requireWorkspaceId
import zone.clanker.report.model.WorkspaceEvidenceJson
import zone.clanker.report.model.WorkspaceIndexStatus
import zone.clanker.report.model.WorkspaceServiceError
import zone.clanker.report.model.WorkspaceServiceJson

internal class WorkspaceEvidenceHttpApi(
    private val registry: WorkspaceRegistry,
    private val index: WorkspaceEvidenceQueryIndex?,
    private val authenticate: (HttpExchange, () -> Unit) -> Unit,
) {
    fun respond(
        exchange: HttpExchange,
        workspaceRoute: String,
    ): Boolean {
        val segments = workspaceRoute.split('/')
        val recognized =
            segments.size == ROUTE_SEGMENT_COUNT &&
                segments[1] == EVIDENCE_SEGMENT &&
                segments[2] in RESOURCES
        if (recognized) respondEvidence(exchange, segments.first(), segments.last(), authenticateRequest = true)
        return recognized
    }

    /** Read-only evidence boundary used by the viewer mounted at `/w/{workspaceId}/`. */
    fun respondMounted(
        exchange: HttpExchange,
        mountedRoute: String,
    ): Boolean {
        val segments = mountedRoute.split('/')
        val recognized =
            segments.size == MOUNTED_ROUTE_SEGMENT_COUNT &&
                segments[1] == API_SEGMENT &&
                segments[2] == EVIDENCE_SEGMENT &&
                segments[3] in RESOURCES
        if (recognized) respondEvidence(exchange, segments.first(), segments.last(), authenticateRequest = false)
        return recognized
    }

    private fun respondEvidence(
        exchange: HttpExchange,
        workspaceIdValue: String,
        resource: String,
        authenticateRequest: Boolean,
    ) {
        if (exchange.requestMethod != "POST") {
            exchange.responseHeaders.set("Allow", "POST")
            respondError(exchange, METHOD_NOT_ALLOWED, "Method not allowed.")
            return
        }
        val query = {
            val workspaceId = requireWorkspaceId(workspaceIdValue)
            val mount = registry.mount(workspaceId)
            when {
                mount == null -> respondError(exchange, NOT_FOUND, "Workspace is not registered.")
                index == null -> respondError(exchange, SERVICE_UNAVAILABLE, "Workspace indexing is disabled.")
                mount.index !is WorkspaceIndexStatus.Current ->
                    respondError(exchange, SERVICE_UNAVAILABLE, "Workspace index is not current.")
                else -> respondJson(exchange, OK, query(workspaceId, resource, readEvidenceRequest(exchange)))
            }
        }
        if (authenticateRequest) authenticate(exchange, query) else query()
    }

    private fun query(
        workspaceId: String,
        resource: String,
        content: String,
    ): String =
        when (resource) {
            DECLARATION_RESOURCE ->
                requireNotNull(index)
                    .declarationEvidence(workspaceId, WorkspaceEvidenceJson.decodeDeclarationRequest(content))
                    .let(WorkspaceEvidenceJson.encodeDeclarationPage)
            USAGES_RESOURCE ->
                requireNotNull(index)
                    .reverseUsages(workspaceId, WorkspaceEvidenceJson.decodeReverseUsageRequest(content))
                    .let(WorkspaceEvidenceJson.encodeReverseUsagePage)
            OCCURRENCES_RESOURCE ->
                requireNotNull(index)
                    .relationshipOccurrences(workspaceId, WorkspaceEvidenceJson.decodeOccurrenceRequest(content))
                    .let(WorkspaceEvidenceJson.encodeOccurrencePage)
            else -> error("Unsupported workspace evidence resource: $resource")
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

    private companion object {
        const val EVIDENCE_SEGMENT: String = "evidence"
        const val API_SEGMENT: String = "api"
        const val DECLARATION_RESOURCE: String = "declaration"
        const val USAGES_RESOURCE: String = "usages"
        const val OCCURRENCES_RESOURCE: String = "occurrences"
        const val ROUTE_SEGMENT_COUNT: Int = 3
        const val MOUNTED_ROUTE_SEGMENT_COUNT: Int = 4
        val RESOURCES: Set<String> = setOf(DECLARATION_RESOURCE, USAGES_RESOURCE, OCCURRENCES_RESOURCE)
        const val OK: Int = 200
        const val NOT_FOUND: Int = 404
        const val METHOD_NOT_ALLOWED: Int = 405
        const val SERVICE_UNAVAILABLE: Int = 503
    }
}

private fun readEvidenceRequest(exchange: HttpExchange): String {
    val bytes = exchange.requestBody.readNBytes(MAX_EVIDENCE_REQUEST_BYTES + 1)
    require(bytes.size <= MAX_EVIDENCE_REQUEST_BYTES) { "Workspace evidence request is too large." }
    return bytes.decodeToString()
}

private const val MAX_EVIDENCE_REQUEST_BYTES: Int = 65_536
