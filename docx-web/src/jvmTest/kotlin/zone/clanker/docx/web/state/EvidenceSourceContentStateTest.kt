package zone.clanker.docx.web.state

import zone.clanker.docx.web.fixture.DocxWebFixture
import zone.clanker.report.model.SourceFileSnapshot
import zone.clanker.report.model.SourceSetSnapshot
import zone.clanker.report.model.SymbolSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EvidenceSourceContentStateTest {
    @Test
    fun loadsExactContentForOnlyTheRequestedProjectFile() {
        val requested = EvidenceSourceContentState().request(PROJECT_ID, FILE_ID)
        val loaded =
            requested.complete(
                projectId = PROJECT_ID,
                fileId = FILE_ID,
                sourceContent = "class Fixture\n",
                sourceContentHash = "a".repeat(HASH_LENGTH),
                sourceEncodedByteSize = 14,
            )

        assertEquals("class Fixture\n", loaded.contentFor(PROJECT_ID, FILE_ID))
        assertNull(loaded.contentFor("project:other", FILE_ID))
        assertFalse(loaded.loading)
        assertNull(loaded.error)
    }

    @Test
    fun ignoresACompletionForAnObsoleteTargetAndAllowsFailureRetry() {
        val requested = EvidenceSourceContentState().request(PROJECT_ID, FILE_ID)

        assertSame(
            requested,
            requested.complete("project:other", "file:other", "other", null, 5),
        )
        val failed = requested.fail(PROJECT_ID, FILE_ID, "missing source body")
        val retried = failed.request(PROJECT_ID, FILE_ID)

        assertTrue(retried.loading)
        assertEquals(requested.requestRevision + 1, retried.requestRevision)
        assertNull(retried.error)
    }

    @Test
    fun resolvesFileSymbolAndRelationshipSelectionsToTheirSourceFile() {
        val project = DocxWebFixture.project

        assertEquals(
            EvidenceSourceTarget(PROJECT_ID, FILE_ID),
            evidenceSourceTarget(listOf(project), FILE_ID, emptySet(), null),
        )
        assertEquals(
            EvidenceSourceTarget(PROJECT_ID, FILE_ID),
            evidenceSourceTarget(listOf(project), "symbol:fixture:consumer", emptySet(), null),
        )
        assertEquals(
            EvidenceSourceTarget(PROJECT_ID, FILE_ID),
            evidenceSourceTarget(
                projects = listOf(project),
                selectedNodeId = null,
                selectedRelationshipIds = setOf("relationship:fixture:constructor"),
                preferredRelationshipId = "relationship:fixture:constructor",
            ),
        )
    }

    @Test
    fun resolvesAnExternalGraphNodeToTheProjectThatOwnsItsSourceSet() {
        val app = DocxWebFixture.project
        val library = DocxWebFixture.libraryProject
        val graphWithExternalLibraryNode =
            app.copy(
                sourceSets = (app.sourceSets + library.sourceSets).sortedBy(SourceSetSnapshot::id),
                files =
                    (app.files + library.files.map { file -> file.copy(content = null) })
                        .sortedBy(SourceFileSnapshot::id),
                symbols = (app.symbols + library.symbols).sortedBy(SymbolSnapshot::id),
            )

        assertEquals(
            EvidenceSourceTarget(
                projectId = library.projectId,
                fileId = library.files.single().id,
            ),
            evidenceSourceTarget(
                projects = listOf(graphWithExternalLibraryNode),
                selectedNodeId = library.symbols.single().id,
                selectedRelationshipIds = emptySet(),
                preferredRelationshipId = null,
            ),
        )
    }

    private companion object {
        const val PROJECT_ID: String = "project:fixture:app"
        const val FILE_ID: String = "file:fixture:consumer"
        const val HASH_LENGTH: Int = 64
    }
}
