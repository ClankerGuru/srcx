package zone.clanker.docx.web.fixture

import zone.clanker.docx.web.site.WorkspaceSiteLoader
import zone.clanker.docx.web.site.WorkspaceSiteTextSource
import zone.clanker.report.model.BuildKind
import zone.clanker.report.model.BuildSnapshot
import zone.clanker.report.model.ProjectGraphShard
import zone.clanker.report.model.ProjectShardReference
import zone.clanker.report.model.ProjectSnapshot
import zone.clanker.report.model.WorkspaceSiteJson
import zone.clanker.report.model.WorkspaceSiteManifest
import zone.clanker.report.model.WorkspaceSnapshot
import zone.clanker.report.model.WorkspaceSourceSetSummary
import zone.clanker.report.model.WorkspaceSummaryShard

/** Tiny typed site used to test the production loader and to populate the standalone distribution. */
data object DocxWebFixture {
    internal const val EXPECTED_SYMBOL_COUNT = 3

    internal val search = fixtureSearch()

    private val appProjectReference =
        ProjectShardReference(
            projectId = "project:fixture:app",
            file = "data/shards/project-fixture.json",
        )
    private val libraryProjectReference =
        ProjectShardReference(
            projectId = "project:fixture:library",
            file = "data/shards/project-library.json",
        )
    private val projectReferences = listOf(appProjectReference, libraryProjectReference)

    val manifest =
        WorkspaceSiteManifest(
            generationId = "fixture-generation",
            snapshotSchemaVersion = WorkspaceSnapshot.CURRENT_SCHEMA_VERSION,
            workspaceFile = "data/workspace.json",
            dashboardFile = "data/dashboard.json",
            atlasOverviewFile = "data/atlas-overview.json",
            atlasOverviewsFile = "data/atlas-overviews.json",
            projectShards = projectReferences,
            searchCatalog = search.catalogReference,
            assetFiles = emptyList(),
        )

    val summary =
        WorkspaceSummaryShard(
            workspace = fixtureWorkspaceIdentity,
            builds =
                listOf(
                    BuildSnapshot("build:fixture", "fixture", BuildKind.ROOT, "."),
                    BuildSnapshot(
                        id = "build:fixture:library",
                        name = "fixture-library",
                        kind = BuildKind.INCLUDED,
                        relativePath = "fixture-library",
                    ),
                ),
            projects =
                listOf(
                    ProjectSnapshot(
                        id = "project:fixture:app",
                        buildId = "build:fixture",
                        path = ":app",
                        buildFile = "build.gradle.kts",
                        sourceDirectories = listOf("src/main/kotlin"),
                    ),
                    ProjectSnapshot(
                        id = "project:fixture:library",
                        buildId = "build:fixture:library",
                        path = ":library",
                        buildFile = "build.gradle.kts",
                        sourceDirectories = listOf("src/main/kotlin"),
                    ),
                ),
            sourceSets =
                listOf(
                    WorkspaceSourceSetSummary(
                        id = "source-set:fixture:library:main",
                        projectId = "project:fixture:library",
                        name = "main",
                        fileCount = 1,
                    ),
                    WorkspaceSourceSetSummary(
                        id = "source-set:fixture:main",
                        projectId = "project:fixture:app",
                        name = "main",
                        fileCount = 2,
                    ),
                ),
            projectShards = projectReferences,
        )

    val project: ProjectGraphShard = fixtureAppProject()
    val libraryProject: ProjectGraphShard = fixtureLibraryProject()
    val projects = listOf(project, libraryProject)
    val atlasOverview = fixtureAtlasOverview()
    val atlasOverviews = fixtureAtlasOverviews(summary, projects, atlasOverview)
    val dashboard = fixtureDashboard(summary, projects, project, libraryProject)

    val source =
        WorkspaceSiteTextSource { path ->
            when (path) {
                WorkspaceSiteLoader.MANIFEST_PATH -> WorkspaceSiteJson.encodeManifest(manifest)
                manifest.workspaceFile -> WorkspaceSiteJson.encodeWorkspace(summary)
                manifest.dashboardFile -> WorkspaceSiteJson.encodeDashboard(dashboard)
                manifest.atlasOverviewFile -> WorkspaceSiteJson.encodeAtlasFrame(atlasOverview)
                manifest.atlasOverviewsFile -> WorkspaceSiteJson.encodeAtlasOverviews(atlasOverviews)
                appProjectReference.file -> WorkspaceSiteJson.encodeProject(project)
                libraryProjectReference.file -> WorkspaceSiteJson.encodeProject(libraryProject)
                in search.content -> search.content.getValue(path)
                else -> error("Unknown fixture path: $path")
            }
        }
}

fun main(arguments: Array<String>) {
    writeDocxWebFixture(arguments)
}
