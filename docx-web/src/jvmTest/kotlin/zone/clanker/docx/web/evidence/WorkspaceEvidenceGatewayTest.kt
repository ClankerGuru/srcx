package zone.clanker.docx.web.evidence

import kotlinx.coroutines.test.runTest
import zone.clanker.docx.web.site.EndpointFallbackWorkspaceEvidenceGateway
import zone.clanker.docx.web.site.LiveWorkspaceEvidenceGateway
import zone.clanker.docx.web.site.WorkspaceEvidenceGateway
import zone.clanker.docx.web.site.WorkspaceEvidenceHttpException
import zone.clanker.docx.web.site.WorkspaceEvidenceHttpResponse
import zone.clanker.docx.web.site.mountedWorkspaceEvidenceEndpoint
import zone.clanker.report.model.WorkspaceDeclarationEvidencePage
import zone.clanker.report.model.WorkspaceDeclarationEvidenceRequest
import zone.clanker.report.model.WorkspaceEvidenceJson
import zone.clanker.report.model.WorkspaceEvidenceTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WorkspaceEvidenceGatewayTest {
    @Test
    fun recognizesOnlySameOriginMountedEvidenceEndpoints() {
        assertEquals("/w/atlas/api/evidence", mountedWorkspaceEvidenceEndpoint("/w/atlas/index.html"))
        assertEquals(null, mountedWorkspaceEvidenceEndpoint("/index.html"))
        assertEquals(null, mountedWorkspaceEvidenceEndpoint("/w/INVALID/index.html"))
    }

    @Test
    fun fallsBackOnlyForMissingOrUnsupportedMountedRoutes() =
        runTest {
            val request = WorkspaceDeclarationEvidenceRequest(TARGET, "symbol:target")
            val expected = WorkspaceDeclarationEvidencePage(TARGET, request.symbolId)
            var staticReads = 0
            val static =
                declarationGateway {
                    staticReads += 1
                    expected
                }
            val missing = liveGateway(404, "not found")

            val page = EndpointFallbackWorkspaceEvidenceGateway(missing, static).declaration(request)

            assertEquals(expected, page)
            assertEquals(1, staticReads)

            listOf(401, 409, 500, 503).forEach { status ->
                assertFailsWith<WorkspaceEvidenceHttpException> {
                    EndpointFallbackWorkspaceEvidenceGateway(liveGateway(status, "failure"), static)
                        .declaration(request)
                }
            }
            assertEquals(1, staticReads)
        }

    @Test
    fun decodesAndValidatesThePinnedLivePage() =
        runTest {
            val request = WorkspaceDeclarationEvidenceRequest(TARGET, "symbol:target")
            val expected = WorkspaceDeclarationEvidencePage(TARGET, request.symbolId)
            var requestedEndpoint: String? = null
            val gateway =
                LiveWorkspaceEvidenceGateway("/w/atlas/api/evidence") { endpoint, _ ->
                    requestedEndpoint = endpoint
                    WorkspaceEvidenceHttpResponse(200, WorkspaceEvidenceJson.encodeDeclarationPage(expected))
                }

            assertEquals(expected, gateway.declaration(request))
            assertEquals("/w/atlas/api/evidence/declaration", requestedEndpoint)
        }
}

private fun liveGateway(
    status: Int,
    body: String,
): LiveWorkspaceEvidenceGateway =
    LiveWorkspaceEvidenceGateway("/w/atlas/api/evidence") { _, _ -> WorkspaceEvidenceHttpResponse(status, body) }

private fun declarationGateway(
    declaration: suspend (WorkspaceDeclarationEvidenceRequest) -> WorkspaceDeclarationEvidencePage,
): WorkspaceEvidenceGateway =
    object : WorkspaceEvidenceGateway {
        override suspend fun declaration(
            request: WorkspaceDeclarationEvidenceRequest,
        ): WorkspaceDeclarationEvidencePage = declaration(request)

        override suspend fun reverseUsages(request: zone.clanker.report.model.WorkspaceReverseUsageRequest) =
            error("Unexpected reverse-usage request")

        override suspend fun relationshipOccurrences(
            request: zone.clanker.report.model.WorkspaceRelationshipOccurrenceRequest,
        ) = error("Unexpected relationship-occurrence request")
    }

private val TARGET = WorkspaceEvidenceTarget("workspace:atlas", "generation:atlas")
