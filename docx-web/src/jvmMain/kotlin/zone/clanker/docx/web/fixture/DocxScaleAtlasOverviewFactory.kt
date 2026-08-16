package zone.clanker.docx.web.fixture

import zone.clanker.report.model.AtlasBuild
import zone.clanker.report.model.AtlasEdge
import zone.clanker.report.model.AtlasFrame
import zone.clanker.report.model.AtlasLens
import zone.clanker.report.model.AtlasNode
import zone.clanker.report.model.AtlasScope
import zone.clanker.report.model.AtlasScopeKind
import zone.clanker.report.model.BuildSnapshot
import zone.clanker.report.model.WorkspaceIdentity

internal class DocxScaleAtlasOverviewFactory(
    private val dimensions: DocxScaleDimensions,
    private val workspace: WorkspaceIdentity,
    private val builds: List<BuildSnapshot>,
    private val projects: List<DocxScaleProject>,
) {
    fun atlasOverview(): AtlasFrame =
        if (dimensions.includeProjectDependencies) compositeOverview() else singleProjectOverview()

    private fun singleProjectOverview(): AtlasFrame {
        val project = projects.first()
        val atlas = DocxScaleProjectAtlasProjector(project)
        val visibleFileCount = minOf(AtlasFrame.MAX_VISIBLE_NODES, project.fileCount)
        val nodes = (0 until visibleFileCount).map(atlas::fileNode)
        val edges = (0 until visibleFileCount - 1).map(atlas::fileEdge)
        return frame(nodes, edges, edges.size)
    }

    private fun compositeOverview(): AtlasFrame {
        val projectNodes =
            projects
                .take(AtlasFrame.MAX_VISIBLE_NODES)
                .map { project -> DocxScaleProjectAtlasProjector(project).fileNode(0) }
        val remainingNodeCount = (AtlasFrame.MAX_VISIBLE_NODES - projectNodes.size).coerceAtLeast(0)
        val extraNodes =
            projects
                .asSequence()
                .flatMap { project ->
                    val atlas = DocxScaleProjectAtlasProjector(project)
                    (1 until project.fileCount).asSequence().map(atlas::fileNode)
                }.take(remainingNodeCount)
                .toList()
        val nodes = (projectNodes + extraNodes).sortedBy(AtlasNode::id)
        val nodeIds = nodes.mapTo(mutableSetOf(), AtlasNode::id)
        val edges =
            projects
                .mapNotNull { project ->
                    dependencyOf(project)?.let { dependency ->
                        DocxScaleProjectAtlasProjector(project).dependencyEdge(dependency)
                    }
                }.filter { edge -> edge.sourceId in nodeIds && edge.targetId in nodeIds }
                .sortedBy(AtlasEdge::id)
        return frame(nodes, edges, edges.sumOf(AtlasEdge::recordCount))
    }

    private fun frame(
        nodes: List<AtlasNode>,
        edges: List<AtlasEdge>,
        shownRelationshipRecordCount: Int,
    ): AtlasFrame =
        AtlasFrame(
            frameId = "atlas:workspace:scale:${dimensions.profileName}:files:overview",
            scope = AtlasScope(AtlasScopeKind.WORKSPACE, workspace.id, workspace.name),
            lens = AtlasLens.FILES,
            builds =
                builds.mapIndexed { index, build ->
                    AtlasBuild(build.id, build.name, build.kind.label, buildColor(index))
                },
            nodes = nodes,
            edges = edges,
            totalNodeCount = dimensions.fileCount,
            matchingNodeCount = dimensions.fileCount,
            pageIndex = 0,
            pageCount =
                (dimensions.fileCount + AtlasFrame.MAX_VISIBLE_NODES - 1) / AtlasFrame.MAX_VISIBLE_NODES,
            totalRelationshipRecordCount = dimensions.relationshipCount,
            shownRelationshipRecordCount = shownRelationshipRecordCount,
        )

    private fun dependencyOf(project: DocxScaleProject): DocxScaleProject? =
        projects.getOrNull(project.ordinal - 1).takeIf { dimensions.includeProjectDependencies }
}
