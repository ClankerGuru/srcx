package zone.clanker.docx.web.fixture

import zone.clanker.docx.web.site.WorkspaceSiteLoader
import zone.clanker.report.model.WorkspaceSiteJson
import java.io.File

internal fun writeDocxWebFixture(arguments: Array<String>) {
    require(arguments.size == 1) { "Fixture generation requires one output directory" }
    val root = File(arguments.single())
    val files = fixtureFiles(root)
    require(files.shardDirectory.mkdirs() || files.shardDirectory.isDirectory) {
        "Unable to create fixture data directory"
    }
    writeFixtureFiles(root, files)
    verifyFixtureFiles(root, files)
}

private fun fixtureFiles(root: File): DocxWebFixtureFiles =
    DocxWebFixtureFiles(
        shardDirectory = root.resolve("data/shards"),
        manifest = root.resolve(WorkspaceSiteLoader.MANIFEST_PATH),
        workspace = root.resolve(DocxWebFixture.manifest.workspaceFile),
        dashboard = root.resolve(DocxWebFixture.manifest.dashboardFile),
        atlasOverview = root.resolve(requireNotNull(DocxWebFixture.manifest.atlasOverviewFile)),
        atlasOverviews = root.resolve(requireNotNull(DocxWebFixture.manifest.atlasOverviewsFile)),
    )

private fun writeFixtureFiles(
    root: File,
    files: DocxWebFixtureFiles,
) {
    files.manifest.writeText(WorkspaceSiteJson.encodeManifest(DocxWebFixture.manifest))
    files.workspace.writeText(WorkspaceSiteJson.encodeWorkspace(DocxWebFixture.summary))
    files.dashboard.writeText(WorkspaceSiteJson.encodeDashboard(DocxWebFixture.dashboard))
    files.atlasOverview.writeText(WorkspaceSiteJson.encodeAtlasFrame(DocxWebFixture.atlasOverview))
    files.atlasOverviews.writeText(WorkspaceSiteJson.encodeAtlasOverviews(DocxWebFixture.atlasOverviews))
    DocxWebFixture.manifest.projectShards.forEach { reference ->
        val project = DocxWebFixture.projects.single { candidate -> candidate.projectId == reference.projectId }
        root.resolve(reference.file).writeText(WorkspaceSiteJson.encodeProject(project))
    }
    DocxWebFixture.search.content.forEach { (path, content) ->
        root.resolve(path).apply { parentFile.mkdirs() }.writeText(content)
    }
}

private fun verifyFixtureFiles(
    root: File,
    files: DocxWebFixtureFiles,
) {
    val decodedManifest = WorkspaceSiteJson.decodeManifest(files.manifest.readText())
    val decodedWorkspace = WorkspaceSiteJson.decodeWorkspace(files.workspace.readText())
    val decodedDashboard = WorkspaceSiteJson.decodeDashboard(root.resolve(decodedManifest.dashboardFile).readText())
    val decodedAtlasOverview =
        WorkspaceSiteJson.decodeAtlasFrame(root.resolve(requireNotNull(decodedManifest.atlasOverviewFile)).readText())
    val decodedAtlasOverviews =
        WorkspaceSiteJson.decodeAtlasOverviews(
            root.resolve(requireNotNull(decodedManifest.atlasOverviewsFile)).readText(),
        )
    val decodedProjects =
        decodedManifest.projectShards.map { reference ->
            WorkspaceSiteJson.decodeProject(root.resolve(reference.file).readText())
        }
    require(decodedManifest.projectShards == decodedWorkspace.projectShards)
    require(decodedDashboard == DocxWebFixture.dashboard)
    require(decodedAtlasOverview == DocxWebFixture.atlasOverview)
    require(decodedAtlasOverviews == DocxWebFixture.atlasOverviews)
    require(decodedProjects == DocxWebFixture.projects)
    require(decodedProjects.sumOf { project -> project.symbols.size } == DocxWebFixture.EXPECTED_SYMBOL_COUNT)
    require(decodedProjects.sumOf { project -> project.relationships.size } == 1)
    require(decodedProjects.sumOf { project -> project.findings.size } == 1)
    val searchCatalog = requireNotNull(decodedManifest.searchCatalog)
    require(root.resolve(searchCatalog.file).readText() == DocxWebFixture.search.content.getValue(searchCatalog.file))
}

private data class DocxWebFixtureFiles(
    val shardDirectory: File,
    val manifest: File,
    val workspace: File,
    val dashboard: File,
    val atlasOverview: File,
    val atlasOverviews: File,
)
