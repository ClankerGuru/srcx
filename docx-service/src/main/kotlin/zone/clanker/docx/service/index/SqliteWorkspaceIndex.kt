package zone.clanker.docx.service.index

import zone.clanker.docx.index.IndexScope
import zone.clanker.docx.index.WorkspaceReportIndex
import zone.clanker.docx.service.workspace.WorkspaceGeneration
import zone.clanker.docx.service.workspace.WorkspaceGenerationIndexer
import zone.clanker.report.model.FindingSeverity
import zone.clanker.report.model.RelationshipKind
import zone.clanker.report.model.SymbolKind
import zone.clanker.report.model.WorkspaceDeclarationEvidencePage
import zone.clanker.report.model.WorkspaceDeclarationEvidenceRequest
import zone.clanker.report.model.WorkspaceFindingCount
import zone.clanker.report.model.WorkspaceFindingSummaryResponse
import zone.clanker.report.model.WorkspaceGraphRequest
import zone.clanker.report.model.WorkspaceGraphSlice
import zone.clanker.report.model.WorkspaceIndexStatus
import zone.clanker.report.model.WorkspaceRelationshipCount
import zone.clanker.report.model.WorkspaceRelationshipOccurrencePage
import zone.clanker.report.model.WorkspaceRelationshipOccurrenceRequest
import zone.clanker.report.model.WorkspaceRelationshipSummaryResponse
import zone.clanker.report.model.WorkspaceReverseUsagePage
import zone.clanker.report.model.WorkspaceReverseUsageRequest
import zone.clanker.report.model.WorkspaceSymbolDeclaration
import zone.clanker.report.model.WorkspaceSymbolHit
import zone.clanker.report.model.WorkspaceSymbolScope
import zone.clanker.report.model.WorkspaceSymbolSearchResponse
import java.nio.file.Path

internal interface WorkspaceQueryIndex {
    fun searchSymbols(request: SymbolQuery): WorkspaceSymbolSearchResponse

    fun relationshipSummary(request: RelationshipQuery): WorkspaceRelationshipSummaryResponse

    fun findingSummary(request: FindingQuery): WorkspaceFindingSummaryResponse
}

internal interface WorkspaceGraphQueryIndex {
    fun graphSlice(request: GraphQuery): WorkspaceGraphSlice
}

internal data class GraphQuery(
    val workspaceId: String,
    val request: WorkspaceGraphRequest,
)

internal data class WorkspaceQueryScope(
    val buildId: String? = null,
    val projectId: String? = null,
    val sourceSetIds: Set<String> = emptySet(),
)

internal data class SymbolQuery(
    val workspaceId: String,
    val query: String,
    val scope: WorkspaceQueryScope,
    val kinds: Set<SymbolKind>,
    val limit: Int,
)

internal data class RelationshipQuery(
    val workspaceId: String,
    val scope: WorkspaceQueryScope,
    val kinds: Set<RelationshipKind>,
)

internal data class FindingQuery(
    val workspaceId: String,
    val scope: WorkspaceQueryScope,
    val severities: Set<FindingSeverity>,
)

internal class SqliteWorkspaceIndex private constructor(
    private val index: WorkspaceReportIndex,
    private val clock: () -> Long,
) : WorkspaceGenerationIndexer,
    WorkspaceQueryIndex,
    WorkspaceGraphQueryIndex,
    WorkspaceEvidenceQueryIndex {
    override val enabled: Boolean = true

    override fun index(request: WorkspaceGeneration): WorkspaceIndexStatus {
        val result = index.indexSite(request.workspaceId, request.siteDirectory)
        require(result.generation.generationId == request.generationId) {
            "Indexed generation does not match the observed site generation"
        }
        return WorkspaceIndexStatus.Current(request.generationId, clock())
    }

    override fun remove(workspaceId: String) {
        index.remove(workspaceId)
    }

    override fun searchSymbols(request: SymbolQuery): WorkspaceSymbolSearchResponse =
        index
            .searchSymbols(
                workspaceId = request.workspaceId,
                query = request.query,
                scope = request.scope.toIndexScope(),
                kinds = request.kinds,
                limit = request.limit,
            ).map { result ->
                WorkspaceSymbolHit(
                    symbolId = result.symbolId,
                    scope =
                        WorkspaceSymbolScope(
                            buildId = result.buildId,
                            projectId = result.projectId,
                            sourceSetId = result.sourceSetId,
                            sourceSetName = result.sourceSetName,
                            fileId = result.fileId,
                            filePath = result.filePath,
                        ),
                    declaration =
                        WorkspaceSymbolDeclaration(
                            name = result.name,
                            qualifiedName = result.qualifiedName,
                            packageName = result.packageName,
                            kind = result.kind,
                            declarationLine = result.declarationLine,
                        ),
                )
            }.let(::WorkspaceSymbolSearchResponse)

    override fun relationshipSummary(request: RelationshipQuery): WorkspaceRelationshipSummaryResponse =
        index
            .relationshipSummary(request.workspaceId, request.scope.toIndexScope(), request.kinds)
            .map { result ->
                WorkspaceRelationshipCount(
                    kind = result.kind,
                    recordCount = result.recordCount,
                    sourceSymbolCount = result.sourceSymbolCount,
                    targetSymbolCount = result.targetSymbolCount,
                    heuristicCount = result.heuristicCount,
                )
            }.let(::WorkspaceRelationshipSummaryResponse)

    override fun findingSummary(request: FindingQuery): WorkspaceFindingSummaryResponse =
        index
            .findingSummary(request.workspaceId, request.scope.toIndexScope(), request.severities)
            .map { result ->
                WorkspaceFindingCount(
                    severity = result.severity,
                    findingCount = result.findingCount,
                    projectCount = result.projectCount,
                    fileCount = result.fileCount,
                )
            }.let(::WorkspaceFindingSummaryResponse)

    override fun graphSlice(request: GraphQuery): WorkspaceGraphSlice =
        index.graphSlice(request.workspaceId, request.request)

    override fun declarationEvidence(
        workspaceId: String,
        request: WorkspaceDeclarationEvidenceRequest,
    ): WorkspaceDeclarationEvidencePage = index.declarationEvidence(workspaceId, request)

    override fun reverseUsages(
        workspaceId: String,
        request: WorkspaceReverseUsageRequest,
    ): WorkspaceReverseUsagePage = index.reverseUsages(workspaceId, request)

    override fun relationshipOccurrences(
        workspaceId: String,
        request: WorkspaceRelationshipOccurrenceRequest,
    ): WorkspaceRelationshipOccurrencePage = index.relationshipOccurrences(workspaceId, request)

    override fun close() = index.close()

    companion object {
        fun open(
            databaseFile: Path,
            clock: () -> Long = System::currentTimeMillis,
        ): SqliteWorkspaceIndex = SqliteWorkspaceIndex(WorkspaceReportIndex.open(databaseFile), clock)
    }
}

private fun WorkspaceQueryScope.toIndexScope(): IndexScope = IndexScope(buildId, projectId, sourceSetIds)
