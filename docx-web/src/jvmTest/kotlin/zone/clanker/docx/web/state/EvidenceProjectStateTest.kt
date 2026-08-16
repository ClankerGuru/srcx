package zone.clanker.docx.web.state

import zone.clanker.report.model.ProjectGraphShard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EvidenceProjectStateTest {
    @Test
    fun settlesOnlyTheCurrentlyRequestedProject() {
        val firstRequest = EvidenceProjectState().request("project:first")
        val secondRequest = firstRequest.request("project:second")

        assertSame(secondRequest, secondRequest.complete("project:first", project("project:first")))
        val settled = secondRequest.complete("project:second", project("project:second"))

        assertEquals("project:second", settled.requestedProjectId)
        assertEquals("project:second", settled.project?.projectId)
        assertFalse(settled.loading)
        assertNull(settled.error)
    }

    @Test
    fun retriesAFailedRequestWithANewRevision() {
        val requested = EvidenceProjectState().request("project:fixture")
        val failed = requested.fail("project:fixture", "fixture failure")
        val retried = failed.request("project:fixture")

        assertEquals(requested.requestRevision + 1, retried.requestRevision)
        assertTrue(retried.loading)
        assertNull(retried.error)
        assertNull(retried.project)
    }

    @Test
    fun keepsASettledShardWithoutStartingAnotherTransaction() {
        val project = project("project:fixture")
        val settled =
            EvidenceProjectState()
                .request(project.projectId)
                .complete(project.projectId, project)

        assertSame(settled, settled.request(project.projectId))
    }

    private fun project(projectId: String): ProjectGraphShard =
        ProjectGraphShard(
            projectId = projectId,
            sourceSets = emptyList(),
            files = emptyList(),
            symbols = emptyList(),
            relationships = emptyList(),
        )
}
