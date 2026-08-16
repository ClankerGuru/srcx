package zone.clanker.docx.web.site

import zone.clanker.report.model.AtlasFrame
import zone.clanker.report.model.AtlasLens
import zone.clanker.report.model.AtlasScopeKind
import zone.clanker.report.model.ProjectGraphShard
import zone.clanker.report.model.SourceContentReference
import zone.clanker.report.model.WorkspaceAtlasOverviewShard
import zone.clanker.report.model.WorkspaceDashboardShard
import zone.clanker.report.model.WorkspaceSearchCatalog
import zone.clanker.report.model.WorkspaceSearchResultGroup
import zone.clanker.report.model.WorkspaceSearchResults
import zone.clanker.report.model.WorkspaceSiteJson
import zone.clanker.report.model.WorkspaceSiteManifest
import zone.clanker.report.model.WorkspaceSummaryShard

/** Report-root-relative text source used by both HTTP delivery and deterministic fixtures. */
fun interface WorkspaceSiteTextSource {
    suspend fun read(path: String): String
}

data class LoadedWorkspaceSite(
    val manifest: WorkspaceSiteManifest,
    val summary: WorkspaceSummaryShard,
    val dashboard: WorkspaceDashboardShard,
    val atlasOverview: AtlasFrame? = null,
    val atlasOverviews: WorkspaceAtlasOverviewShard? = null,
)

fun LoadedWorkspaceSite.selectAtlasOverview(lens: AtlasLens): LoadedWorkspaceSite {
    val selected = atlasOverviews?.frame(lens) ?: atlasOverview?.takeIf { frame -> frame.lens == lens }
    return copy(atlasOverview = selected)
}

class WorkspaceSiteLoader(
    private val source: WorkspaceSiteTextSource,
) {
    private val staticSearch = WorkspaceStaticSearchLoader(source)
    private val staticEvidence = WorkspaceStaticEvidenceLoader(source)

    internal fun staticEvidenceGateway(site: LoadedWorkspaceSite): WorkspaceEvidenceGateway =
        StaticWorkspaceEvidenceGateway(site, staticEvidence)

    suspend fun loadWorkspace(): LoadedWorkspaceSite {
        val manifest = WorkspaceSiteJson.decodeManifest(source.read(MANIFEST_PATH))
        val summary = WorkspaceSiteJson.decodeWorkspace(source.read(manifest.workspaceFile))
        require(manifest.snapshotSchemaVersion == summary.schemaVersion) {
            "Manifest and workspace-summary schema versions must agree"
        }
        require(manifest.projectShards == summary.projectShards) {
            "Manifest and workspace summary must reference the same project shards"
        }
        val dashboard =
            WorkspaceSiteJson.decodeDashboard(source.read(manifest.dashboardFile)).also { shard ->
                require(shard.workspaceId == summary.workspace.id) {
                    "Manifest workspace dashboard must belong to the workspace summary"
                }
                require(shard.builds.map { build -> build.buildId } == summary.builds.map { build -> build.id }) {
                    "Workspace dashboard and summary must contain the same build catalog"
                }
                require(
                    shard.projects.associate { project -> project.projectId to project.buildId } ==
                        summary.projects.associate { project -> project.id to project.buildId },
                ) {
                    "Workspace dashboard and summary must contain the same project ownership catalog"
                }
                validateSourceSetCatalog(summary, shard)
            }
        val atlasOverview =
            manifest.atlasOverviewFile?.let { file ->
                WorkspaceSiteJson.decodeAtlasFrame(source.read(file)).also { frame ->
                    require(frame.scope.kind == AtlasScopeKind.WORKSPACE) {
                        "Manifest Atlas overview must use workspace scope"
                    }
                    require(frame.scope.workspaceId == summary.workspace.id) {
                        "Manifest Atlas overview must belong to the workspace summary"
                    }
                }
            }
        val atlasOverviews =
            manifest.atlasOverviewsFile?.let { file ->
                WorkspaceSiteJson.decodeAtlasOverviews(source.read(file)).also { overviews ->
                    require(overviews.frames.all { frame -> frame.scope.workspaceId == summary.workspace.id }) {
                        "Manifest Atlas lens overviews must belong to the workspace summary"
                    }
                    require(atlasOverview == null || overviews.frame(AtlasLens.FILES) == atlasOverview) {
                        "Manifest Atlas file overview must agree with its lens bundle"
                    }
                }
            }
        return LoadedWorkspaceSite(
            manifest = manifest,
            summary = summary,
            dashboard = dashboard,
            atlasOverview = atlasOverviews?.frame(AtlasLens.FILES) ?: atlasOverview,
            atlasOverviews = atlasOverviews,
        )
    }

    suspend fun loadProject(
        site: LoadedWorkspaceSite,
        projectId: String,
    ): ProjectGraphShard = loadProjectResource(site, projectId).shard

    internal suspend fun loadProjectResource(
        site: LoadedWorkspaceSite,
        projectId: String,
    ): LoadedProjectResource {
        require(site.summary.projects.any { project -> project.id == projectId }) {
            "Unknown project selected: $projectId"
        }
        val reference = site.manifest.projectShards.single { shard -> shard.projectId == projectId }
        val content = source.read(reference.file)
        val shard =
            WorkspaceSiteJson.decodeProject(content).also { shard ->
                require(shard.projectId == projectId) { "Project shard identity does not match its manifest entry" }
                require(shard.sourceContents == reference.sourceContents) {
                    "Project source contents do not match their manifest entry"
                }
            }
        return LoadedProjectResource(
            shard = shard,
            encodedByteSize = content.utf8EncodedByteSize(),
        )
    }

    suspend fun loadSourceContent(
        site: LoadedWorkspaceSite,
        project: ProjectGraphShard,
        fileId: String,
    ): LoadedSourceContent? {
        require(site.summary.projects.any { candidate -> candidate.id == project.projectId }) {
            "Unknown source-content project: ${project.projectId}"
        }
        val file =
            project.files.singleOrNull { candidate -> candidate.id == fileId }
                ?: throw IllegalArgumentException("Unknown source-content file: $fileId")
        file.content?.let { embedded ->
            return LoadedSourceContent(
                fileId = fileId,
                content = embedded,
                encodedByteSize = embedded.utf8EncodedByteSize(),
            )
        }
        val reference = project.sourceContents.singleOrNull { source -> source.fileId == fileId } ?: return null
        val manifestProject = site.manifest.projectShards.single { shard -> shard.projectId == project.projectId }
        require(reference in manifestProject.sourceContents) {
            "Source-content reference does not match its manifest project"
        }
        return loadSourceReference(reference)
    }

    suspend fun loadSourceContent(
        site: LoadedWorkspaceSite,
        projectId: String,
        fileId: String,
    ): LoadedSourceContent? {
        require(site.summary.projects.any { candidate -> candidate.id == projectId }) {
            "Unknown source-content project: $projectId"
        }
        val manifestProject = site.manifest.projectShards.single { shard -> shard.projectId == projectId }
        val reference = manifestProject.sourceContents.singleOrNull { source -> source.fileId == fileId } ?: return null
        return loadSourceReference(reference)
    }

    private suspend fun loadSourceReference(reference: SourceContentReference): LoadedSourceContent {
        val content = source.read(reference.file)
        require(content.utf8EncodedByteSize() == reference.encodedByteSize) {
            "Loaded source-content byte size does not match its reference"
        }
        return LoadedSourceContent(
            fileId = reference.fileId,
            content = content,
            encodedByteSize = reference.encodedByteSize,
            contentHash = reference.contentHash,
        )
    }

    suspend fun loadSearchCatalog(site: LoadedWorkspaceSite): LoadedWorkspaceSearchCatalog? =
        staticSearch.loadCatalog(site)

    suspend fun search(
        site: LoadedWorkspaceSite,
        catalog: WorkspaceSearchCatalog,
        query: String,
        resultLimitPerGroup: Int = DEFAULT_SEARCH_RESULTS_PER_GROUP,
    ): WorkspaceSearchResults = staticSearch.query(site, catalog, query, resultLimitPerGroup)

    /** Loads only cache misses while returning every project shard owned by one selected build. */
    suspend fun loadBuildProjects(
        site: LoadedWorkspaceSite,
        buildId: String,
        cachedProjects: Map<String, ProjectGraphShard> = emptyMap(),
    ): List<ProjectGraphShard> {
        require(site.summary.builds.any { build -> build.id == buildId }) { "Unknown build selected: $buildId" }
        return site.summary.projects
            .filter { project -> project.buildId == buildId }
            .map { project ->
                cachedProjects[project.id]?.also { cached ->
                    require(cached.projectId == project.id) { "Cached project shard identity does not match its key" }
                } ?: loadProject(site, project.id)
            }
    }

    companion object {
        const val MANIFEST_PATH: String = "data/manifest.json"
        const val DEFAULT_SEARCH_RESULTS_PER_GROUP: Int = 10

        init {
            require(DEFAULT_SEARCH_RESULTS_PER_GROUP <= WorkspaceSearchResultGroup.MAX_RESULTS)
        }
    }
}

private fun validateSourceSetCatalog(
    summary: WorkspaceSummaryShard,
    dashboard: WorkspaceDashboardShard,
) {
    val sourceSetsByProject = summary.sourceSets.groupBy { sourceSet -> sourceSet.projectId }
    dashboard.projects.forEach { project ->
        val sourceSets = sourceSetsByProject[project.projectId].orEmpty()
        require(sourceSets.size == project.sourceSetCount) {
            "Workspace source-set catalog must match the dashboard count for ${project.projectId}"
        }
        require(sourceSets.sumOf { sourceSet -> sourceSet.fileCount } == project.fileCount) {
            "Workspace source-set file counts must match the dashboard count for ${project.projectId}"
        }
        require(sourceSets.map { sourceSet -> sourceSet.name }.distinct().sorted() == project.sourceSetNames) {
            "Workspace source-set catalog must match the dashboard names for ${project.projectId}"
        }
    }
}
