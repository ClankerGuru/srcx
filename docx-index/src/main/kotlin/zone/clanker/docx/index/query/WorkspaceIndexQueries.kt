package zone.clanker.docx.index.query

import zone.clanker.docx.index.FindingSummary
import zone.clanker.docx.index.IndexScope
import zone.clanker.docx.index.RelationshipSummary
import zone.clanker.docx.index.SymbolDeclaration
import zone.clanker.docx.index.SymbolScope
import zone.clanker.docx.index.SymbolSearchResult
import zone.clanker.docx.index.database.SqliteDatabase
import zone.clanker.docx.index.database.query
import zone.clanker.report.model.FindingSeverity
import zone.clanker.report.model.RelationshipKind
import zone.clanker.report.model.SymbolKind
import java.sql.ResultSet

internal fun SqliteDatabase.querySymbols(
    workspaceId: String,
    query: String,
    scope: IndexScope,
    kinds: Set<SymbolKind>,
    limit: Int,
): List<SymbolSearchResult> {
    val searchExpression = query.ftsExpression()
    if (query.isNotBlank() && searchExpression == null) return emptyList()
    val prepared = symbolSearchQuery(workspaceId, searchExpression, scope, kinds, limit)
    return read { connection ->
        connection.query(prepared.sql, prepared.values, ResultSet::symbolSearchResult)
    }
}

internal fun SqliteDatabase.queryRelationshipSummary(
    workspaceId: String,
    scope: IndexScope,
    kinds: Set<RelationshipKind>,
): List<RelationshipSummary> {
    val prepared = relationshipSummaryQuery(workspaceId, scope, kinds)
    return read { connection ->
        connection.query(prepared.sql, prepared.values) { result ->
            RelationshipSummary(
                kind = RelationshipKind.valueOf(result.getString("kind")),
                recordCount = result.getInt("record_count"),
                sourceSymbolCount = result.getInt("source_symbol_count"),
                targetSymbolCount = result.getInt("target_symbol_count"),
                heuristicCount = result.getInt("heuristic_count"),
            )
        }
    }
}

internal fun SqliteDatabase.queryFindingSummary(
    workspaceId: String,
    scope: IndexScope,
    severities: Set<FindingSeverity>,
): List<FindingSummary> {
    val prepared = findingSummaryQuery(workspaceId, scope, severities)
    return read { connection ->
        connection.query(prepared.sql, prepared.values) { result ->
            FindingSummary(
                severity = FindingSeverity.valueOf(result.getString("severity")),
                findingCount = result.getInt("finding_count"),
                projectCount = result.getInt("project_count"),
                fileCount = result.getInt("file_count"),
            )
        }
    }
}

private fun symbolSearchQuery(
    workspaceId: String,
    searchExpression: String?,
    scope: IndexScope,
    kinds: Set<SymbolKind>,
    limit: Int,
): PreparedQuery {
    val scopeFilter = scope.sqlFilter("s")
    val kindFilter = enumFilter("s.kind", kinds.map(SymbolKind::name))
    val ftsJoin =
        if (searchExpression == null) {
            ""
        } else {
            """
            JOIN symbol_fts
              ON symbol_fts.workspace_id = s.workspace_id
             AND symbol_fts.generation_id = s.generation_id
             AND symbol_fts.symbol_id = s.symbol_id
            """.trimIndent()
        }
    val searchFilter = searchExpression?.let { "AND symbol_fts MATCH ?" }.orEmpty()
    val rank = if (searchExpression == null) "s.qualified_name" else "bm25(symbol_fts), s.qualified_name"
    val sql =
        """
        SELECT s.symbol_id, s.build_id, s.project_id, s.source_set_id, s.source_set_name,
               s.file_id, s.file_path, s.name, s.qualified_name, s.package_name,
               s.kind, s.declaration_line
        FROM active_workspace_generations active
        JOIN symbols s
          ON s.workspace_id = active.workspace_id AND s.generation_id = active.generation_id
        $ftsJoin
        WHERE active.workspace_id = ?
          $searchFilter
          ${scopeFilter.clause}
          ${kindFilter.clause}
        ORDER BY $rank, s.symbol_id
        LIMIT ?
        """.trimIndent()
    return PreparedQuery(
        sql = sql,
        values =
            buildList {
                add(workspaceId)
                searchExpression?.let { expression -> add(expression) }
                addAll(scopeFilter.values)
                addAll(kindFilter.values)
                add(limit)
            },
    )
}

private fun relationshipSummaryQuery(
    workspaceId: String,
    scope: IndexScope,
    kinds: Set<RelationshipKind>,
): PreparedQuery {
    val scopeFilter = scope.sqlFilter("r")
    val kindFilter = enumFilter("r.kind", kinds.map(RelationshipKind::name))
    return PreparedQuery(
        sql =
            """
            SELECT r.kind, COUNT(*) AS record_count,
                   COUNT(DISTINCT r.source_symbol_id) AS source_symbol_count,
                   COUNT(DISTINCT r.target_symbol_id) AS target_symbol_count,
                   SUM(CASE WHEN r.evidence = 'HEURISTIC' THEN 1 ELSE 0 END) AS heuristic_count
            FROM active_workspace_generations active
            JOIN relationships r
              ON r.workspace_id = active.workspace_id AND r.generation_id = active.generation_id
            WHERE active.workspace_id = ?
              ${scopeFilter.clause}
              ${kindFilter.clause}
            GROUP BY r.kind
            ORDER BY r.kind
            """.trimIndent(),
        values = listOf(workspaceId) + scopeFilter.values + kindFilter.values,
    )
}

private fun findingSummaryQuery(
    workspaceId: String,
    scope: IndexScope,
    severities: Set<FindingSeverity>,
): PreparedQuery {
    val scopeFilter = scope.sqlFilter("f")
    val severityFilter = enumFilter("f.severity", severities.map(FindingSeverity::name))
    return PreparedQuery(
        sql =
            """
            SELECT f.severity, COUNT(*) AS finding_count,
                   COUNT(DISTINCT f.project_id) AS project_count,
                   COUNT(DISTINCT f.file_id) AS file_count
            FROM active_workspace_generations active
            JOIN findings f
              ON f.workspace_id = active.workspace_id AND f.generation_id = active.generation_id
            WHERE active.workspace_id = ?
              ${scopeFilter.clause}
              ${severityFilter.clause}
            GROUP BY f.severity
            ORDER BY f.severity
            """.trimIndent(),
        values = listOf(workspaceId) + scopeFilter.values + severityFilter.values,
    )
}

private fun IndexScope.sqlFilter(alias: String): SqlFilter {
    require(buildId == null || buildId.isNotBlank()) { "Index build scope must not be blank" }
    require(projectId == null || projectId.isNotBlank()) { "Index project scope must not be blank" }
    require(sourceSetIds.none(String::isBlank)) { "Index source-set scope must not contain blank IDs" }
    val predicates = mutableListOf<String>()
    val values = mutableListOf<String>()
    buildId?.let { id ->
        predicates += "AND $alias.build_id = ?"
        values += id
    }
    projectId?.let { id ->
        predicates += "AND $alias.project_id = ?"
        values += id
    }
    if (sourceSetIds.isNotEmpty()) {
        predicates += "AND $alias.source_set_id IN (${placeholders(sourceSetIds.size)})"
        values += sourceSetIds.sorted()
    }
    return SqlFilter(predicates.joinToString("\n"), values)
}

private fun enumFilter(
    column: String,
    values: List<String>,
): SqlFilter =
    if (values.isEmpty()) {
        SqlFilter("", emptyList())
    } else {
        val sorted = values.sorted()
        SqlFilter("AND $column IN (${placeholders(sorted.size)})", sorted)
    }

private fun String.ftsExpression(): String? {
    val tokens =
        SEARCH_TOKEN
            .findAll(this)
            .map { match -> match.value }
            .take(MAX_SEARCH_TERMS)
            .toList()
    return tokens.takeIf { values -> values.isNotEmpty() }?.joinToString(" AND ") { token -> "\"$token\"*" }
}

private fun ResultSet.symbolSearchResult(): SymbolSearchResult =
    SymbolSearchResult(
        symbolId = getString("symbol_id"),
        scope =
            SymbolScope(
                buildId = getString("build_id"),
                projectId = getString("project_id"),
                sourceSetId = getString("source_set_id"),
                sourceSetName = getString("source_set_name"),
                fileId = getString("file_id"),
                filePath = getString("file_path"),
            ),
        declaration =
            SymbolDeclaration(
                name = getString("name"),
                qualifiedName = getString("qualified_name"),
                packageName = getString("package_name"),
                kind = SymbolKind.valueOf(getString("kind")),
                line = getInt("declaration_line"),
            ),
    )

private fun placeholders(count: Int): String = List(count) { "?" }.joinToString(", ")

private data class PreparedQuery(
    val sql: String,
    val values: List<Any?>,
)

private data class SqlFilter(
    val clause: String,
    val values: List<String>,
)

private val SEARCH_TOKEN = Regex("[\\p{L}\\p{N}_]+")
private const val MAX_SEARCH_TERMS: Int = 12
