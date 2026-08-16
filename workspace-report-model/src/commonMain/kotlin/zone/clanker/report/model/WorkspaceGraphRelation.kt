package zone.clanker.report.model

import kotlinx.serialization.Serializable

/** One semantic relation after projection to the nearest visible endpoint ancestors. */
@Serializable
data class WorkspaceGraphRelation(
    val id: String,
    val endpoints: WorkspaceGraphRelationEndpoints,
    val kind: WorkspaceGraphRelationKind,
    val facts: WorkspaceGraphRelationFacts,
) {
    init {
        requireValidId(id, "Workspace-graph relation")
    }
}

@Serializable
data class WorkspaceGraphRelationEndpoints(
    val sourceNodeId: String,
    val targetNodeId: String,
) {
    init {
        requireValidId(sourceNodeId, "Workspace-graph relation source")
        requireValidId(targetNodeId, "Workspace-graph relation target")
    }
}

/** Deterministic bounded evidence for one aggregate relation. */
@Serializable
data class WorkspaceGraphRelationFacts(
    val factCount: Long,
    val sampleFactIds: List<String> = emptyList(),
    val omittedFactCount: Long = factCount - sampleFactIds.size,
) {
    init {
        require(factCount > 0) { "Workspace-graph relation fact count must be positive" }
        sampleFactIds.forEach { id -> requireValidId(id, "Workspace-graph sampled fact") }
        requireDistinctAndSorted(sampleFactIds, "Workspace-graph sampled fact IDs")
        require(sampleFactIds.size <= WorkspaceGraphLimits.MAX_EVIDENCE_PER_RELATION_LIMIT) {
            "Workspace-graph sampled facts exceed the hard evidence limit"
        }
        require(omittedFactCount >= 0 && sampleFactIds.size.toLong() + omittedFactCount == factCount) {
            "Workspace-graph sampled and omitted fact counts must cover the aggregate"
        }
    }
}

@Serializable
enum class WorkspaceGraphRelationKind {
    IMPORT,
    EXTENDS,
    IMPLEMENTS,
    CALL,
    CONSTRUCTOR,
    NAME_REFERENCE,
    TYPE_REFERENCE,
    PROPERTY_TYPE,
    PARAMETER_TYPE,
    RETURN_TYPE,
    BUILD_DEPENDS_ON,
    PROJECT_DEPENDS_ON,
    SOURCE_SET_DEPENDS_ON,
    VARIANT_USES_SOURCE_SET,
    TASK_DEPENDS_ON,
    TASK_FINALIZED_BY,
    TASK_MUST_RUN_AFTER,
    TASK_SHOULD_RUN_AFTER,
    DECLARES_DEPENDENCY,
    UPGRADE_AVAILABLE,
}
