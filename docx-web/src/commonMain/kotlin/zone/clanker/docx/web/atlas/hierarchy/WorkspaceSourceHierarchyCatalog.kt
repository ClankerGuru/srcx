@file:Suppress("LongParameterList")

package zone.clanker.docx.web.atlas.hierarchy

import zone.clanker.report.model.WorkspaceGraphDeclarationKind
import zone.clanker.report.model.WorkspaceGraphNode
import zone.clanker.report.model.WorkspaceGraphNodeHierarchy
import zone.clanker.report.model.WorkspaceGraphNodeKind
import zone.clanker.report.model.WorkspaceGraphNodePresentation
import zone.clanker.report.model.WorkspaceGraphNodeRole
import zone.clanker.report.model.WorkspaceGraphSearchTarget
import zone.clanker.report.model.WorkspaceSummaryShard

internal class WorkspaceSourceHierarchyCatalog(
    summary: WorkspaceSummaryShard,
    loadedFacts: WorkspaceSourceHierarchyLoadedFacts,
) {
    val workspaceId: String = summary.workspace.id

    private val recordsById: Map<String, WorkspaceSourceHierarchyRecord>
    private val childrenByParentId: Map<String, List<WorkspaceSourceHierarchyRecord>>

    init {
        val descriptors = workspaceSourceHierarchyDescriptors(summary, loadedFacts)
        val descriptorIds = descriptors.map(WorkspaceSourceHierarchyDescriptor::id)
        require(descriptorIds.distinct().size == descriptors.size) {
            "Workspace source hierarchy requires globally unique semantic IDs"
        }
        val descriptorsById = descriptors.associateBy(WorkspaceSourceHierarchyDescriptor::id)
        descriptors.forEach { descriptor -> descriptor.requireValidParent(descriptorsById) }
        val directChildCounts = descriptors.groupingBy(WorkspaceSourceHierarchyDescriptor::parentId).eachCount()
        val descendantCounts = descendantCounts(descriptors, descriptorsById)
        recordsById =
            descriptors.associate { descriptor ->
                descriptor.id to
                    descriptor.record(
                        directChildCount = (directChildCounts[descriptor.id] ?: 0).toLong(),
                        descendantCount = descendantCounts[descriptor.id] ?: 0,
                    )
            }
        childrenByParentId =
            recordsById.values
                .filter { record -> record.parentId != null }
                .groupBy { record -> requireNotNull(record.parentId) }
                .mapValues { (_, children) -> children.sortedBy(WorkspaceSourceHierarchyRecord::id) }
    }

    val size: Int
        get() = recordsById.size

    val observedPackageCount: Int
        get() =
            recordsById.values.count { record ->
                record.kind == WorkspaceGraphNodeKind.PACKAGE && !isWorkspaceUnknownSourcePackageId(record.id)
            }

    fun requireRecord(id: String): WorkspaceSourceHierarchyRecord =
        requireNotNull(recordsById[id]) { "Unknown workspace source-hierarchy node: $id" }

    fun recordOrNull(id: String): WorkspaceSourceHierarchyRecord? = recordsById[id]

    fun children(id: String): List<WorkspaceSourceHierarchyRecord> = childrenByParentId[id].orEmpty()

    fun path(id: String): List<WorkspaceSourceHierarchyRecord> {
        val reversePath = mutableListOf<WorkspaceSourceHierarchyRecord>()
        var record: WorkspaceSourceHierarchyRecord? = requireRecord(id)
        while (record != null) {
            reversePath += record
            record = record.parentId?.let(::requireRecord)
        }
        return reversePath.asReversed()
    }

    fun projectPath(projectId: String): List<WorkspaceSourceHierarchyRecord>? =
        recordOrNull(projectId)
            ?.takeIf { record -> record.kind == WorkspaceGraphNodeKind.PROJECT }
            ?.let { project -> path(project.id) }

    fun sourceSetPath(
        sourceSetId: String,
        fallbackProjectId: String?,
    ): List<WorkspaceSourceHierarchyRecord>? =
        recordOrNull(sourceSetId)?.let { sourceSet -> path(sourceSet.id) }
            ?: fallbackProjectId?.let(::projectPath)

    fun node(
        id: String,
        roles: Set<WorkspaceGraphNodeRole>,
    ): WorkspaceGraphNode {
        val record = requireRecord(id)
        return WorkspaceGraphNode(
            id = record.id,
            kind = record.kind,
            hierarchy =
                WorkspaceGraphNodeHierarchy(
                    parentId = record.parentId,
                    depth = record.depth,
                    directChildCount = record.directChildCount,
                    descendantCount = record.descendantCount,
                ),
            presentation =
                WorkspaceGraphNodePresentation(
                    label = record.label,
                    secondaryLabel = record.secondaryLabel,
                ),
            roles = roles.sortedBy(Enum<*>::name),
        )
    }
}

internal data class WorkspaceSourceHierarchyRecord(
    val id: String,
    val kind: WorkspaceGraphNodeKind,
    val parentId: String?,
    val depth: Int,
    val semanticLevel: Int,
    val directChildCount: Long,
    val descendantCount: Long,
    val label: String,
    val secondaryLabel: String?,
    val searchFields: Map<WorkspaceGraphSearchTarget, List<String>>,
    val declarationKinds: Set<WorkspaceGraphDeclarationKind>,
)

internal data class WorkspaceSourceHierarchyDescriptor(
    val id: String,
    val kind: WorkspaceGraphNodeKind,
    val parentId: String?,
    val depth: Int,
    val semanticLevel: Int,
    val label: String,
    val secondaryLabel: String?,
    val searchFields: Map<WorkspaceGraphSearchTarget, List<String>> = emptyMap(),
    val declarationKinds: Set<WorkspaceGraphDeclarationKind> = emptySet(),
) {
    fun requireValidParent(descriptorsById: Map<String, WorkspaceSourceHierarchyDescriptor>) {
        if (parentId == null) {
            require(kind == WorkspaceGraphNodeKind.WORKSPACE && depth == 0)
            return
        }
        val parent = requireNotNull(descriptorsById[parentId]) { "Unknown source-hierarchy parent: $parentId" }
        require(depth == parent.depth + 1) { "Source-hierarchy depth does not follow its parent: $id" }
    }

    fun record(
        directChildCount: Long,
        descendantCount: Long,
    ): WorkspaceSourceHierarchyRecord =
        WorkspaceSourceHierarchyRecord(
            id = id,
            kind = kind,
            parentId = parentId,
            depth = depth,
            semanticLevel = semanticLevel,
            directChildCount = directChildCount,
            descendantCount = descendantCount,
            label = label,
            secondaryLabel = secondaryLabel,
            searchFields = searchFields,
            declarationKinds = declarationKinds,
        )
}

private fun descendantCounts(
    descriptors: List<WorkspaceSourceHierarchyDescriptor>,
    descriptorsById: Map<String, WorkspaceSourceHierarchyDescriptor>,
): Map<String, Long> {
    val counts = mutableMapOf<String, Long>()
    descriptors.forEach { descriptor ->
        var ancestorId = descriptor.parentId
        while (ancestorId != null) {
            counts[ancestorId] = (counts[ancestorId] ?: 0) + 1
            ancestorId = descriptorsById.getValue(ancestorId).parentId
        }
    }
    return counts
}
