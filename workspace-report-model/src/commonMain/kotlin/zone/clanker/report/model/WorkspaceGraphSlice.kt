package zone.clanker.report.model

import kotlinx.serialization.Serializable

/** A complete, bounded graph response with explicit data availability. */
@Serializable
data class WorkspaceGraphSlice(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val target: WorkspaceGraphSliceTarget,
    val viewport: WorkspaceGraphSliceViewport = WorkspaceGraphSliceViewport(),
    val content: WorkspaceGraphSliceContent,
    val counts: WorkspaceGraphSliceCounts,
    val limits: WorkspaceGraphLimits = WorkspaceGraphLimits(),
) {
    init {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) {
            "Unsupported workspace-graph slice schema: $schemaVersion"
        }
        requireWorkspaceGraphAvailabilityCatalog(content.availability)
        requireUniqueAndSorted(content.nodes.map(WorkspaceGraphNode::id), "Workspace-graph nodes")
        requireUniqueAndSorted(content.relations.map(WorkspaceGraphRelation::id), "Workspace-graph relations")
        require(content.nodes.size <= limits.nodeLimit) { "Workspace-graph nodes exceed the declared slice limit" }
        require(content.relations.size <= limits.relationLimit) {
            "Workspace-graph relations exceed the declared slice limit"
        }
        require(
            content.relations.all { relation ->
                relation.facts.sampleFactIds.size <= limits.evidencePerRelationLimit
            },
        ) { "Workspace-graph relation evidence exceeds the declared slice limit" }
        require(counts.matchingPrimaryNodeCount >= returnedPrimaryNodeCount()) {
            "Workspace-graph matching primary count cannot be smaller than returned primary nodes"
        }
        require(counts.matchingRelationCount >= content.relations.size.toLong()) {
            "Workspace-graph matching relation count cannot be smaller than returned relations"
        }
        requireHierarchyClosure()
        requireRelationClosure()
    }

    private fun returnedPrimaryNodeCount(): Long =
        content.nodes.count { node -> WorkspaceGraphNodeRole.PRIMARY in node.roles }.toLong()

    private fun requireHierarchyClosure() {
        val nodesById = content.nodes.associateBy(WorkspaceGraphNode::id)
        val workspaceRoots = content.nodes.filter { node -> node.kind == WorkspaceGraphNodeKind.WORKSPACE }
        require(workspaceRoots.size == 1 && workspaceRoots.single().id == target.workspaceId) {
            "Workspace-graph slices must contain their one workspace root"
        }
        require(viewport.scopeRootIds.all(nodesById::containsKey)) {
            "Workspace-graph scope roots must be returned with the slice"
        }
        content.nodes.forEach { node ->
            val parentId = node.hierarchy.parentId ?: return@forEach
            val parent =
                requireNotNull(nodesById[parentId]) {
                    "Workspace-graph nodes require complete ancestor closure"
                }
            require(node.hierarchy.depth == parent.hierarchy.depth + 1) {
                "Workspace-graph node depth must follow its returned parent"
            }
        }
        val returnedChildren = content.nodes.groupingBy { node -> node.hierarchy.parentId }.eachCount()
        require(
            content.nodes.all { node ->
                node.hierarchy.directChildCount >= (returnedChildren[node.id] ?: 0)
            },
        ) { "Workspace-graph child totals cannot omit returned children" }
        requireDescendantTotals(nodesById)
    }

    private fun requireDescendantTotals(nodesById: Map<String, WorkspaceGraphNode>) {
        val returnedDescendants = mutableMapOf<String, Long>()
        content.nodes.forEach { node ->
            var parentId = node.hierarchy.parentId
            while (parentId != null) {
                returnedDescendants[parentId] = (returnedDescendants[parentId] ?: 0) + 1
                parentId = nodesById.getValue(parentId).hierarchy.parentId
            }
        }
        require(
            content.nodes.all { node ->
                node.hierarchy.descendantCount >= (returnedDescendants[node.id] ?: 0)
            },
        ) { "Workspace-graph descendant totals cannot omit returned descendants" }
    }

    private fun requireRelationClosure() {
        val nodeIds = content.nodes.mapTo(mutableSetOf(), WorkspaceGraphNode::id)
        require(
            content.relations.all { relation ->
                relation.endpoints.sourceNodeId in nodeIds && relation.endpoints.targetNodeId in nodeIds
            },
        ) { "Workspace-graph relations require complete endpoint closure" }
        require(
            content.relations
                .flatMap { relation -> relation.facts.sampleFactIds }
                .distinct()
                .size == content.relations.sumOf { relation -> relation.facts.sampleFactIds.size },
        ) { "Sampled workspace-graph facts may belong to only one returned relation" }
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1
    }
}

@Serializable
data class WorkspaceGraphSliceTarget(
    val workspaceId: String,
    val generationId: String,
    val facet: WorkspaceGraphFacet,
) {
    init {
        requireValidId(workspaceId, "Workspace-graph slice workspace")
        requireValidId(generationId, "Workspace-graph slice generation")
    }
}

@Serializable
data class WorkspaceGraphSliceViewport(
    val scopeRootIds: List<String> = emptyList(),
    val continuationToken: String? = null,
) {
    init {
        scopeRootIds.forEach { id -> requireValidId(id, "Workspace-graph slice scope root") }
        requireDistinctAndSorted(scopeRootIds, "Workspace-graph slice scope roots")
        continuationToken?.let { token ->
            require(token.isNotBlank()) { "Workspace-graph slice continuation token must not be blank" }
        }
    }
}

@Serializable
data class WorkspaceGraphSliceContent(
    val availability: List<WorkspaceGraphFactAvailability>,
    val nodes: List<WorkspaceGraphNode>,
    val relations: List<WorkspaceGraphRelation>,
)

@Serializable
data class WorkspaceGraphSliceCounts(
    val matchingPrimaryNodeCount: Long,
    val matchingRelationCount: Long,
) {
    init {
        require(matchingPrimaryNodeCount >= 0 && matchingRelationCount >= 0) {
            "Workspace-graph matching counts must not be negative"
        }
    }
}
