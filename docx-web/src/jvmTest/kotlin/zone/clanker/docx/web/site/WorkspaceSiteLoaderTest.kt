package zone.clanker.docx.web.site

import kotlinx.coroutines.test.runTest
import zone.clanker.docx.web.fixture.DocxWebFixture
import zone.clanker.report.model.FindingSeverity
import zone.clanker.report.model.ProjectGraphShard
import zone.clanker.report.model.ReferenceKind
import zone.clanker.report.model.RelationshipKind
import zone.clanker.report.model.WorkspaceDashboardCoverage
import zone.clanker.report.model.WorkspaceSiteJson
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WorkspaceSiteLoaderTest {
    @Test
    fun loadsTheCatalogWithoutWaitingForAProjectShard() =
        runTest {
            val requestedPaths = mutableListOf<String>()
            val source =
                WorkspaceSiteTextSource { path ->
                    requestedPaths += path
                    DocxWebFixture.source.read(path)
                }
            val loader = WorkspaceSiteLoader(source)

            val site = loader.loadWorkspace()
            assertWorkspaceLoaded(site, requestedPaths)

            val project = loader.loadProject(site, "project:fixture:app")
            assertAppProjectLoaded(project, requestedPaths)

            val libraryProject = loader.loadProject(site, "project:fixture:library")
            assertLibraryProjectLoaded(libraryProject, requestedPaths)
        }

    @Test
    fun loadsEverySelectedBuildProjectInOrderWhileReadingOnlyCacheMisses() =
        runTest {
            val requestedPaths = mutableListOf<String>()
            val source =
                WorkspaceSiteTextSource { path ->
                    requestedPaths += path
                    DocxWebFixture.source.read(path)
                }
            val loader = WorkspaceSiteLoader(source)
            val loaded = loader.loadWorkspace()
            val sharedBuildSummary =
                loaded.summary.copy(
                    projects = loaded.summary.projects.map { project -> project.copy(buildId = "build:fixture") },
                )
            val site = loaded.copy(summary = sharedBuildSummary)
            requestedPaths.clear()

            val projects =
                loader.loadBuildProjects(
                    site = site,
                    buildId = "build:fixture",
                    cachedProjects = mapOf(DocxWebFixture.project.projectId to DocxWebFixture.project),
                )

            assertEquals(listOf("project:fixture:app", "project:fixture:library"), projects.map { it.projectId })
            assertEquals(
                listOf(
                    DocxWebFixture.manifest.projectShards
                        .last()
                        .file,
                ),
                requestedPaths,
            )
            assertFailsWith<IllegalArgumentException> {
                loader.loadBuildProjects(
                    site = site,
                    buildId = "build:fixture",
                    cachedProjects = mapOf("project:fixture:app" to DocxWebFixture.libraryProject),
                )
            }
        }

    @Test
    fun rejectsAWorkspaceSourceSetCatalogThatDisagreesWithTheDashboard() =
        runTest {
            val corruptedSummary =
                DocxWebFixture.summary.copy(
                    sourceSets =
                        DocxWebFixture.summary.sourceSets.mapIndexed { index, sourceSet ->
                            if (index == 0) sourceSet.copy(fileCount = sourceSet.fileCount + 1) else sourceSet
                        },
                )
            val source =
                WorkspaceSiteTextSource { path ->
                    if (path == DocxWebFixture.manifest.workspaceFile) {
                        WorkspaceSiteJson.encodeWorkspace(corruptedSummary)
                    } else {
                        DocxWebFixture.source.read(path)
                    }
                }

            assertFailsWith<IllegalArgumentException> {
                WorkspaceSiteLoader(source).loadWorkspace()
            }
        }

    private fun assertWorkspaceLoaded(
        site: LoadedWorkspaceSite,
        requestedPaths: List<String>,
    ) {
        assertEquals(
            listOf(
                WorkspaceSiteLoader.MANIFEST_PATH,
                DocxWebFixture.manifest.workspaceFile,
                DocxWebFixture.manifest.dashboardFile,
                requireNotNull(DocxWebFixture.manifest.atlasOverviewFile),
                requireNotNull(DocxWebFixture.manifest.atlasOverviewsFile),
            ),
            requestedPaths,
        )
        assertEquals("Fixture Workspace", site.summary.workspace.name)
        assertEquals(
            listOf("source-set:fixture:library:main", "source-set:fixture:main"),
            site.summary.sourceSets.map { sourceSet -> sourceSet.id },
        )
        assertEquals(listOf(1, 2), site.summary.sourceSets.map { sourceSet -> sourceSet.fileCount })
        assertEquals(DocxWebFixture.dashboard, site.dashboard)
        assertEquals(DocxWebFixture.atlasOverview, site.atlasOverview)
        assertEquals(DocxWebFixture.atlasOverviews, site.atlasOverviews)
        assertEquals(3, requireNotNull(site.atlasOverview).nodes.size)
        val dashboard = requireNotNull(site.dashboard)
        assertEquals(2, dashboard.projectCount)
        assertEquals(3, dashboard.symbolCount)
        assertEquals(1, dashboard.dependencyCount)
        assertEquals(
            FindingSeverity.WARNING,
            dashboard.findings
                .single()
                .finding
                .severity,
        )
        assertEquals(
            WorkspaceDashboardCoverage(
                projectsWithSymbols = 2,
                sourceSetCount = 2,
                packageCount = 2,
                projectAnalysisCount = 0,
            ),
            dashboard.coverage,
        )
    }

    private fun assertAppProjectLoaded(
        project: ProjectGraphShard,
        requestedPaths: List<String>,
    ) {
        assertLastRequestedProject("project:fixture:app", requestedPaths)
        assertEquals(listOf("Consumer", "Target"), project.symbols.map { symbol -> symbol.name })
        assertEquals(ReferenceKind.CONSTRUCTOR, project.references.single().kind)
        assertEquals(RelationshipKind.CONSTRUCTOR, project.relationships.single().kind)
        assertTrue(
            project.references
                .single()
                .context
                .contains("Target()"),
        )
        assertTrue(
            project.findings
                .single()
                .message
                .contains("construction relationship"),
        )
    }

    private fun assertLibraryProjectLoaded(
        project: ProjectGraphShard,
        requestedPaths: List<String>,
    ) {
        assertLastRequestedProject("project:fixture:library", requestedPaths)
        assertEquals(listOf("LibraryPort"), project.symbols.map { symbol -> symbol.name })
        assertTrue(project.relationships.isEmpty())
        assertTrue(project.findings.isEmpty())
    }

    private fun assertLastRequestedProject(
        projectId: String,
        requestedPaths: List<String>,
    ) {
        val expectedPath =
            DocxWebFixture.manifest.projectShards
                .single { reference -> reference.projectId == projectId }
                .file
        assertEquals(expectedPath, requestedPaths.last())
    }

    @Test
    fun generatedDistributionFixtureDecodesThroughTheSharedSerializer() {
        val fixtureDirectory = File(requireNotNull(System.getProperty("docx.fixture.directory")))
        val manifestFile = fixtureDirectory.resolve(WorkspaceSiteLoader.MANIFEST_PATH)
        val manifest = WorkspaceSiteJson.decodeManifest(manifestFile.readText())
        val summary = WorkspaceSiteJson.decodeWorkspace(fixtureDirectory.resolve(manifest.workspaceFile).readText())
        val dashboard =
            WorkspaceSiteJson.decodeDashboard(
                fixtureDirectory.resolve(manifest.dashboardFile).readText(),
            )
        val atlasOverview =
            WorkspaceSiteJson.decodeAtlasFrame(
                fixtureDirectory.resolve(requireNotNull(manifest.atlasOverviewFile)).readText(),
            )
        val projects =
            manifest.projectShards.map { projectReference ->
                WorkspaceSiteJson.decodeProject(fixtureDirectory.resolve(projectReference.file).readText())
            }

        assertEquals(summary.projectShards, manifest.projectShards)
        assertEquals(DocxWebFixture.dashboard, dashboard)
        assertEquals(DocxWebFixture.atlasOverview, atlasOverview)
        assertEquals(DocxWebFixture.projects, projects)
        assertEquals(DocxWebFixture.EXPECTED_SYMBOL_COUNT, projects.sumOf { project -> project.symbols.size })
        assertEquals(1, projects.sumOf { project -> project.relationships.size })
        assertEquals(1, projects.sumOf { project -> project.findings.size })
    }
}
