package zone.clanker.docx.live.observation

import kotlinx.coroutines.flow.Flow
import kotlinx.rpc.annotations.Rpc
import zone.clanker.report.model.WorkspaceCatalogResponse
import zone.clanker.report.model.WorkspaceEvent

/** Version of the read-only live observation contract, independent from its eventual transport. */
data object WorkspaceObservationProtocol {
    const val CURRENT_VERSION: Int = 1

    fun supports(version: Int): Boolean = version == CURRENT_VERSION
}

/** Small live boundary for catalog discovery and cancellable workspace updates. */
@Rpc
interface WorkspaceObservation {
    suspend fun catalog(): WorkspaceCatalogResponse

    fun updates(): Flow<WorkspaceEvent>
}
