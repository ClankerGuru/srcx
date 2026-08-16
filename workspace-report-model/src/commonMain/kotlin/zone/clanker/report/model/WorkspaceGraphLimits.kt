package zone.clanker.report.model

import kotlinx.serialization.Serializable

/** Client-requested limits, capped so every conforming producer has a fixed memory envelope. */
@Serializable
data class WorkspaceGraphLimits(
    val nodeLimit: Int = DEFAULT_NODE_LIMIT,
    val relationLimit: Int = DEFAULT_RELATION_LIMIT,
    val evidencePerRelationLimit: Int = DEFAULT_EVIDENCE_PER_RELATION_LIMIT,
) {
    init {
        require(nodeLimit in 1..MAX_NODE_LIMIT) {
            "Workspace-graph node limit must be between 1 and $MAX_NODE_LIMIT"
        }
        require(relationLimit in 1..MAX_RELATION_LIMIT) {
            "Workspace-graph relation limit must be between 1 and $MAX_RELATION_LIMIT"
        }
        require(evidencePerRelationLimit in 0..MAX_EVIDENCE_PER_RELATION_LIMIT) {
            "Workspace-graph evidence limit must be between 0 and $MAX_EVIDENCE_PER_RELATION_LIMIT"
        }
    }

    companion object {
        const val DEFAULT_NODE_LIMIT: Int = 400
        const val DEFAULT_RELATION_LIMIT: Int = 800
        const val DEFAULT_EVIDENCE_PER_RELATION_LIMIT: Int = 3
        const val MAX_NODE_LIMIT: Int = 2_000
        const val MAX_RELATION_LIMIT: Int = 5_000
        const val MAX_EVIDENCE_PER_RELATION_LIMIT: Int = 20
    }
}
