package zone.clanker.docx.index

import zone.clanker.docx.index.database.SqliteDatabase
import zone.clanker.docx.index.generation.readActiveGeneration
import zone.clanker.docx.index.generation.removeWorkspace
import zone.clanker.docx.index.importing.importWorkspaceSite
import zone.clanker.docx.index.query.queryDeclarationEvidence
import zone.clanker.docx.index.query.queryFindingSummary
import zone.clanker.docx.index.query.queryGraphSlice
import zone.clanker.docx.index.query.queryRelationshipOccurrences
import zone.clanker.docx.index.query.queryRelationshipSummary
import zone.clanker.docx.index.query.queryReverseUsages
import zone.clanker.docx.index.query.querySymbols
import zone.clanker.report.model.FindingSeverity
import zone.clanker.report.model.RelationshipKind
import zone.clanker.report.model.SymbolKind
import zone.clanker.report.model.WorkspaceDeclarationEvidencePage
import zone.clanker.report.model.WorkspaceDeclarationEvidenceRequest
import zone.clanker.report.model.WorkspaceGraphRequest
import zone.clanker.report.model.WorkspaceGraphSlice
import zone.clanker.report.model.WorkspaceRelationshipOccurrencePage
import zone.clanker.report.model.WorkspaceRelationshipOccurrenceRequest
import zone.clanker.report.model.WorkspaceReverseUsagePage
import zone.clanker.report.model.WorkspaceReverseUsageRequest
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

class WorkspaceReportIndex private constructor(
    private val database: SqliteDatabase,
    private val importOptions: IndexImportOptions,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    @Synchronized
    fun indexSite(
        workspaceId: String,
        siteRoot: Path,
    ): IndexResult {
        ensureOpen()
        require(workspaceId.isNotBlank()) { "Index workspace ID must not be blank" }
        return importWorkspaceSite(database, workspaceId, siteRoot, importOptions)
    }

    fun activeGeneration(workspaceId: String): IndexedGeneration? {
        ensureOpen()
        require(workspaceId.isNotBlank()) { "Index workspace ID must not be blank" }
        return readActiveGeneration(database, workspaceId)
    }

    fun searchSymbols(
        workspaceId: String,
        query: String,
        scope: IndexScope = IndexScope(),
        kinds: Set<SymbolKind> = emptySet(),
        limit: Int = DEFAULT_SEARCH_LIMIT,
    ): List<SymbolSearchResult> {
        ensureOpen()
        require(workspaceId.isNotBlank()) { "Index workspace ID must not be blank" }
        require(limit in 1..MAX_SEARCH_LIMIT) { "Symbol search limit must be between 1 and $MAX_SEARCH_LIMIT" }
        return database.querySymbols(workspaceId, query, scope, kinds, limit)
    }

    fun relationshipSummary(
        workspaceId: String,
        scope: IndexScope = IndexScope(),
        kinds: Set<RelationshipKind> = emptySet(),
    ): List<RelationshipSummary> {
        ensureOpen()
        require(workspaceId.isNotBlank()) { "Index workspace ID must not be blank" }
        return database.queryRelationshipSummary(workspaceId, scope, kinds)
    }

    fun findingSummary(
        workspaceId: String,
        scope: IndexScope = IndexScope(),
        severities: Set<FindingSeverity> = emptySet(),
    ): List<FindingSummary> {
        ensureOpen()
        require(workspaceId.isNotBlank()) { "Index workspace ID must not be blank" }
        return database.queryFindingSummary(workspaceId, scope, severities)
    }

    fun graphSlice(
        workspaceId: String,
        request: WorkspaceGraphRequest,
        cancellation: WorkspaceGraphCancellation = WorkspaceGraphCancellation.THREAD_INTERRUPTION,
    ): WorkspaceGraphSlice {
        ensureOpen()
        require(workspaceId.isNotBlank()) { "Index workspace ID must not be blank" }
        return database.queryGraphSlice(workspaceId, request, cancellation)
    }

    fun declarationEvidence(
        workspaceId: String,
        request: WorkspaceDeclarationEvidenceRequest,
    ): WorkspaceDeclarationEvidencePage {
        ensureOpen()
        require(workspaceId.isNotBlank()) { "Index workspace ID must not be blank" }
        return database.queryDeclarationEvidence(workspaceId, request)
    }

    fun reverseUsages(
        workspaceId: String,
        request: WorkspaceReverseUsageRequest,
    ): WorkspaceReverseUsagePage {
        ensureOpen()
        require(workspaceId.isNotBlank()) { "Index workspace ID must not be blank" }
        return database.queryReverseUsages(workspaceId, request)
    }

    fun relationshipOccurrences(
        workspaceId: String,
        request: WorkspaceRelationshipOccurrenceRequest,
    ): WorkspaceRelationshipOccurrencePage {
        ensureOpen()
        require(workspaceId.isNotBlank()) { "Index workspace ID must not be blank" }
        return database.queryRelationshipOccurrences(workspaceId, request)
    }

    @Synchronized
    fun remove(workspaceId: String): Boolean {
        ensureOpen()
        require(workspaceId.isNotBlank()) { "Index workspace ID must not be blank" }
        return removeWorkspace(database, workspaceId)
    }

    override fun close() {
        closed.set(true)
    }

    private fun ensureOpen() {
        check(!closed.get()) { "Workspace report index is closed" }
    }

    companion object {
        const val DEFAULT_SEARCH_LIMIT: Int = 50
        const val MAX_SEARCH_LIMIT: Int = 500

        @JvmStatic
        fun open(databaseFile: Path): WorkspaceReportIndex = open(databaseFile, IndexImportOptions())

        @JvmStatic
        fun open(
            databaseFile: Path,
            importOptions: IndexImportOptions,
        ): WorkspaceReportIndex {
            val path = databaseFile.toAbsolutePath().normalize()
            require(path.fileName != null) { "SQLite index path must identify a file" }
            path.parent?.let { parent -> Files.createDirectories(parent) }
            val database = SqliteDatabase(path).also { indexDatabase -> indexDatabase.initialize() }
            return WorkspaceReportIndex(database, importOptions)
        }
    }
}
