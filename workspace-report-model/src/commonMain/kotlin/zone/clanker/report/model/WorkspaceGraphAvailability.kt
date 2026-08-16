package zone.clanker.report.model

import kotlinx.serialization.Serializable

/** Coverage for one fact family. Complete with zero observations is different from unavailable. */
@Serializable
data class WorkspaceGraphFactAvailability(
    val fact: WorkspaceGraphFactKind,
    val state: WorkspaceGraphAvailabilityState,
    val observedFactCount: Long,
    val reason: WorkspaceGraphAvailabilityReason? = null,
) {
    init {
        require(observedFactCount >= 0) { "Workspace-graph observed fact count must not be negative" }
        require(
            when (state) {
                WorkspaceGraphAvailabilityState.COMPLETE -> reason == null
                WorkspaceGraphAvailabilityState.PARTIAL -> reason != null
                WorkspaceGraphAvailabilityState.UNAVAILABLE -> reason != null && observedFactCount == 0L
            },
        ) { "Workspace-graph availability state, count, and reason must agree" }
    }
}

@Serializable
enum class WorkspaceGraphFactKind {
    STRUCTURE,
    PACKAGES,
    SYMBOL_DECLARATIONS,
    MEMBER_OWNERSHIP,
    CODE_RELATIONSHIPS,
    PROBLEMS,
    CYCLES,
    BUILD_DEPENDENCIES,
    PROJECT_DEPENDENCIES,
    SOURCE_SET_DEPENDENCIES,
    VARIANTS,
    VARIANT_SOURCE_SETS,
    GRADLE_TASKS,
    TASK_RELATIONSHIPS,
    DECLARED_DEPENDENCIES,
    RESOLVED_DEPENDENCIES,
    DEPENDENCY_UPGRADES,
}

@Serializable
enum class WorkspaceGraphAvailabilityState {
    COMPLETE,
    PARTIAL,
    UNAVAILABLE,
}

@Serializable
enum class WorkspaceGraphAvailabilityReason {
    LEGACY_SNAPSHOT,
    NOT_CAPTURED,
    NOT_INDEXED,
    SOURCE_SCAN_ONLY,
}

internal fun requireWorkspaceGraphAvailabilityCatalog(
    availability: List<WorkspaceGraphFactAvailability>,
) {
    val facts = availability.map(WorkspaceGraphFactAvailability::fact)
    requireWorkspaceGraphEnumOrder(facts, "Workspace-graph fact availability")
    require(facts == WorkspaceGraphFactKind.entries.sortedBy(Enum<*>::name)) {
        "Workspace-graph slices must state availability for every fact family"
    }
}
