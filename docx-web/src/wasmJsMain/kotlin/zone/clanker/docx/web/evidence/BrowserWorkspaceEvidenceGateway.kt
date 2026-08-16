package zone.clanker.docx.web.evidence

import kotlinx.browser.window
import kotlinx.coroutines.await
import zone.clanker.docx.web.site.EndpointFallbackWorkspaceEvidenceGateway
import zone.clanker.docx.web.site.LiveWorkspaceEvidenceGateway
import zone.clanker.docx.web.site.LoadedWorkspaceSite
import zone.clanker.docx.web.site.WorkspaceEvidenceGateway
import zone.clanker.docx.web.site.WorkspaceEvidenceHttpResponse
import zone.clanker.docx.web.site.WorkspaceEvidenceHttpTransport
import zone.clanker.docx.web.site.WorkspaceSiteLoader
import zone.clanker.docx.web.site.mountedWorkspaceEvidenceEndpoint
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsString
import kotlin.js.Promise

internal fun browserWorkspaceEvidenceGateway(
    loader: WorkspaceSiteLoader,
    site: LoadedWorkspaceSite,
): WorkspaceEvidenceGateway {
    val static = loader.staticEvidenceGateway(site)
    val endpoint = mountedWorkspaceEvidenceEndpoint(window.location.pathname) ?: return static
    val live = LiveWorkspaceEvidenceGateway(endpoint, BrowserWorkspaceEvidenceHttpTransport)
    return EndpointFallbackWorkspaceEvidenceGateway(live, static)
}

private data object BrowserWorkspaceEvidenceHttpTransport : WorkspaceEvidenceHttpTransport {
    @OptIn(ExperimentalWasmJsInterop::class)
    override suspend fun post(
        endpoint: String,
        body: String,
    ): WorkspaceEvidenceHttpResponse {
        require(endpoint.isNotBlank()) { "Workspace-evidence endpoint must not be blank" }
        require(body.isNotBlank()) { "Workspace-evidence request body must not be blank" }
        val packed: JsString = postWorkspaceEvidence(endpoint, body).await()
        val value = packed.toString()
        val separator = value.indexOf('\n')
        require(separator > 0) { "Workspace-evidence HTTP response is malformed" }
        return WorkspaceEvidenceHttpResponse(
            statusCode = value.substring(0, separator).toInt(),
            body = value.substring(separator + 1),
        )
    }
}

@OptIn(ExperimentalWasmJsInterop::class)
private val postWorkspaceEvidence: (String, String) -> Promise<JsString> =
    js(
        """(endpoint, body) => window.fetch(endpoint, {
                method: "POST",
                credentials: "same-origin",
                redirect: "error",
                headers: {
                    "Accept": "application/json",
                    "Content-Type": "application/json"
                },
                body: body
            }).then(async (response) => String(response.status) + "\n" + await response.text())""",
    )
