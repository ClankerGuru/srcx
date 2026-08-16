package zone.clanker.docx.service.workspace

import zone.clanker.report.model.WorkspaceIndexStatus
import java.nio.file.Path

/** Optional acceleration boundary. Static serving remains correct when this is disabled or fails. */
internal interface WorkspaceGenerationIndexer : AutoCloseable {
    val enabled: Boolean

    fun index(request: WorkspaceGeneration): WorkspaceIndexStatus

    fun remove(workspaceId: String)

    override fun close()
}

internal data class WorkspaceGeneration(
    val workspaceId: String,
    val siteDirectory: Path,
    val generationId: String,
)

internal data object DisabledWorkspaceGenerationIndexer : WorkspaceGenerationIndexer {
    override val enabled: Boolean = false

    override fun index(request: WorkspaceGeneration): WorkspaceIndexStatus = WorkspaceIndexStatus.Disabled

    override fun remove(workspaceId: String) = Unit

    override fun close() = Unit
}
