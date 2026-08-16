package zone.clanker.docx.web.atlas.source

import zone.clanker.report.model.WorkspaceGraphJson
import zone.clanker.report.model.WorkspaceGraphRequest
import zone.clanker.report.model.WorkspaceGraphSlice

/** A transport-neutral HTTP result so the live source remains deterministic in common tests. */
internal data class WorkspaceGraphHttpResponse(
    val statusCode: Int,
    val body: String,
) {
    init {
        require(statusCode in MIN_HTTP_STATUS..MAX_HTTP_STATUS) { "Invalid workspace-graph HTTP status" }
    }
}

internal fun interface WorkspaceGraphHttpTransport {
    suspend fun post(
        endpoint: String,
        body: String,
    ): WorkspaceGraphHttpResponse
}

/** Reads one bounded graph slice from the SQLite-backed service without exposing its bearer token. */
internal class LiveWorkspaceGraphSliceSource(
    private val endpoint: String,
    private val transport: WorkspaceGraphHttpTransport,
) : WorkspaceGraphSliceSource {
    init {
        require(MOUNTED_GRAPH_ENDPOINT.matches(endpoint)) {
            "Live workspace-graph endpoints must use the same-origin mounted-workspace route"
        }
    }

    override suspend fun load(request: WorkspaceGraphRequest): WorkspaceGraphSlice {
        val response = transport.post(endpoint, WorkspaceGraphJson.encodeRequest(request))
        return when (response.statusCode) {
            HTTP_OK -> WorkspaceGraphJson.decodeSlice(response.body).also { slice -> slice.requireTarget(request) }
            HTTP_NOT_FOUND,
            HTTP_METHOD_NOT_ALLOWED,
            -> throw WorkspaceGraphEndpointUnavailable(response.statusCode)
            else -> throw WorkspaceGraphHttpException(response.statusCode, response.body)
        }
    }
}

/** Falls back only when a mounted static host does not implement the live endpoint. */
internal class EndpointFallbackWorkspaceGraphSliceSource(
    private val live: WorkspaceGraphSliceSource,
    private val static: WorkspaceGraphSliceSource,
) : WorkspaceGraphSliceSource {
    override suspend fun load(request: WorkspaceGraphRequest): WorkspaceGraphSlice =
        try {
            live.load(request)
        } catch (_: WorkspaceGraphEndpointUnavailable) {
            static.load(request)
        }
}

internal class WorkspaceGraphEndpointUnavailable(
    statusCode: Int,
) : IllegalStateException("Mounted workspace-graph endpoint is unavailable ($statusCode)")

internal class WorkspaceGraphHttpException(
    val statusCode: Int,
    body: String,
) : IllegalStateException("Workspace-graph request failed ($statusCode): ${body.take(MAX_ERROR_BODY_LENGTH)}")

/** Returns a relative path so browser requests cannot cross an origin or leak service credentials. */
internal fun mountedWorkspaceGraphEndpoint(pathname: String): String? {
    if (!pathname.startsWith(MOUNT_PREFIX)) return null
    val workspaceId = pathname.removePrefix(MOUNT_PREFIX).substringBefore('/')
    if (!MOUNT_ID.matches(workspaceId)) return null
    return "$MOUNT_PREFIX$workspaceId/$MOUNTED_GRAPH_SUFFIX"
}

private fun WorkspaceGraphSlice.requireTarget(request: WorkspaceGraphRequest) {
    require(target.workspaceId == request.workspaceId) { "Workspace-graph response belongs to another workspace" }
    require(target.facet == request.facet) { "Workspace-graph response belongs to another facet" }
    require(request.generationId == null || target.generationId == request.generationId) {
        "Workspace-graph response belongs to another generation"
    }
    require(viewport.scopeRootIds == request.view.selection.scopeRootIds) {
        "Workspace-graph response belongs to another scope"
    }
}

private val MOUNT_ID = Regex("[a-z0-9][a-z0-9._-]{0,63}")
private val MOUNTED_GRAPH_ENDPOINT = Regex("/w/[a-z0-9][a-z0-9._-]{0,63}/api/graph")
private const val MOUNT_PREFIX: String = "/w/"
private const val MOUNTED_GRAPH_SUFFIX: String = "api/graph"
private const val HTTP_OK: Int = 200
private const val HTTP_NOT_FOUND: Int = 404
private const val HTTP_METHOD_NOT_ALLOWED: Int = 405
private const val MIN_HTTP_STATUS: Int = 100
private const val MAX_HTTP_STATUS: Int = 599
private const val MAX_ERROR_BODY_LENGTH: Int = 512
