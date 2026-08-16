package zone.clanker.docx.index.importing

import zone.clanker.report.model.WorkspaceGraphDeclarationKind
import zone.clanker.report.model.WorkspaceGraphNodeKind

internal class WorkspaceGraphOverlayDescriptors(
    private val rows: WorkspaceGraphSourceRows,
    hierarchyDescriptors: List<IndexedGraphDescriptor>,
) {
    private val descriptorsById = hierarchyDescriptors.associateBy(IndexedGraphDescriptor::id)

    fun descriptors(): List<IndexedGraphDescriptor> =
        (rows.findings.map(::findingDescriptor) + rows.cycles.map(::cycleDescriptor))
            .sortedBy(IndexedGraphDescriptor::id)

    private fun findingDescriptor(finding: GraphFindingRow): IndexedGraphDescriptor {
        val parent =
            finding.fileId?.let(descriptorsById::get)
                ?: finding.symbolIds.takeIf { symbolIds -> symbolIds.isNotEmpty() }?.let(::deepestCommonAncestor)
                ?: descriptor(finding.projectId)
        return IndexedGraphDescriptor(
            id = finding.id,
            kind = WorkspaceGraphNodeKind.PROBLEM,
            parentId = parent.id,
            depth = parent.depth + 1,
            semanticLevel = GRAPH_OVERLAY_LEVEL,
            label = finding.message,
            secondaryLabel = finding.severity.label,
            declarationKinds = declarationKinds(finding.symbolIds),
        )
    }

    private fun cycleDescriptor(cycle: GraphCycleRow): IndexedGraphDescriptor {
        val participants = cycle.symbolIds.distinct()
        val parent = deepestCommonAncestor(participants)
        return IndexedGraphDescriptor(
            id = cycle.id,
            kind = WorkspaceGraphNodeKind.CYCLE,
            parentId = parent.id,
            depth = parent.depth + 1,
            semanticLevel = GRAPH_OVERLAY_LEVEL,
            label = "Cycle (${cycle.symbolIds.size - 1} relationships)",
            secondaryLabel = "Captured relationship cycle",
            declarationKinds = declarationKinds(participants),
        )
    }

    private fun declarationKinds(symbolIds: List<String>): Set<WorkspaceGraphDeclarationKind> =
        symbolIds.flatMapTo(mutableSetOf()) { symbolId -> descriptor(symbolId).declarationKinds }

    private fun deepestCommonAncestor(nodeIds: List<String>): IndexedGraphDescriptor {
        require(nodeIds.isNotEmpty()) { "Indexed graph overlay requires captured symbol evidence" }
        val paths = nodeIds.map(::path)
        val commonDepth =
            (0 until paths.minOf { path -> path.size })
                .takeWhile { index ->
                    paths.map { path -> path[index].id }.distinct().size == 1
                }.size
        require(commonDepth > 0) { "Indexed graph overlay symbols have no common workspace ancestor" }
        return paths.first()[commonDepth - 1]
    }

    private fun path(nodeId: String): List<IndexedGraphDescriptor> {
        val reversePath = mutableListOf<IndexedGraphDescriptor>()
        var current: IndexedGraphDescriptor? = descriptor(nodeId)
        while (current != null) {
            reversePath += current
            current = current.parentId?.let(::descriptor)
        }
        return reversePath.asReversed()
    }

    private fun descriptor(id: String): IndexedGraphDescriptor =
        requireNotNull(descriptorsById[id]) { "Unknown indexed graph overlay owner: $id" }
}
