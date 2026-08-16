package zone.clanker.report.model

import kotlinx.serialization.Serializable

/** Source-set identity and size needed to navigate without loading its project graph shard. */
@Serializable
data class WorkspaceSourceSetSummary(
    val id: String,
    val projectId: String,
    val name: String,
    val fileCount: Int,
) {
    init {
        requireValidId(id, "Workspace source set")
        requireValidId(projectId, "Workspace source-set project")
        require(name.isNotBlank()) { "Workspace source-set name must not be blank" }
        require(fileCount >= 0) { "Workspace source-set file count must not be negative" }
    }
}
