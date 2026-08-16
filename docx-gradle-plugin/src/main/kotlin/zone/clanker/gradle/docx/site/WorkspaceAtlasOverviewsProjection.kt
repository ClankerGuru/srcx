package zone.clanker.gradle.docx.site

import zone.clanker.report.model.AtlasFrame
import zone.clanker.report.model.AtlasLens
import zone.clanker.report.model.AtlasNode
import zone.clanker.report.model.AtlasScope
import zone.clanker.report.model.AtlasScopeKind
import zone.clanker.report.model.WorkspaceAtlasOverviewShard
import zone.clanker.report.model.WorkspaceSnapshot

/** Precomputes constant-size workspace frames so switching lenses never requires every project shard. */
internal object WorkspaceAtlasOverviewsProjection {
    fun apply(
        snapshot: WorkspaceSnapshot,
        projection: ProjectedDocxSite,
    ): WorkspaceAtlasOverviewShard {
        val catalog = WorkspaceAtlasOverviewCatalog(snapshot, projection)
        return WorkspaceAtlasOverviewShard(
            AtlasLens.entries.map { lens ->
                when (lens) {
                    AtlasLens.FILES -> WorkspaceAtlasOverviewProjection.apply(snapshot, projection)
                    else -> WorkspaceAtlasLensOverviewProjector(snapshot, catalog, lens).project()
                }
            },
        )
    }
}

private class WorkspaceAtlasLensOverviewProjector(
    private val snapshot: WorkspaceSnapshot,
    private val catalog: WorkspaceAtlasOverviewCatalog,
    private val lens: AtlasLens,
) {
    fun project(): AtlasFrame {
        val candidates = catalog.candidates(lens)
        val primaryIds = selectPrimaryNodeIds(candidates)
        val candidateIds = candidates.mapTo(mutableSetOf(), WorkspaceOverviewNodeCandidate::id)
        val totalRelationshipCount = catalog.relationshipFacts(lens, candidateIds).size
        val projection = visibleProjection(primaryIds)
        val nodes =
            projection.visibleNodeIds
                .map { nodeId -> catalog.node(nodeId, nodeId in primaryIds, projection.relationships) }
                .sortedBy(AtlasNode::id)
        val edges = catalog.edges(projection.relationships)
        return AtlasFrame(
            frameId = "atlas:${snapshot.workspace.id}:${lens.name.lowercase()}:overview",
            scope =
                AtlasScope(
                    kind = AtlasScopeKind.WORKSPACE,
                    workspaceId = snapshot.workspace.id,
                    workspaceName = snapshot.workspace.name,
                ),
            lens = lens,
            builds = catalog.builds(nodes),
            nodes = nodes,
            edges = edges,
            totalNodeCount = candidates.size,
            matchingNodeCount = candidates.size,
            pageIndex = 0,
            pageCount = if (nodes.isEmpty()) 0 else 1,
            totalRelationshipRecordCount = totalRelationshipCount,
            shownRelationshipRecordCount =
                edges.sumOf { edge -> edge.recordCount } + nodes.sumOf(AtlasNode::internalRecordCount),
        )
    }

    private fun visibleProjection(primaryNodeIds: Set<String>): WorkspaceOverviewVisibleProjection {
        val visibleNodeIds = LinkedHashSet(primaryNodeIds)
        val visibleRelationships = mutableListOf<WorkspaceOverviewRelationshipFact>()
        catalog.relationshipFacts(lens, primaryNodeIds).forEach { fact ->
            val missingIds = listOf(fact.sourceId, fact.targetId).filterNot(visibleNodeIds::contains).distinct()
            if (visibleNodeIds.size + missingIds.size <= AtlasFrame.MAX_VISIBLE_NODES) {
                visibleNodeIds.addAll(missingIds)
                visibleRelationships += fact
            }
        }
        return WorkspaceOverviewVisibleProjection(visibleNodeIds.sorted(), visibleRelationships)
    }
}

private data class WorkspaceOverviewVisibleProjection(
    val visibleNodeIds: List<String>,
    val relationships: List<WorkspaceOverviewRelationshipFact>,
)

private fun selectPrimaryNodeIds(candidates: List<WorkspaceOverviewNodeCandidate>): Set<String> {
    val limit =
        if (candidates.size <= AtlasFrame.MAX_VISIBLE_NODES) {
            AtlasFrame.MAX_VISIBLE_NODES
        } else {
            OVERVIEW_PRIMARY_NODE_LIMIT
        }
    val ranked = candidates.sortedWith(OVERVIEW_NODE_ORDER)
    val selected = linkedSetOf<String>()
    ranked.distinctBy(WorkspaceOverviewNodeCandidate::buildId).forEach { candidate ->
        selected.addWithinLimit(candidate.id, limit)
    }
    ranked.forEach { candidate -> selected.addWithinLimit(candidate.id, limit) }
    return selected
}

private fun MutableSet<String>.addWithinLimit(
    id: String,
    limit: Int,
) {
    if (size < limit) add(id)
}

private val OVERVIEW_NODE_ORDER =
    compareByDescending<WorkspaceOverviewNodeCandidate> { candidate -> candidate.findingCount > 0 }
        .thenByDescending(WorkspaceOverviewNodeCandidate::findingCount)
        .thenByDescending(WorkspaceOverviewNodeCandidate::observedCycle)
        .thenByDescending(WorkspaceOverviewNodeCandidate::analysisCycle)
        .thenByDescending(WorkspaceOverviewNodeCandidate::importanceScore)
        .thenByDescending(WorkspaceOverviewNodeCandidate::relationshipCount)
        .thenBy(WorkspaceOverviewNodeCandidate::id)

private const val OVERVIEW_PRIMARY_NODE_LIMIT = 28
