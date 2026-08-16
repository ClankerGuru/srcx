package zone.clanker.docx.web.atlas.projection

import zone.clanker.report.model.AtlasBuild
import zone.clanker.report.model.AtlasEdge
import zone.clanker.report.model.AtlasFrame
import zone.clanker.report.model.AtlasLens
import zone.clanker.report.model.AtlasNode
import zone.clanker.report.model.BuildSnapshot
import zone.clanker.report.model.ProjectGraphShard
import zone.clanker.report.model.ProjectSnapshot
import zone.clanker.report.model.SymbolKind
import zone.clanker.report.model.WorkspaceSummaryShard

/** Projects every project shard owned by one build into a complete, unpaged Atlas frame. */
fun atlasBuildFrame(
    summary: WorkspaceSummaryShard,
    buildId: String,
    projects: List<ProjectGraphShard>,
    request: AtlasProjectFrameRequest,
): AtlasFrame =
    atlasSelectionFrame(
        summary = summary,
        selection = AtlasScopeSelection(buildIds = setOf(buildId)),
        projects = projects,
        request = request,
    )

/** Projects an arbitrary, explicitly selected union of project shards into one complete, unpaged Atlas frame. */
fun atlasSelectionFrame(
    summary: WorkspaceSummaryShard,
    selection: AtlasScopeSelection,
    projects: List<ProjectGraphShard>,
    request: AtlasProjectFrameRequest,
): AtlasFrame =
    AtlasBuildFrameProjector(
        AtlasSelectionFrameInput(summary, selection, projects, request),
    ).project()

internal data class AtlasProjectBuildFrames(
    val project: ProjectGraphShard,
    val current: AtlasFrame,
    val complete: AtlasFrame,
    val fileEvidence: AtlasFrame,
)

internal data class AtlasBuildMergedProjection(
    val nodes: List<AtlasNode>,
    val edges: List<AtlasEdge>,
    val totalNodeCount: Int,
    val matchingNodeCount: Int,
    val totalRelationshipRecordCount: Int,
    val shownRelationshipRecordCount: Int,
)

private data class AtlasSelectionFrameInput(
    val summary: WorkspaceSummaryShard,
    val selection: AtlasScopeSelection,
    val projects: List<ProjectGraphShard>,
    val request: AtlasProjectFrameRequest,
)

private class AtlasBuildFrameProjector(
    private val input: AtlasSelectionFrameInput,
) {
    private val buildsById = input.summary.builds.associateBy(BuildSnapshot::id)
    private val projectsById = input.summary.projects.associateBy(ProjectSnapshot::id)
    private val selectedBuildIds = input.selection.effectiveBuildIds(input.summary)
    private val expectedProjects = input.selection.selectedProjects(input.summary)

    fun project(): AtlasFrame {
        validateSelection()
        val projects = orderedProjects()
        val evidence = atlasBuildRawEvidence(projects)
        val merged = mergeAtlasBuildProjection(projectFrames(projects), input.request, evidence)
        val frameBuildIds =
            merged.nodes
                .mapNotNullTo(mutableSetOf(), AtlasNode::buildId)
                .apply { addAll(selectedBuildIds) }
        val visibleRelationshipIds = merged.edges.flatMapTo(mutableSetOf()) { edge -> edge.relationshipIds }
        return AtlasFrame(
            frameId = input.selection.frameId(input.request.lens, projects),
            scope = input.selection.atlasScope(input.summary),
            lens = input.request.lens,
            builds = frameBuildIds.sorted().map { id -> buildsById.getValue(id).atlasBuild() },
            nodes = merged.nodes,
            edges = merged.edges,
            totalNodeCount = merged.totalNodeCount,
            matchingNodeCount = merged.matchingNodeCount,
            pageIndex = 0,
            pageCount = if (merged.nodes.isEmpty()) 0 else 1,
            totalRelationshipRecordCount = merged.totalRelationshipRecordCount,
            shownRelationshipRecordCount = merged.shownRelationshipRecordCount,
            selectedNodeIds =
                input.request.selectedNodeIds
                    .distinct()
                    .filter(merged.nodeIds()::contains)
                    .sorted(),
            selectedEdgeId = input.request.selectedRelationshipId?.takeIf(visibleRelationshipIds::contains),
        )
    }

    private fun validateSelection() {
        val unknownBuildIds = input.selection.buildIds - buildsById.keys
        require(unknownBuildIds.isEmpty()) { "Unknown Atlas builds selected: ${unknownBuildIds.sorted()}" }
        val unknownProjectIds = input.selection.projectIds - projectsById.keys
        require(unknownProjectIds.isEmpty()) { "Unknown Atlas projects selected: ${unknownProjectIds.sorted()}" }
        require(expectedProjects.isNotEmpty()) { "Atlas selection must resolve at least one project" }
        val ownerBuildIds = expectedProjects.mapTo(mutableSetOf(), ProjectSnapshot::buildId)
        require(ownerBuildIds.all(input.selection.buildIds::contains)) {
            "Every selected Atlas project must retain its owning build"
        }
    }

    private fun orderedProjects(): List<ProjectGraphShard> {
        val projectsById = input.projects.associateBy(ProjectGraphShard::projectId)
        require(projectsById.size == input.projects.size) { "Atlas selection shards must have unique project IDs" }
        require(projectsById.keys == expectedProjects.mapTo(mutableSetOf(), ProjectSnapshot::id)) {
            "Atlas selection frame requires exactly its selected project shards"
        }
        return expectedProjects.map { project -> projectsById.getValue(project.id) }
    }

    private fun projectFrames(projects: List<ProjectGraphShard>): List<AtlasProjectBuildFrames> {
        val currentRequest = input.request.copy(relationshipCountFilter = null)
        val completeRequest =
            input.request.copy(
                query = "",
                searchTargets = emptySet(),
                sourceSetIds = emptySet(),
                symbolKinds = SymbolKind.entries.toSet(),
                relationshipCountFilter = null,
                selectedNodeIds = emptyList(),
                selectedRelationshipId = null,
            )
        val fileRequest = completeRequest.copy(lens = AtlasLens.FILES)
        return projects.map { project ->
            val current = atlasProjectFrame(input.summary, project, currentRequest)
            val complete =
                if (completeRequest == currentRequest) {
                    current
                } else {
                    atlasProjectFrame(input.summary, project, completeRequest)
                }
            val fileEvidence =
                if (complete.lens == fileRequest.lens) {
                    complete
                } else {
                    atlasProjectFrame(input.summary, project, fileRequest)
                }
            AtlasProjectBuildFrames(project, current, complete, fileEvidence)
        }
    }
}

private fun AtlasBuildMergedProjection.nodeIds(): Set<String> = nodes.mapTo(mutableSetOf(), AtlasNode::id)

private fun BuildSnapshot.atlasBuild(): AtlasBuild =
    AtlasBuild(
        id = id,
        name = name,
        context = kind.label,
        color = stableAtlasBuildColor(name),
    )

private fun stableAtlasBuildColor(name: String): String {
    val rawHue = name.hashCode().toLong() * BUILD_COLOR_HUE_STEP
    val hue = ((rawHue % BUILD_COLOR_HUE_COUNT) + BUILD_COLOR_HUE_COUNT) % BUILD_COLOR_HUE_COUNT
    return "hsl($hue 58% 66%)"
}

private const val BUILD_COLOR_HUE_COUNT = 360L
private const val BUILD_COLOR_HUE_STEP = 137L
