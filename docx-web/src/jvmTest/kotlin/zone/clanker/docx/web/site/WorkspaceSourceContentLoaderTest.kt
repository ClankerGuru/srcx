package zone.clanker.docx.web.site

import kotlinx.coroutines.test.runTest
import zone.clanker.docx.web.fixture.DocxWebFixture
import zone.clanker.report.model.ProjectShardReference
import zone.clanker.report.model.SourceContentReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WorkspaceSourceContentLoaderTest {
    @Test
    fun readsOnlyTheRequestedContentAddressedBody() =
        runTest {
            val content = "package fixture\n\nclass Consumer\n"
            val sourceReference =
                SourceContentReference(
                    fileId = "file:fixture:consumer",
                    contentHash = "a".repeat(HASH_LENGTH),
                    encodedByteSize = content.encodeToByteArray().size.toLong(),
                )
            val site = siteWith(sourceReference)
            val requestedPaths = mutableListOf<String>()
            val loader =
                WorkspaceSiteLoader { path ->
                    requestedPaths += path
                    require(path == sourceReference.file)
                    content
                }

            val loaded =
                loader.loadSourceContent(
                    site,
                    DocxWebFixture.project.projectId,
                    sourceReference.fileId,
                )

            assertEquals(listOf(sourceReference.file), requestedPaths)
            assertEquals(content, loaded?.content)
            assertEquals(sourceReference.contentHash, loaded?.contentHash)
            assertEquals(sourceReference.encodedByteSize, loaded?.encodedByteSize)
        }

    @Test
    fun usesLegacyEmbeddedContentWithoutReadingAnotherFile() =
        runTest {
            val loader = WorkspaceSiteLoader { path -> error("Unexpected source read: $path") }
            val loaded =
                loader.loadSourceContent(
                    DocxWebFixture.site(),
                    DocxWebFixture.project,
                    "file:fixture:consumer",
                )

            assertEquals(
                DocxWebFixture.project.files
                    .first()
                    .content,
                loaded?.content,
            )
            assertNull(loaded?.contentHash)
        }

    @Test
    fun reportsNoBodyWhenMetadataHasNeitherAReferenceNorLegacyContent() =
        runTest {
            val project =
                DocxWebFixture.project.copy(
                    files = DocxWebFixture.project.files.map { file -> file.copy(content = null) },
                )
            val loader = WorkspaceSiteLoader { path -> error("Unexpected source read: $path") }

            assertNull(
                loader.loadSourceContent(
                    DocxWebFixture.site(),
                    project,
                    "file:fixture:consumer",
                ),
            )
        }

    private fun siteWith(sourceReference: SourceContentReference): LoadedWorkspaceSite {
        val projectReferences =
            DocxWebFixture.manifest.projectShards.map { project ->
                if (project.projectId == DocxWebFixture.project.projectId) {
                    project.copy(sourceContents = listOf(sourceReference))
                } else {
                    project
                }
            }
        return DocxWebFixture.site(projectReferences)
    }

    private fun DocxWebFixture.site(
        projectReferences: List<ProjectShardReference> = manifest.projectShards,
    ): LoadedWorkspaceSite =
        LoadedWorkspaceSite(
            manifest = manifest.copy(projectShards = projectReferences),
            summary = summary.copy(projectShards = projectReferences),
            dashboard = dashboard,
            atlasOverview = atlasOverview,
            atlasOverviews = atlasOverviews,
        )

    private companion object {
        const val HASH_LENGTH: Int = 64
    }
}
