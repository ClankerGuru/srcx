package zone.clanker.docx.index

import zone.clanker.report.model.FindingSeverity
import zone.clanker.report.model.RelationshipKind
import zone.clanker.report.model.SymbolKind

data class IndexImportOptions(
    val parallelism: Int = defaultImportParallelism(),
) {
    init {
        require(parallelism in 1..MAX_PARALLELISM) {
            "Index import parallelism must be between 1 and $MAX_PARALLELISM"
        }
    }

    internal val decodeWindowSize: Int
        get() = parallelism

    companion object {
        const val MAX_PARALLELISM: Int = 8
    }
}

data class IndexedGeneration(
    val workspaceId: String,
    val sourceWorkspaceId: String,
    val workspaceName: String,
    val generationId: String,
    val scopes: IndexedScopeCounts,
    val records: IndexedRecordCounts,
) {
    val buildCount: Int get() = scopes.builds
    val projectCount: Int get() = scopes.projects
    val sourceSetCount: Int get() = scopes.sourceSets
    val fileCount: Int get() = records.files
    val symbolCount: Int get() = records.symbols
    val relationshipCount: Int get() = records.relationships
    val findingCount: Int get() = records.findings
}

data class IndexedScopeCounts(
    val builds: Int,
    val projects: Int,
    val sourceSets: Int,
)

data class IndexedRecordCounts(
    val files: Int,
    val symbols: Int,
    val relationships: Int,
    val findings: Int,
)

data class IndexResult(
    val generation: IndexedGeneration,
    val alreadyCurrent: Boolean,
)

data class IndexScope(
    val buildId: String? = null,
    val projectId: String? = null,
    val sourceSetIds: Set<String> = emptySet(),
)

data class SymbolSearchResult(
    val symbolId: String,
    val scope: SymbolScope,
    val declaration: SymbolDeclaration,
) {
    val buildId: String get() = scope.buildId
    val projectId: String get() = scope.projectId
    val sourceSetId: String get() = scope.sourceSetId
    val sourceSetName: String get() = scope.sourceSetName
    val fileId: String get() = scope.fileId
    val filePath: String get() = scope.filePath
    val name: String get() = declaration.name
    val qualifiedName: String get() = declaration.qualifiedName
    val packageName: String get() = declaration.packageName
    val kind: SymbolKind get() = declaration.kind
    val declarationLine: Int get() = declaration.line
}

data class SymbolScope(
    val buildId: String,
    val projectId: String,
    val sourceSetId: String,
    val sourceSetName: String,
    val fileId: String,
    val filePath: String,
)

data class SymbolDeclaration(
    val name: String,
    val qualifiedName: String,
    val packageName: String,
    val kind: SymbolKind,
    val line: Int,
)

data class RelationshipSummary(
    val kind: RelationshipKind,
    val recordCount: Int,
    val sourceSymbolCount: Int,
    val targetSymbolCount: Int,
    val heuristicCount: Int,
)

data class FindingSummary(
    val severity: FindingSeverity,
    val findingCount: Int,
    val projectCount: Int,
    val fileCount: Int,
)

fun interface WorkspaceGraphCancellation {
    fun isCancelled(): Boolean

    companion object {
        val THREAD_INTERRUPTION: WorkspaceGraphCancellation =
            WorkspaceGraphCancellation { Thread.currentThread().isInterrupted }
    }
}

private fun defaultImportParallelism(): Int =
    Runtime.getRuntime().availableProcessors().coerceIn(1, DEFAULT_MAX_PARALLELISM)

private const val DEFAULT_MAX_PARALLELISM: Int = 4
