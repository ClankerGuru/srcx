package zone.clanker.docx.index.performance

import zone.clanker.report.model.AtlasFrame
import zone.clanker.report.model.ProjectGraphShard
import zone.clanker.report.model.ProjectShardReference
import zone.clanker.report.model.WorkspaceGraphFacet
import zone.clanker.report.model.WorkspaceGraphJson
import zone.clanker.report.model.WorkspaceGraphLimits
import zone.clanker.report.model.WorkspaceGraphRequest
import zone.clanker.report.model.WorkspaceGraphSelection
import zone.clanker.report.model.WorkspaceGraphSlice
import zone.clanker.report.model.WorkspaceGraphView
import zone.clanker.report.model.WorkspaceSiteJson
import zone.clanker.report.model.WorkspaceSiteManifest
import zone.clanker.report.model.WorkspaceSummaryShard
import java.lang.reflect.Method
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

internal class StaticAtlasPerformanceProbe(
    private val siteRoot: Path,
) {
    private val manifestPath = siteRoot.resolve(MANIFEST_PATH)
    private val manifest: WorkspaceSiteManifest = decodeManifest(manifestPath)
    private val summaryPath = siteRoot.resolve(manifest.workspaceFile)
    val summary: WorkspaceSummaryShard = decodeSummary(summaryPath)
    val generationId: String = manifest.generationId
    val scaleProfile: ScaleProfileDescriptor = readScaleProfile(siteRoot.resolve(SCALE_PROFILE_PATH))
    private val atlasPath = siteRoot.resolve(requireNotNull(manifest.atlasOverviewFile))
    private val selectedReference = manifest.projectShards.last()
    private val selectedProject = decodeProject(selectedReference)

    val projectRequest: WorkspaceGraphRequest = projectRequest(selectedReference.projectId)
    val defaultWorkspaceRequest: WorkspaceGraphRequest = workspaceRequest(WorkspaceGraphLimits())
    val hardWorkspaceRequest: WorkspaceGraphRequest =
        workspaceRequest(
            HARD_LIMITS,
        )
    val hardProjectRequest: WorkspaceGraphRequest =
        projectRequest(selectedReference.projectId).copy(limits = HARD_LIMITS)

    fun firstUsefulMapComponent(): StaticInitialMap {
        val decodedManifest = decodeManifest(manifestPath)
        val decodedSummary = decodeSummary(siteRoot.resolve(decodedManifest.workspaceFile))
        val decodedAtlas =
            WorkspaceSiteJson.decodeAtlasFrame(
                Files.readString(siteRoot.resolve(requireNotNull(decodedManifest.atlasOverviewFile))),
            )
        require(decodedSummary.workspace.id == decodedAtlas.scope.workspaceId)
        return StaticInitialMap(decodedManifest, decodedSummary, decodedAtlas)
    }

    fun warmProjectSlice(): WorkspaceGraphSlice = project(projectRequest, listOf(selectedProject))

    fun projectDrill(index: Int): WorkspaceGraphSlice {
        val reference = manifest.projectShards[index % manifest.projectShards.size]
        return project(projectRequest(reference.projectId), listOf(decodeProject(reference)))
    }

    fun defaultWorkspaceSlice(): WorkspaceGraphSlice = project(defaultWorkspaceRequest)

    fun hardWorkspaceSlice(): WorkspaceGraphSlice = project(hardWorkspaceRequest)

    fun payloadSizes(projectSlice: WorkspaceGraphSlice): List<PayloadSize> {
        val encodedSlice = WorkspaceGraphJson.encodeSlice(projectSlice)
        return listOf(
            payloadSize("manifest", manifestPath),
            payloadSize("workspace-summary", summaryPath),
            payloadSize("atlas-overview", atlasPath),
            payloadSize("selected-project-shard", siteRoot.resolve(selectedReference.file)),
            PayloadSize(
                name = "bounded-graph-slice",
                encodedBytes = encodedSlice.encodeToByteArray().size.toLong(),
                decodedUtf16BytesEstimate = encodedSlice.length * UTF16_BYTES_PER_CHARACTER,
            ),
        )
    }

    private fun project(
        request: WorkspaceGraphRequest,
        loadedProjects: List<ProjectGraphShard> = emptyList(),
    ): WorkspaceGraphSlice =
        SOURCE_PROJECTOR.invoke(null, summary, generationId, request, loadedProjects) as WorkspaceGraphSlice

    private fun decodeProject(reference: ProjectShardReference): ProjectGraphShard =
        WorkspaceSiteJson.decodeProject(Files.readString(siteRoot.resolve(reference.file)))

    private fun projectRequest(projectId: String): WorkspaceGraphRequest =
        WorkspaceGraphRequest(
            workspaceId = summary.workspace.id,
            generationId = generationId,
            facet = WorkspaceGraphFacet.SOURCE,
            view =
                WorkspaceGraphView(
                    selection =
                        WorkspaceGraphSelection(
                            scopeRootIds = listOf(projectId),
                            expandedNodeIds = listOf(projectId),
                        ),
                ),
        )

    private fun workspaceRequest(limits: WorkspaceGraphLimits): WorkspaceGraphRequest =
        WorkspaceGraphRequest(
            workspaceId = summary.workspace.id,
            generationId = generationId,
            facet = WorkspaceGraphFacet.SOURCE,
            view =
                WorkspaceGraphView(
                    selection =
                        WorkspaceGraphSelection(
                            expandedNodeIds = summary.builds.map { build -> build.id }.sorted(),
                        ),
                ),
            limits = limits,
        )

    private fun payloadSize(
        name: String,
        path: Path,
    ): PayloadSize {
        val encodedBytes = Files.size(path)
        val decodedCharacters = Files.readString(path).length.toLong()
        return PayloadSize(name, encodedBytes, decodedCharacters * UTF16_BYTES_PER_CHARACTER)
    }

    private companion object {
        val SOURCE_PROJECTOR: Method =
            Class
                .forName("zone.clanker.docx.web.atlas.hierarchy.WorkspaceSourceHierarchyProjectorKt")
                .getMethod(
                    "workspaceSourceHierarchySlice",
                    WorkspaceSummaryShard::class.java,
                    String::class.java,
                    WorkspaceGraphRequest::class.java,
                    List::class.java,
                )
        const val MANIFEST_PATH = "data/manifest.json"
        const val SCALE_PROFILE_PATH = "data/scale-profile.properties"
        const val UTF16_BYTES_PER_CHARACTER = 2L
    }
}

internal data class StaticInitialMap(
    val manifest: WorkspaceSiteManifest,
    val summary: WorkspaceSummaryShard,
    val atlas: AtlasFrame,
)

internal data class PayloadSize(
    val name: String,
    val encodedBytes: Long,
    val decodedUtf16BytesEstimate: Long?,
)

internal data class ScaleProfileDescriptor(
    val profile: String,
    val builds: Int,
    val projects: Int,
    val files: Int,
    val symbols: Int,
    val relationships: Int,
    val logicalLines: Long,
    val materializedLines: Long,
)

private fun decodeManifest(path: Path): WorkspaceSiteManifest =
    WorkspaceSiteJson.decodeManifest(Files.readString(path))

private fun decodeSummary(path: Path): WorkspaceSummaryShard =
    WorkspaceSiteJson.decodeWorkspace(Files.readString(path))

private fun readScaleProfile(path: Path): ScaleProfileDescriptor {
    val properties = Properties().apply { Files.newInputStream(path).use { input -> load(input) } }

    fun required(name: String): String = requireNotNull(properties.getProperty(name)) { "Missing scale profile $name" }
    return ScaleProfileDescriptor(
        profile = required("profile"),
        builds = required("builds").toInt(),
        projects = required("projects").toInt(),
        files = required("files").toInt(),
        symbols = required("symbols").toInt(),
        relationships = required("relationships").toInt(),
        logicalLines = required("logicalLines").toLong(),
        materializedLines = required("materializedLines").toLong(),
    )
}

private val HARD_LIMITS =
    WorkspaceGraphLimits(
        nodeLimit = WorkspaceGraphLimits.MAX_NODE_LIMIT,
        relationLimit = WorkspaceGraphLimits.MAX_RELATION_LIMIT,
        evidencePerRelationLimit = WorkspaceGraphLimits.MAX_EVIDENCE_PER_RELATION_LIMIT,
    )
