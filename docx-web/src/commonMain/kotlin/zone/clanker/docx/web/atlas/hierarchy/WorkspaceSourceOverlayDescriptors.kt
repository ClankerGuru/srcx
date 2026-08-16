package zone.clanker.docx.web.atlas.hierarchy

import zone.clanker.report.model.CycleSnapshot
import zone.clanker.report.model.FindingSnapshot
import zone.clanker.report.model.WorkspaceGraphDeclarationKind
import zone.clanker.report.model.WorkspaceGraphNodeKind

/** Captured analysis facts are lateral children of the deepest authoritative source owner. */
internal class WorkspaceSourceOverlayDescriptors(
    private val loadedFacts: WorkspaceSourceHierarchyLoadedFacts,
    hierarchyDescriptors: List<WorkspaceSourceHierarchyDescriptor>,
) {
    private val descriptorsById = hierarchyDescriptors.associateBy(WorkspaceSourceHierarchyDescriptor::id)

    fun descriptors(): List<WorkspaceSourceHierarchyDescriptor> =
        (
            loadedFacts.findingsById.values.map(::findingDescriptor) +
                loadedFacts.cyclesById.values.map(::cycleDescriptor)
        ).sortedBy(WorkspaceSourceHierarchyDescriptor::id)

    private fun findingDescriptor(finding: FindingSnapshot): WorkspaceSourceHierarchyDescriptor {
        val parent = finding.fileId?.let(descriptorsById::get) ?: findingSymbolParent(finding)
        val declarationKinds = declarationKinds(finding.symbolIds)
        return WorkspaceSourceHierarchyDescriptor(
            id = finding.id,
            kind = WorkspaceGraphNodeKind.PROBLEM,
            parentId = parent.id,
            depth = parent.depth + 1,
            semanticLevel = OVERLAY_LEVEL,
            label = finding.message,
            secondaryLabel = finding.severity.label,
            declarationKinds = declarationKinds,
        )
    }

    private fun findingSymbolParent(finding: FindingSnapshot): WorkspaceSourceHierarchyDescriptor =
        if (finding.symbolIds.isEmpty()) {
            descriptor(loadedFacts.findingProjectIdsById.getValue(finding.id))
        } else {
            deepestCommonAncestor(finding.symbolIds)
        }

    private fun cycleDescriptor(cycle: CycleSnapshot): WorkspaceSourceHierarchyDescriptor {
        val participantIds = cycle.symbolIds.distinct()
        val parent = deepestCommonAncestor(participantIds)
        return WorkspaceSourceHierarchyDescriptor(
            id = cycle.id,
            kind = WorkspaceGraphNodeKind.CYCLE,
            parentId = parent.id,
            depth = parent.depth + 1,
            semanticLevel = OVERLAY_LEVEL,
            label = "Cycle (${cycle.relationshipIds.size} relationships)",
            secondaryLabel = "Captured relationship cycle",
            declarationKinds = declarationKinds(participantIds),
        )
    }

    private fun declarationKinds(symbolIds: List<String>): Set<WorkspaceGraphDeclarationKind> =
        symbolIds.flatMapTo(mutableSetOf()) { symbolId -> descriptor(symbolId).declarationKinds }

    private fun deepestCommonAncestor(nodeIds: List<String>): WorkspaceSourceHierarchyDescriptor {
        require(nodeIds.isNotEmpty()) { "Source overlay requires at least one captured symbol" }
        val paths = nodeIds.map(::path)
        val commonDepth =
            paths
                .minOf { path -> path.size }
                .let { pathLength ->
                    (0 until pathLength)
                        .takeWhile { index ->
                            paths.map { path -> path[index].id }.distinct().size == 1
                        }.size
                }
        require(commonDepth > 0) { "Source overlay symbols do not share a workspace ancestor" }
        return paths.first()[commonDepth - 1]
    }

    private fun path(nodeId: String): List<WorkspaceSourceHierarchyDescriptor> {
        val reversePath = mutableListOf<WorkspaceSourceHierarchyDescriptor>()
        var descriptor: WorkspaceSourceHierarchyDescriptor? = descriptor(nodeId)
        while (descriptor != null) {
            reversePath += descriptor
            descriptor = descriptor.parentId?.let(::descriptor)
        }
        return reversePath.asReversed()
    }

    private fun descriptor(id: String): WorkspaceSourceHierarchyDescriptor =
        requireNotNull(descriptorsById[id]) { "Unknown captured source-overlay owner: $id" }
}

internal const val OVERLAY_LEVEL: Int = 7
