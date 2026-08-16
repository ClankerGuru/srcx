package zone.clanker.docx.web.atlas.session

import zone.clanker.report.model.WorkspaceGraphNode
import zone.clanker.report.model.WorkspaceGraphNodeKind
import zone.clanker.report.model.WorkspaceGraphSlice

internal fun WorkspaceGraphSlice.atlasHierarchyNodes(): List<AtlasHierarchyNode> =
    content.nodes.mapNotNull(WorkspaceGraphNode::atlasHierarchyNode)

internal fun AtlasSession.extendHierarchy(additions: List<AtlasHierarchyNode>): AtlasSession {
    if (additions.isEmpty()) return this
    val extended = hierarchy.extending(additions)
    return if (extended == hierarchy) this else copy(hierarchy = extended)
}

internal fun WorkspaceGraphNode.atlasHierarchyNode(): AtlasHierarchyNode? {
    val level = kind.atlasHierarchyLevel ?: return null
    val parentId =
        when (kind) {
            WorkspaceGraphNodeKind.BUILD -> null
            else -> hierarchy.parentId?.let(::AtlasSemanticId)
        }
    return AtlasHierarchyNode(
        id = AtlasSemanticId(id),
        level = level,
        parentId = parentId,
    )
}

private val WorkspaceGraphNodeKind.atlasHierarchyLevel: AtlasHierarchyLevel?
    get() =
        when (this) {
            WorkspaceGraphNodeKind.WORKSPACE -> null
            WorkspaceGraphNodeKind.BUILD -> AtlasHierarchyLevel.BUILD
            WorkspaceGraphNodeKind.PROJECT -> AtlasHierarchyLevel.PROJECT
            WorkspaceGraphNodeKind.SOURCE_SET -> AtlasHierarchyLevel.SOURCE_SET
            WorkspaceGraphNodeKind.PACKAGE -> AtlasHierarchyLevel.PACKAGE
            WorkspaceGraphNodeKind.FILE -> AtlasHierarchyLevel.FILE
            WorkspaceGraphNodeKind.TYPE -> AtlasHierarchyLevel.TYPE
            WorkspaceGraphNodeKind.MEMBER -> AtlasHierarchyLevel.MEMBER
            WorkspaceGraphNodeKind.PROBLEM,
            WorkspaceGraphNodeKind.CYCLE,
            WorkspaceGraphNodeKind.VARIANT,
            WorkspaceGraphNodeKind.TASK,
            WorkspaceGraphNodeKind.DEPENDENCY,
            WorkspaceGraphNodeKind.UPGRADE,
            -> null
        }
