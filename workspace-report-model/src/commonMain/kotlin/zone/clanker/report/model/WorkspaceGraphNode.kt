package zone.clanker.report.model

import kotlinx.serialization.Serializable

/** One visible hierarchy or sibling-graph node. */
@Serializable
data class WorkspaceGraphNode(
    val id: String,
    val kind: WorkspaceGraphNodeKind,
    val hierarchy: WorkspaceGraphNodeHierarchy,
    val presentation: WorkspaceGraphNodePresentation,
    val roles: List<WorkspaceGraphNodeRole>,
) {
    init {
        requireValidId(id, "Workspace-graph node")
        hierarchy.parentId?.let { parent ->
            requireValidId(parent, "Workspace-graph node parent")
            require(parent != id) { "Workspace-graph node cannot contain itself" }
        }
        require(roles.isNotEmpty()) { "Workspace-graph node must explain why it was returned" }
        requireWorkspaceGraphEnumOrder(roles, "Workspace-graph node roles")
        require(
            if (kind == WorkspaceGraphNodeKind.WORKSPACE) {
                hierarchy.parentId == null && hierarchy.depth == 0
            } else {
                hierarchy.parentId != null && hierarchy.depth > 0
            },
        ) { "Workspace-graph workspace ownership must agree with node kind and depth" }
    }
}

/** Authoritative parent and full-generation child totals for a node. */
@Serializable
data class WorkspaceGraphNodeHierarchy(
    val parentId: String? = null,
    val depth: Int,
    val directChildCount: Long,
    val descendantCount: Long,
) {
    init {
        require(depth >= 0) { "Workspace-graph node depth must not be negative" }
        require(directChildCount >= 0 && descendantCount >= directChildCount) {
            "Workspace-graph node child totals must be non-negative and internally consistent"
        }
    }
}

@Serializable
data class WorkspaceGraphNodePresentation(
    val label: String,
    val secondaryLabel: String? = null,
) {
    init {
        require(label.isNotBlank()) { "Workspace-graph node label must not be blank" }
        require(secondaryLabel == null || secondaryLabel.isNotBlank()) {
            "Workspace-graph node secondary label must not be blank"
        }
    }
}

@Serializable
enum class WorkspaceGraphNodeKind {
    WORKSPACE,
    BUILD,
    PROJECT,
    SOURCE_SET,
    VARIANT,
    PACKAGE,
    FILE,
    TYPE,
    MEMBER,
    PROBLEM,
    CYCLE,
    TASK,
    DEPENDENCY,
    UPGRADE,
}

@Serializable
enum class WorkspaceGraphNodeRole {
    PRIMARY,
    FOCUS,
    ANCESTOR,
    ENDPOINT,
}
