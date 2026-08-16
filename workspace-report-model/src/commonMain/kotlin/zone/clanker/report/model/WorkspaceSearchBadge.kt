package zone.clanker.report.model

import kotlinx.serialization.Serializable

@Serializable
data class WorkspaceSearchBadge(
    val kind: WorkspaceSearchBadgeKind,
    val count: Long,
) {
    init {
        require(count > 0) { "Workspace search badge counts must be positive" }
    }
}

@Serializable
enum class WorkspaceSearchBadgeKind(
    val label: String,
) {
    RELATIONSHIPS("relationships"),
    PROBLEMS("problems"),
    CYCLES("cycles"),
}
