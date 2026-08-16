package zone.clanker.docx.index.performance

import zone.clanker.docx.index.IndexResult
import zone.clanker.docx.index.WorkspaceReportIndex
import zone.clanker.report.model.WorkspaceGraphRequest
import zone.clanker.report.model.WorkspaceGraphSlice
import java.nio.file.Path

internal class LiveAtlasPerformanceProbe(
    databaseFile: Path,
    private val siteRoot: Path,
    private val workspaceId: String,
) : AutoCloseable {
    private val index = WorkspaceReportIndex.open(databaseFile)

    fun importSite(): IndexResult = index.indexSite(workspaceId, siteRoot)

    fun graphSlice(request: WorkspaceGraphRequest): WorkspaceGraphSlice = index.graphSlice(workspaceId, request)

    fun symbolSearch(): Int = index.searchSymbols(workspaceId, "Node0000", limit = 50).size

    fun relationshipSummary(): Int = index.relationshipSummary(workspaceId).size

    override fun close() = index.close()
}
