package zone.clanker.docx.service.client

import zone.clanker.docx.service.workspace.requireWorkspaceId
import zone.clanker.report.model.WorkspaceCatalogResponse
import zone.clanker.report.model.WorkspaceDeclarationEvidencePage
import zone.clanker.report.model.WorkspaceDeclarationEvidenceRequest
import zone.clanker.report.model.WorkspaceEvidenceJson
import zone.clanker.report.model.WorkspaceGraphJson
import zone.clanker.report.model.WorkspaceGraphRequest
import zone.clanker.report.model.WorkspaceGraphSlice
import zone.clanker.report.model.WorkspaceMount
import zone.clanker.report.model.WorkspaceRegistrationRequest
import zone.clanker.report.model.WorkspaceRelationshipOccurrencePage
import zone.clanker.report.model.WorkspaceRelationshipOccurrenceRequest
import zone.clanker.report.model.WorkspaceReverseUsagePage
import zone.clanker.report.model.WorkspaceReverseUsageRequest
import zone.clanker.report.model.WorkspaceServiceJson
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path

internal class WorkspaceServiceClient(
    private val connection: ServiceConnection,
    private val client: HttpClient = HttpClient.newHttpClient(),
) {
    fun register(
        workspaceId: String,
        siteDirectory: Path,
    ): WorkspaceMount {
        val request =
            WorkspaceRegistrationRequest(
                workspaceId = workspaceId,
                siteDirectory = siteDirectory.toAbsolutePath().normalize().toString(),
            )
        val response =
            send(
                requestBuilder("api/v1/workspaces")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(WorkspaceServiceJson.encodeRegistration(request)))
                    .build(),
            )
        require(response.statusCode() == OK || response.statusCode() == CREATED) { serviceFailure(response) }
        return WorkspaceServiceJson.decodeMount(response.body())
    }

    fun unregister(workspaceId: String) {
        val response =
            send(
                requestBuilder("api/v1/workspaces/${requireWorkspaceId(workspaceId)}")
                    .DELETE()
                    .build(),
            )
        require(response.statusCode() == NO_CONTENT) { serviceFailure(response) }
    }

    fun list(): WorkspaceCatalogResponse {
        val response =
            send(
                requestBuilder("api/v1/workspaces")
                    .GET()
                    .build(),
            )
        require(response.statusCode() == OK) { serviceFailure(response) }
        return WorkspaceServiceJson.decodeCatalog(response.body())
    }

    fun graphSlice(
        workspaceId: String,
        request: WorkspaceGraphRequest,
    ): WorkspaceGraphSlice {
        val response =
            send(
                requestBuilder("api/v1/workspaces/${requireWorkspaceId(workspaceId)}/graph")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(WorkspaceGraphJson.encodeRequest(request)))
                    .build(),
            )
        require(response.statusCode() == OK) { serviceFailure(response) }
        return WorkspaceGraphJson.decodeSlice(response.body())
    }

    fun declarationEvidence(
        workspaceId: String,
        request: WorkspaceDeclarationEvidenceRequest,
    ): WorkspaceDeclarationEvidencePage =
        postEvidence(
            workspaceId = workspaceId,
            resource = "declaration",
            body = WorkspaceEvidenceJson.encodeDeclarationRequest(request),
            decode = WorkspaceEvidenceJson.decodeDeclarationPage,
        )

    fun reverseUsages(
        workspaceId: String,
        request: WorkspaceReverseUsageRequest,
    ): WorkspaceReverseUsagePage =
        postEvidence(
            workspaceId = workspaceId,
            resource = "usages",
            body = WorkspaceEvidenceJson.encodeReverseUsageRequest(request),
            decode = WorkspaceEvidenceJson.decodeReverseUsagePage,
        )

    fun relationshipOccurrences(
        workspaceId: String,
        request: WorkspaceRelationshipOccurrenceRequest,
    ): WorkspaceRelationshipOccurrencePage =
        postEvidence(
            workspaceId = workspaceId,
            resource = "occurrences",
            body = WorkspaceEvidenceJson.encodeOccurrenceRequest(request),
            decode = WorkspaceEvidenceJson.decodeOccurrencePage,
        )

    private fun <T> postEvidence(
        workspaceId: String,
        resource: String,
        body: String,
        decode: (String) -> T,
    ): T {
        val response =
            send(
                requestBuilder("api/v1/workspaces/${requireWorkspaceId(workspaceId)}/evidence/$resource")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(),
            )
        require(response.statusCode() == OK) { serviceFailure(response) }
        return decode(response.body())
    }

    private fun requestBuilder(relativePath: String): HttpRequest.Builder {
        val builder = HttpRequest.newBuilder(connection.baseUrl.resolve(relativePath))
        connection.token?.let { token -> builder.header("Authorization", "Bearer $token") }
        return builder
    }

    private fun send(request: HttpRequest): HttpResponse<String> =
        client.send(request, HttpResponse.BodyHandlers.ofString())

    private fun serviceFailure(response: HttpResponse<String>): String =
        "DOCX service request failed with HTTP ${response.statusCode()}: ${response.body()}"

    private companion object {
        const val OK: Int = 200
        const val CREATED: Int = 201
        const val NO_CONTENT: Int = 204
    }
}
