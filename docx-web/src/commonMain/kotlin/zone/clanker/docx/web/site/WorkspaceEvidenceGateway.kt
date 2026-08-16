package zone.clanker.docx.web.site

import zone.clanker.report.model.WorkspaceDeclarationEvidencePage
import zone.clanker.report.model.WorkspaceDeclarationEvidenceRequest
import zone.clanker.report.model.WorkspaceEvidenceJson
import zone.clanker.report.model.WorkspaceRelationshipOccurrencePage
import zone.clanker.report.model.WorkspaceRelationshipOccurrenceRequest
import zone.clanker.report.model.WorkspaceReverseUsagePage
import zone.clanker.report.model.WorkspaceReverseUsageRequest

internal interface WorkspaceEvidenceGateway {
    suspend fun declaration(request: WorkspaceDeclarationEvidenceRequest): WorkspaceDeclarationEvidencePage

    suspend fun reverseUsages(request: WorkspaceReverseUsageRequest): WorkspaceReverseUsagePage

    suspend fun relationshipOccurrences(
        request: WorkspaceRelationshipOccurrenceRequest,
    ): WorkspaceRelationshipOccurrencePage
}

internal data class WorkspaceEvidenceHttpResponse(
    val statusCode: Int,
    val body: String,
) {
    init {
        require(statusCode in MIN_HTTP_STATUS..MAX_HTTP_STATUS) { "Invalid workspace-evidence HTTP status" }
    }
}

internal fun interface WorkspaceEvidenceHttpTransport {
    suspend fun post(
        endpoint: String,
        body: String,
    ): WorkspaceEvidenceHttpResponse
}

internal class LiveWorkspaceEvidenceGateway(
    private val endpoint: String,
    private val transport: WorkspaceEvidenceHttpTransport,
) : WorkspaceEvidenceGateway {
    init {
        require(MOUNTED_EVIDENCE_ENDPOINT.matches(endpoint)) {
            "Live workspace-evidence endpoints must use the same-origin mounted-workspace route"
        }
    }

    override suspend fun declaration(request: WorkspaceDeclarationEvidenceRequest): WorkspaceDeclarationEvidencePage =
        post(
            resource = "declaration",
            body = WorkspaceEvidenceJson.encodeDeclarationRequest(request),
            decode = WorkspaceEvidenceJson.decodeDeclarationPage,
        ).also { page ->
            require(page.target == request.target && page.symbolId == request.symbolId) {
                "Workspace declaration-evidence response does not match its request"
            }
        }

    override suspend fun reverseUsages(request: WorkspaceReverseUsageRequest): WorkspaceReverseUsagePage =
        post(
            resource = "usages",
            body = WorkspaceEvidenceJson.encodeReverseUsageRequest(request),
            decode = WorkspaceEvidenceJson.decodeReverseUsagePage,
        ).also { page ->
            require(page.target == request.target && page.symbolId == request.symbolId) {
                "Workspace reverse-usage response does not match its request"
            }
        }

    override suspend fun relationshipOccurrences(
        request: WorkspaceRelationshipOccurrenceRequest,
    ): WorkspaceRelationshipOccurrencePage =
        post(
            resource = "occurrences",
            body = WorkspaceEvidenceJson.encodeOccurrenceRequest(request),
            decode = WorkspaceEvidenceJson.decodeOccurrencePage,
        ).also { page ->
            require(page.target == request.target && page.selector == request.selector) {
                "Workspace relationship-occurrence response does not match its request"
            }
        }

    private suspend fun <T> post(
        resource: String,
        body: String,
        decode: (String) -> T,
    ): T {
        val response = transport.post("$endpoint/$resource", body)
        return when (response.statusCode) {
            HTTP_OK -> decode(response.body)
            HTTP_NOT_FOUND,
            HTTP_METHOD_NOT_ALLOWED,
            -> throw WorkspaceEvidenceEndpointUnavailable(response.statusCode)
            else -> throw WorkspaceEvidenceHttpException(response.statusCode, response.body)
        }
    }
}

/** Falls back only when a static host does not implement the mounted read-only boundary. */
internal class EndpointFallbackWorkspaceEvidenceGateway(
    private val live: WorkspaceEvidenceGateway,
    private val static: WorkspaceEvidenceGateway,
) : WorkspaceEvidenceGateway {
    override suspend fun declaration(request: WorkspaceDeclarationEvidenceRequest): WorkspaceDeclarationEvidencePage =
        fallback { gateway -> gateway.declaration(request) }

    override suspend fun reverseUsages(request: WorkspaceReverseUsageRequest): WorkspaceReverseUsagePage =
        fallback { gateway -> gateway.reverseUsages(request) }

    override suspend fun relationshipOccurrences(
        request: WorkspaceRelationshipOccurrenceRequest,
    ): WorkspaceRelationshipOccurrencePage = fallback { gateway -> gateway.relationshipOccurrences(request) }

    private suspend fun <T> fallback(load: suspend (WorkspaceEvidenceGateway) -> T): T =
        try {
            load(live)
        } catch (_: WorkspaceEvidenceEndpointUnavailable) {
            load(static)
        }
}

internal class WorkspaceEvidenceEndpointUnavailable(
    statusCode: Int,
) : IllegalStateException("Mounted workspace-evidence endpoint is unavailable ($statusCode)")

internal class WorkspaceEvidenceHttpException(
    val statusCode: Int,
    body: String,
) : IllegalStateException("Workspace-evidence request failed ($statusCode): ${body.take(MAX_ERROR_BODY_LENGTH)}")

internal fun mountedWorkspaceEvidenceEndpoint(pathname: String): String? {
    if (!pathname.startsWith(MOUNT_PREFIX)) return null
    val workspaceId = pathname.removePrefix(MOUNT_PREFIX).substringBefore('/')
    if (!MOUNT_ID.matches(workspaceId)) return null
    return "$MOUNT_PREFIX$workspaceId/$MOUNTED_EVIDENCE_SUFFIX"
}

private val MOUNT_ID = Regex("[a-z0-9][a-z0-9._-]{0,63}")
private val MOUNTED_EVIDENCE_ENDPOINT = Regex("/w/[a-z0-9][a-z0-9._-]{0,63}/api/evidence")
private const val MOUNT_PREFIX: String = "/w/"
private const val MOUNTED_EVIDENCE_SUFFIX: String = "api/evidence"
private const val HTTP_OK: Int = 200
private const val HTTP_NOT_FOUND: Int = 404
private const val HTTP_METHOD_NOT_ALLOWED: Int = 405
private const val MIN_HTTP_STATUS: Int = 100
private const val MAX_HTTP_STATUS: Int = 599
private const val MAX_ERROR_BODY_LENGTH: Int = 512
