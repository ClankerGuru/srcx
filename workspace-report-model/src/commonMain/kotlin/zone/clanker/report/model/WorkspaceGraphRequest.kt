package zone.clanker.report.model

import kotlinx.serialization.Serializable

/** Bounded, renderer-neutral request for one continuously expandable workspace graph slice. */
@Serializable
data class WorkspaceGraphRequest(
    val workspaceId: String,
    val generationId: String? = null,
    val facet: WorkspaceGraphFacet,
    val view: WorkspaceGraphView = WorkspaceGraphView(),
    val limits: WorkspaceGraphLimits = WorkspaceGraphLimits(),
) {
    init {
        requireValidId(workspaceId, "Workspace-graph workspace")
        generationId?.let { requireValidId(it, "Workspace-graph generation") }
        val requiredNodeIds = view.selection.scopeRootIds + view.selection.focusNodeIds
        require(requiredNodeIds.distinct().size <= limits.nodeLimit) {
            "Workspace-graph scope and focus nodes must fit within the requested node limit"
        }
        view.continuationToken?.let {
            require(generationId != null) { "Workspace-graph continuation requires a pinned generation" }
        }
    }
}

/** Expansion, filtering, and continuation state for a graph request. */
@Serializable
data class WorkspaceGraphView(
    val selection: WorkspaceGraphSelection = WorkspaceGraphSelection(),
    val filter: WorkspaceGraphFilter = WorkspaceGraphFilter(),
    val continuationToken: String? = null,
) {
    init {
        continuationToken?.let { token ->
            require(token.isNotBlank()) { "Workspace-graph continuation token must not be blank" }
        }
    }
}

/** Empty scope roots select the workspace root; expansion and focus remain explicit. */
@Serializable
data class WorkspaceGraphSelection(
    val scopeRootIds: List<String> = emptyList(),
    val expandedNodeIds: List<String> = emptyList(),
    val focusNodeIds: List<String> = emptyList(),
) {
    init {
        scopeRootIds.forEach { id -> requireValidId(id, "Workspace-graph scope root") }
        expandedNodeIds.forEach { id -> requireValidId(id, "Workspace-graph expanded node") }
        focusNodeIds.forEach { id -> requireValidId(id, "Workspace-graph focus node") }
        requireDistinctAndSorted(scopeRootIds, "Workspace-graph scope roots")
        requireDistinctAndSorted(expandedNodeIds, "Workspace-graph expanded nodes")
        requireDistinctAndSorted(focusNodeIds, "Workspace-graph focus nodes")
        require(expandedNodeIds.size <= WorkspaceGraphLimits.MAX_NODE_LIMIT) {
            "Workspace-graph expanded nodes must not exceed ${WorkspaceGraphLimits.MAX_NODE_LIMIT}"
        }
    }
}

/** Empty kind/target lists mean all available values; a null count filter disables degree filtering. */
@Serializable
data class WorkspaceGraphFilter(
    val nodeKinds: List<WorkspaceGraphNodeKind> = emptyList(),
    val relationKinds: List<WorkspaceGraphRelationKind> = emptyList(),
    val query: String = "",
    val searchTargets: List<WorkspaceGraphSearchTarget> = emptyList(),
    val declarationKinds: List<WorkspaceGraphDeclarationKind> = emptyList(),
    val relationshipCountFilter: WorkspaceGraphRelationshipCountFilter? = null,
) {
    init {
        requireWorkspaceGraphEnumOrder(nodeKinds, "Workspace-graph node kinds")
        requireWorkspaceGraphEnumOrder(relationKinds, "Workspace-graph relation kinds")
        requireWorkspaceGraphEnumOrder(searchTargets, "Workspace-graph search targets")
        requireWorkspaceGraphEnumOrder(declarationKinds, "Workspace-graph declaration kinds")
        require(query.length <= MAX_QUERY_LENGTH) {
            "Workspace-graph query must not exceed $MAX_QUERY_LENGTH characters"
        }
    }

    companion object {
        const val MAX_QUERY_LENGTH: Int = 256
    }
}

/** Declaration categories that can jointly refine type, member, problem, and cycle nodes. */
@Serializable
enum class WorkspaceGraphDeclarationKind {
    CLASS,
    INTERFACE,
    OBJECT,
    ENUM,
    FUNCTION,
    METHOD,
    PROPERTY,
}

@Serializable
enum class WorkspaceGraphSearchTarget {
    BUILD,
    PROJECT,
    PACKAGE,
    FILE,
    CLASS,
    SYMBOL,
    METHOD,
    FILE_EXTENSION,
}

@Serializable
enum class WorkspaceGraphRelationshipDirection {
    ANY,
    OUTGOING,
    INCOMING,
}

@Serializable
data class WorkspaceGraphRelationshipCountFilter(
    val moreThan: Long,
    val direction: WorkspaceGraphRelationshipDirection = WorkspaceGraphRelationshipDirection.ANY,
    val keepInverseContext: Boolean = true,
) {
    init {
        require(moreThan >= 0) { "Workspace-graph relationship-count threshold must not be negative" }
    }
}

@Serializable
enum class WorkspaceGraphFacet {
    SOURCE,
    VARIANTS,
    TASKS,
    DEPENDENCIES,
    UPGRADES,
}

internal fun <T : Enum<T>> requireWorkspaceGraphEnumOrder(
    values: List<T>,
    label: String,
) {
    require(values == values.distinct().sortedBy(Enum<*>::name)) {
        "$label must be distinct and deterministic"
    }
}
