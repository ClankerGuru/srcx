package zone.clanker.docx.web.atlas.projection

import zone.clanker.report.model.AtlasEdge
import zone.clanker.report.model.AtlasFrame
import zone.clanker.report.model.AtlasLens
import zone.clanker.report.model.AtlasNode
import zone.clanker.report.model.AtlasScope
import zone.clanker.report.model.AtlasScopeKind

internal class AtlasFrameAssembler(
    private val context: AtlasProjectionContext,
) {
    fun frame(
        lens: AtlasLens,
        nodes: List<AtlasNode>,
        edges: List<AtlasEdge>,
        page: AtlasFramePage,
        selection: AtlasRequestedSelection,
    ): AtlasFrame {
        val activeProject = context.projectsById.getValue(context.project.projectId)
        val buildIds = nodes.mapNotNullTo(mutableSetOf(), AtlasNode::buildId).apply { add(activeProject.buildId) }
        val visibleRelationshipIds = edges.flatMapTo(mutableSetOf(), AtlasEdge::relationshipIds)
        return AtlasFrame(
            frameId = "atlas:${context.project.projectId}:${lens.name.lowercase()}:${page.pageIndex}",
            scope =
                AtlasScope(
                    kind = AtlasScopeKind.PROJECT,
                    workspaceId = context.summary.workspace.id,
                    workspaceName = context.summary.workspace.name,
                    buildId = activeProject.buildId,
                    projectId = activeProject.id,
                ),
            lens = lens,
            builds = buildIds.sorted().map { buildId -> context.buildsById.getValue(buildId).projectedAtlasBuild() },
            nodes = nodes,
            edges = edges,
            totalNodeCount = page.totalNodeCount,
            matchingNodeCount = page.matchingNodeCount,
            pageIndex = page.pageIndex,
            pageCount = page.pageCount,
            totalRelationshipRecordCount = page.totalRelationshipRecordCount,
            shownRelationshipRecordCount =
                edges.sumOf(AtlasEdge::recordCount) + nodes.sumOf(AtlasNode::internalRecordCount),
            selectedNodeIds =
                selection.nodeIds.filterTo(mutableSetOf()) { it in nodes.map(AtlasNode::id) }.sorted(),
            selectedEdgeId = selection.relationshipId?.takeIf(visibleRelationshipIds::contains),
        )
    }
}
