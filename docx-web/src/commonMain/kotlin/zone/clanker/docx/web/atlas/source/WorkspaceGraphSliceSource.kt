package zone.clanker.docx.web.atlas.source

import zone.clanker.report.model.ProjectGraphShard
import zone.clanker.report.model.WorkspaceGraphRequest
import zone.clanker.report.model.WorkspaceGraphSlice

/** One bounded graph request/response boundary shared by static and service-backed viewers. */
internal fun interface WorkspaceGraphSliceSource {
    suspend fun load(request: WorkspaceGraphRequest): WorkspaceGraphSlice
}

internal fun interface ProjectGraphShardSource {
    suspend fun load(projectId: String): ProjectGraphShard
}

internal fun interface WorkspaceGraphSliceProjector {
    fun project(
        request: WorkspaceGraphRequest,
        projects: List<ProjectGraphShard>,
    ): WorkspaceGraphSlice
}

internal fun interface WorkspaceGraphSearchProjectSource {
    suspend fun projectIds(request: WorkspaceGraphRequest): List<String>

    companion object {
        val EMPTY = WorkspaceGraphSearchProjectSource { emptyList() }
    }
}
