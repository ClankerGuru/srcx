package zone.clanker.docx.index.query

import zone.clanker.docx.index.WorkspaceGraphCancellation
import zone.clanker.report.model.WorkspaceGraphNode
import zone.clanker.report.model.WorkspaceGraphNodeHierarchy
import zone.clanker.report.model.WorkspaceGraphNodeKind
import zone.clanker.report.model.WorkspaceGraphNodePresentation
import zone.clanker.report.model.WorkspaceGraphNodeRole
import zone.clanker.report.model.WorkspaceGraphRelationKind
import java.util.concurrent.CancellationException

internal data class GraphGeneration(
    val registeredWorkspaceId: String,
    val sourceWorkspaceId: String,
    val generationId: String,
    val projectCount: Int,
)

internal data class GraphNodeRow(
    val key: Long,
    val id: String,
    val kind: WorkspaceGraphNodeKind,
    val parentKey: Long?,
    val parentId: String?,
    val depth: Int,
    val semanticLevel: Int,
    val directChildCount: Long,
    val descendantCount: Long,
    val label: String,
    val secondaryLabel: String?,
) {
    fun node(roles: Set<WorkspaceGraphNodeRole>): WorkspaceGraphNode =
        WorkspaceGraphNode(
            id = id,
            kind = kind,
            hierarchy =
                WorkspaceGraphNodeHierarchy(
                    parentId = parentId,
                    depth = depth,
                    directChildCount = directChildCount,
                    descendantCount = descendantCount,
                ),
            presentation = WorkspaceGraphNodePresentation(label, secondaryLabel),
            roles = roles.sortedBy(Enum<*>::name),
        )
}

internal data class GraphRelationAggregate(
    val id: String,
    val kind: WorkspaceGraphRelationKind,
    val sourceKey: Long,
    val sourceId: String,
    val targetKey: Long,
    val targetId: String,
    val factCount: Long,
    val sampleFactIds: List<String>,
    val matchingRelationCount: Long,
)

internal data class GraphRelationProjection(
    val aggregates: List<GraphRelationAggregate>,
    val matchingRelationCount: Long,
)

internal fun WorkspaceGraphCancellation.checkActive() {
    if (isCancelled()) {
        throw CancellationException("Workspace graph query was cancelled")
    }
}
