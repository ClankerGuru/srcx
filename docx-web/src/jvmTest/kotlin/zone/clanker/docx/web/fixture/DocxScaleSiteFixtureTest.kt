package zone.clanker.docx.web.fixture

import zone.clanker.report.model.AtlasFrame
import zone.clanker.report.model.WorkspaceSiteJson
import java.nio.file.Files
import java.util.Properties
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DocxScaleSiteFixtureTest {
    @Test
    fun exposesTheDeterministicTenThousandSymbolDimensions() {
        val dimensions = DocxScaleSiteFixture.tenThousandSymbols

        assertEquals(2, dimensions.buildCount)
        assertEquals(10, dimensions.projectCount)
        assertEquals(1_000, dimensions.fileCount)
        assertEquals(10_000, dimensions.symbolCount)
        assertEquals(10_000, dimensions.relationshipCount)
        assertEquals(200_000L, dimensions.logicalLineCount)
    }

    @Test
    fun exposesTheSkewedTwentyFiveThousandSymbolComposite() {
        val dimensions = DocxScaleSiteFixture.twentyFiveThousandSymbols

        assertEquals(5, dimensions.buildCount)
        assertEquals(25, dimensions.projectCount)
        assertEquals(2_500, dimensions.fileCount)
        assertEquals(25_000, dimensions.symbolCount)
        assertEquals(25_024, dimensions.relationshipCount)
        assertEquals(500_000L, dimensions.logicalLineCount)
        assertEquals(5, dimensions.projectFileCounts.count { it > dimensions.filesPerProject })
        assertEquals(500, dimensions.projectFileCounts.max())
    }

    @Test
    fun exposesTheCompactRepositoryTopologyProfileWithoutMaterializingTenMillionLines() {
        val dimensions = DocxScaleSiteFixture.repositoryTopology

        assertEquals(80, dimensions.buildCount)
        assertEquals(2_000, dimensions.projectCount)
        assertEquals(4_000, dimensions.fileCount)
        assertEquals(8_000, dimensions.symbolCount)
        assertEquals(9_999, dimensions.relationshipCount)
        assertEquals(10_000_000L, dimensions.logicalLineCount)
        assertEquals(32_000L, dimensions.materializedLineCount)
        assertTrue(dimensions.materializedLineCount < dimensions.logicalLineCount)

        val overview = DocxScaleCatalog(dimensions).atlasOverview()
        assertEquals(4_000, overview.totalNodeCount)
        assertEquals(AtlasFrame.MAX_VISIBLE_NODES, overview.nodes.size)
    }

    @Test
    fun writesTypedShardsWithoutAMonolithicWorkspaceSnapshot() {
        val destination = Files.createTempDirectory("docx-scale-fixture-")
        val dimensions =
            DocxScaleDimensions(
                buildCount = 1,
                projectsPerBuild = 1,
                filesPerProject = 2,
                symbolsPerFile = 2,
                linesPerFile = 8,
            )

        try {
            DocxScaleSiteFixture.generate(destination, dimensions)

            val manifest =
                WorkspaceSiteJson.decodeManifest(destination.resolve(DocxScaleSiteFixture.MANIFEST_PATH).readText())
            val workspace = WorkspaceSiteJson.decodeWorkspace(destination.resolve(manifest.workspaceFile).readText())
            val dashboard = WorkspaceSiteJson.decodeDashboard(destination.resolve(manifest.dashboardFile).readText())
            val overview =
                WorkspaceSiteJson.decodeAtlasFrame(
                    destination.resolve(requireNotNull(manifest.atlasOverviewFile)).readText(),
                )
            val project =
                WorkspaceSiteJson.decodeProject(destination.resolve(manifest.projectShards.single().file).readText())
            val profile =
                Properties().apply {
                    destination.resolve(DocxScaleSiteFixture.PROFILE_PATH).toFile().inputStream().use { input ->
                        load(input)
                    }
                }

            assertEquals(1, workspace.builds.size)
            assertEquals(1, workspace.projects.size)
            assertEquals(4, dashboard.symbolCount)
            assertEquals(2, project.files.size)
            assertEquals(4, project.symbols.size)
            assertEquals(4, project.references.size)
            assertEquals(4, project.relationships.size)
            assertEquals(1, project.cycles.size)
            assertEquals(2, overview.nodes.size)
            assertEquals(1, overview.edges.size)
            assertEquals("16", profile.getProperty("logicalLines"))
            assertEquals("16", profile.getProperty("materializedLines"))
            assertTrue(project.files.all { file -> file.content?.count { character -> character == '\n' } == 8 })
            assertFalse(Files.exists(destination.resolve("workspace-report.json")))
        } finally {
            assertTrue(destination.toFile().deleteRecursively())
        }
    }

    @Test
    fun writesIncludedBuildProjectsWithCrossBuildDependencyClosure() {
        val destination = Files.createTempDirectory("docx-scale-composite-")
        val dimensions =
            DocxScaleDimensions(
                profileName = "dependency-test",
                workspaceName = "Dependency Test Workspace",
                buildCount = 2,
                projectsPerBuild = 2,
                filesPerProject = 2,
                symbolsPerFile = 2,
                linesPerFile = 8,
                includeProjectDependencies = true,
            )

        try {
            DocxScaleSiteFixture.generate(destination, dimensions)

            val manifest =
                WorkspaceSiteJson.decodeManifest(destination.resolve(DocxScaleSiteFixture.MANIFEST_PATH).readText())
            val workspace = WorkspaceSiteJson.decodeWorkspace(destination.resolve(manifest.workspaceFile).readText())
            val dashboard = WorkspaceSiteJson.decodeDashboard(destination.resolve(manifest.dashboardFile).readText())
            val overview =
                WorkspaceSiteJson.decodeAtlasFrame(
                    destination.resolve(requireNotNull(manifest.atlasOverviewFile)).readText(),
                )
            val includedProjectReference =
                manifest.projectShards.single { reference -> reference.projectId.endsWith(":001:000") }
            val includedProject =
                WorkspaceSiteJson.decodeProject(destination.resolve(includedProjectReference.file).readText())

            assertEquals(2, workspace.builds.size)
            assertEquals(4, workspace.projects.size)
            assertEquals(1, workspace.buildEdges.size)
            assertEquals(16, dashboard.symbolCount)
            assertEquals(3, dashboard.dependencyCount)
            assertEquals(8, overview.nodes.size)
            assertEquals(3, overview.edges.size)
            assertTrue(overview.edges.any { it.crossBuild })
            assertEquals(3, includedProject.files.size)
            assertEquals(5, includedProject.symbols.size)
            assertEquals(5, includedProject.references.size)
            assertEquals(5, includedProject.relationships.size)
            assertEquals(4, includedProject.ownedSymbolIds.size)
            assertTrue(
                includedProject.relationships.any { relationship ->
                    relationship.sourceSymbolId in includedProject.ownedSymbolIds &&
                        relationship.targetSymbolId !in includedProject.ownedSymbolIds
                },
            )
        } finally {
            assertTrue(destination.toFile().deleteRecursively())
        }
    }
}
