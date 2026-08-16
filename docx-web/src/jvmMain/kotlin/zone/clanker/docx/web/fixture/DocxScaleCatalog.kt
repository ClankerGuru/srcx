package zone.clanker.docx.web.fixture

import zone.clanker.report.model.BuildEdgeSnapshot
import zone.clanker.report.model.BuildKind
import zone.clanker.report.model.BuildSnapshot
import zone.clanker.report.model.ProjectGraphShard
import zone.clanker.report.model.WorkspaceDashboardShard
import zone.clanker.report.model.WorkspaceIdentity
import zone.clanker.report.model.WorkspaceSiteManifest
import zone.clanker.report.model.WorkspaceSnapshot
import zone.clanker.report.model.WorkspaceSourceSetSummary
import zone.clanker.report.model.WorkspaceSummaryShard

internal class DocxScaleCatalog(
    private val dimensions: DocxScaleDimensions,
) {
    private val workspace =
        WorkspaceIdentity(
            id = "workspace:scale:${dimensions.profileName}",
            name = dimensions.workspaceName,
        )
    val projects: List<DocxScaleProject> =
        (0 until dimensions.buildCount).flatMap { buildIndex ->
            (0 until dimensions.projectsPerBuild).map { projectIndex ->
                DocxScaleProject(dimensions, buildIndex, projectIndex)
            }
        }
    private val builds: List<BuildSnapshot> =
        (0 until dimensions.buildCount).map { index ->
            BuildSnapshot(
                id = dimensions.buildId(index),
                name = dimensions.buildName(index),
                kind = if (index == 0) BuildKind.ROOT else BuildKind.INCLUDED,
                relativePath = if (index == 0) "." else "build-${padded(index, BUILD_WIDTH)}",
            )
        }
    private val shardReferences = projects.map(DocxScaleProject::shardReference)

    fun manifest(): WorkspaceSiteManifest =
        WorkspaceSiteManifest(
            generationId = "scale-${dimensions.profileName}-v2",
            snapshotSchemaVersion = WorkspaceSnapshot.CURRENT_SCHEMA_VERSION,
            workspaceFile = DocxScaleSiteFixture.WORKSPACE_PATH,
            dashboardFile = DocxScaleSiteFixture.DASHBOARD_PATH,
            atlasOverviewFile = DocxScaleSiteFixture.ATLAS_PATH,
            projectShards = shardReferences,
            assetFiles = emptyList(),
        )

    fun summary(): WorkspaceSummaryShard =
        WorkspaceSummaryShard(
            workspace = workspace,
            builds = builds,
            projects = projects.map(DocxScaleProject::snapshot),
            sourceSets = sourceSetSummaries(),
            projectShards = shardReferences,
            buildEdges = buildEdges(),
        )

    fun dashboard(): WorkspaceDashboardShard =
        DocxScaleDashboardFactory(dimensions, workspace, builds, projects).dashboard()

    fun atlasOverview() =
        DocxScaleAtlasOverviewFactory(dimensions, workspace, builds, projects).atlasOverview()

    fun projectGraph(project: DocxScaleProject): ProjectGraphShard =
        DocxScaleProjectGraphFactory(project, dependencyOf(project)).graph()

    private fun buildEdges(): List<BuildEdgeSnapshot> =
        (1 until dimensions.buildCount).map { index ->
            BuildEdgeSnapshot(
                id = "build-edge:scale:${dimensions.profileName}:${padded(index, BUILD_WIDTH)}",
                sourceBuildId = dimensions.buildId(index),
                targetBuildId = dimensions.buildId(index - 1),
            )
        }

    private fun sourceSetSummaries(): List<WorkspaceSourceSetSummary> =
        projects
            .flatMap { project ->
                val mainFileCount = dimensions.mainFilesInProject(project.buildIndex, project.projectIndex)
                SOURCE_SET_NAMES.map { name ->
                    WorkspaceSourceSetSummary(
                        id = project.sourceSetId(name),
                        projectId = project.id,
                        name = name,
                        fileCount = if (name == "main") mainFileCount else project.fileCount - mainFileCount,
                    )
                }
            }.sortedBy(WorkspaceSourceSetSummary::id)

    private fun dependencyOf(project: DocxScaleProject): DocxScaleProject? =
        projects.getOrNull(project.ordinal - 1).takeIf { dimensions.includeProjectDependencies }
}
