package zone.clanker.docx.index.query

import zone.clanker.docx.index.database.SqliteDatabase
import zone.clanker.docx.index.database.query
import zone.clanker.docx.index.database.queryOne
import zone.clanker.report.model.RelationshipEvidence
import zone.clanker.report.model.RelationshipKind
import zone.clanker.report.model.SourceRangeSnapshot
import zone.clanker.report.model.WorkspaceDeclarationEvidence
import zone.clanker.report.model.WorkspaceDeclarationEvidencePage
import zone.clanker.report.model.WorkspaceDeclarationEvidenceRequest
import zone.clanker.report.model.WorkspaceEvidenceCursor
import zone.clanker.report.model.WorkspaceEvidenceLocation
import zone.clanker.report.model.WorkspaceEvidenceTarget
import zone.clanker.report.model.WorkspaceRelationshipOccurrence
import zone.clanker.report.model.WorkspaceRelationshipOccurrencePage
import zone.clanker.report.model.WorkspaceRelationshipOccurrenceRequest
import zone.clanker.report.model.WorkspaceReverseUsagePage
import zone.clanker.report.model.WorkspaceReverseUsageRequest
import zone.clanker.report.model.usageCategory
import java.sql.Connection
import java.sql.ResultSet

internal fun SqliteDatabase.queryDeclarationEvidence(
    registeredWorkspaceId: String,
    request: WorkspaceDeclarationEvidenceRequest,
): WorkspaceDeclarationEvidencePage =
    snapshot { connection ->
        val generation = connection.requireEvidenceGeneration(registeredWorkspaceId, request.target)
        val declaration =
            connection.queryOne(
                DECLARATION_SQL,
                generation.values + request.symbolId,
                ResultSet::declarationEvidence,
            )
        WorkspaceDeclarationEvidencePage(request.target, request.symbolId, declaration)
    }

internal fun SqliteDatabase.queryReverseUsages(
    registeredWorkspaceId: String,
    request: WorkspaceReverseUsageRequest,
): WorkspaceReverseUsagePage =
    snapshot { connection ->
        val generation = connection.requireEvidenceGeneration(registeredWorkspaceId, request.target)
        val baseValues = generation.values + request.symbolId
        val totalCount = connection.count(REVERSE_USAGE_COUNT_SQL, baseValues)
        val occurrences =
            connection.queryOccurrences(
                query =
                    OccurrenceQuery(
                        forwardSql = REVERSE_USAGE_FORWARD_PAGE_SQL,
                        backwardSql = REVERSE_USAGE_BACKWARD_PAGE_SQL,
                        baseValues = baseValues,
                    ),
                after = request.after,
                before = request.before,
                limit = request.limit,
            )
        WorkspaceReverseUsagePage(
            target = request.target,
            symbolId = request.symbolId,
            totalCount = totalCount,
            occurrences = occurrences.page,
            previousCursor = occurrences.previousCursor,
            nextCursor = occurrences.nextCursor,
        )
    }

internal fun SqliteDatabase.queryRelationshipOccurrences(
    registeredWorkspaceId: String,
    request: WorkspaceRelationshipOccurrenceRequest,
): WorkspaceRelationshipOccurrencePage =
    snapshot { connection ->
        val generation = connection.requireEvidenceGeneration(registeredWorkspaceId, request.target)
        val selector = request.selector
        val baseValues =
            generation.values +
                listOf(
                    selector.sourceNodeId,
                    selector.targetNodeId,
                    selector.kind.name,
                )
        val totalCount = connection.count(RELATION_OCCURRENCE_COUNT_SQL, baseValues)
        val occurrences =
            connection.queryOccurrences(
                query =
                    OccurrenceQuery(
                        forwardSql = RELATION_OCCURRENCE_FORWARD_PAGE_SQL,
                        backwardSql = RELATION_OCCURRENCE_BACKWARD_PAGE_SQL,
                        baseValues = baseValues,
                    ),
                after = request.after,
                before = request.before,
                limit = request.limit,
            )
        WorkspaceRelationshipOccurrencePage(
            target = request.target,
            selector = selector,
            totalCount = totalCount,
            occurrences = occurrences.page,
            previousCursor = occurrences.previousCursor,
            nextCursor = occurrences.nextCursor,
        )
    }

private fun Connection.requireEvidenceGeneration(
    registeredWorkspaceId: String,
    target: WorkspaceEvidenceTarget,
): EvidenceGeneration {
    val generation =
        queryOne(
            """
            SELECT workspace_id, generation_id, source_workspace_id
            FROM workspace_generations
            WHERE workspace_id = ? AND generation_id = ? AND state = 'READY'
            """.trimIndent(),
            listOf(registeredWorkspaceId, target.generationId),
        ) { result ->
            EvidenceGeneration(
                registeredWorkspaceId = result.getString(1),
                generationId = result.getString(2),
                sourceWorkspaceId = result.getString(3),
            )
        }
    requireNotNull(generation) {
        "Workspace evidence generation is not indexed: ${target.generationId}"
    }
    require(generation.sourceWorkspaceId == target.workspaceId) {
        "Workspace evidence request does not target the indexed source workspace"
    }
    return generation
}

private fun Connection.queryOccurrences(
    query: OccurrenceQuery,
    after: WorkspaceEvidenceCursor?,
    before: WorkspaceEvidenceCursor?,
    limit: Int,
): OccurrenceQueryResult {
    require(after == null || before == null) { "Evidence query accepts only one cursor direction" }
    val cursor = before ?: after
    val backwards = before != null
    val candidates =
        query(
            if (backwards) query.backwardSql else query.forwardSql,
            query.baseValues + cursor.cursorValues() + (limit + 1),
            ResultSet::relationshipOccurrence,
        )
    val page = candidates.take(limit).let { records -> if (backwards) records.reversed() else records }
    return OccurrenceQueryResult(
        page = page,
        previousCursor =
            page.firstOrNull()?.cursor?.takeIf { if (backwards) candidates.size > limit else after != null },
        nextCursor =
            page.lastOrNull()?.cursor?.takeIf { backwards || candidates.size > limit },
    )
}

private data class OccurrenceQuery(
    val forwardSql: String,
    val backwardSql: String,
    val baseValues: List<Any?>,
)

private fun Connection.count(
    sql: String,
    values: List<Any?>,
): Long = queryOne(sql, values) { result -> result.getLong(1) } ?: 0

private fun WorkspaceEvidenceCursor?.cursorValues(): List<Any?> =
    listOf(
        this?.filePath,
        this?.filePath,
        this?.filePath,
        this?.line,
        this?.filePath,
        this?.line,
        this?.relationshipId,
    )

private fun ResultSet.declarationEvidence(): WorkspaceDeclarationEvidence {
    val startOffset = getInt("declaration_start_offset").takeUnless { wasNull() }
    val endOffset = getInt("declaration_end_offset_exclusive").takeUnless { wasNull() }
    return WorkspaceDeclarationEvidence(
        symbolId = getString("symbol_id"),
        ownerSymbolId = getString("owner_symbol_id"),
        signature = getString("signature"),
        name = getString("name"),
        qualifiedName = getString("qualified_name"),
        kind = enumValueOf(getString("kind")),
        semantic = enumValueOf(getString("declaration_semantic")),
        location =
            WorkspaceEvidenceLocation(
                buildId = getString("build_id"),
                projectId = getString("project_id"),
                sourceSetId = getString("source_set_id"),
                sourceSetName = getString("source_set_name"),
                fileId = getString("file_id"),
                filePath = getString("file_path"),
                line = getInt("declaration_line"),
                range =
                    if (startOffset == null || endOffset == null) {
                        null
                    } else {
                        SourceRangeSnapshot(startOffset, endOffset)
                    },
            ),
    )
}

private fun ResultSet.relationshipOccurrence(): WorkspaceRelationshipOccurrence {
    val kind = enumValueOf<RelationshipKind>(getString("kind"))
    val startOffset = getInt("occurrence_start_offset").takeUnless { wasNull() }
    val endOffset = getInt("occurrence_end_offset_exclusive").takeUnless { wasNull() }
    return WorkspaceRelationshipOccurrence(
        relationshipId = getString("relationship_id"),
        referenceId = getString("reference_id"),
        sourceSymbolId = getString("source_symbol_id"),
        targetSymbolId = getString("target_symbol_id"),
        kind = kind,
        evidence = enumValueOf<RelationshipEvidence>(getString("evidence")),
        category = kind.usageCategory(),
        location =
            WorkspaceEvidenceLocation(
                buildId = getString("build_id"),
                projectId = getString("project_id"),
                sourceSetId = getString("source_set_id"),
                sourceSetName = getString("source_set_name"),
                fileId = getString("source_file_id"),
                filePath = getString("source_file_path"),
                line = getInt("source_line"),
                range =
                    if (startOffset == null || endOffset == null) {
                        null
                    } else {
                        SourceRangeSnapshot(startOffset, endOffset)
                    },
            ),
        context = getString("source_context"),
    )
}

private data class EvidenceGeneration(
    val registeredWorkspaceId: String,
    val sourceWorkspaceId: String,
    val generationId: String,
) {
    val values: List<String>
        get() = listOf(registeredWorkspaceId, generationId)
}

private data class OccurrenceQueryResult(
    val page: List<WorkspaceRelationshipOccurrence>,
    val previousCursor: WorkspaceEvidenceCursor?,
    val nextCursor: WorkspaceEvidenceCursor?,
)

private const val DECLARATION_SQL =
    """
    SELECT symbol.symbol_id, evidence.owner_symbol_id, evidence.signature,
           symbol.name, symbol.qualified_name, symbol.kind, symbol.declaration_semantic,
           symbol.build_id, symbol.project_id, symbol.source_set_id, symbol.source_set_name,
           symbol.file_id, symbol.file_path, symbol.declaration_line,
           evidence.declaration_start_offset, evidence.declaration_end_offset_exclusive
    FROM symbols symbol
    LEFT JOIN symbol_evidence evidence
      ON evidence.workspace_id = symbol.workspace_id
     AND evidence.generation_id = symbol.generation_id
     AND evidence.symbol_id = symbol.symbol_id
    WHERE symbol.workspace_id = ? AND symbol.generation_id = ? AND symbol.symbol_id = ?
    """

private const val OCCURRENCE_COLUMNS =
    """
    relationship.relationship_id, occurrence.reference_id, relationship.source_symbol_id,
    relationship.target_symbol_id, relationship.kind, relationship.evidence,
    relationship.build_id, relationship.project_id, relationship.source_set_id,
    source_set.name AS source_set_name, occurrence.source_file_id,
    occurrence.source_file_path, occurrence.source_line, occurrence.source_context,
    occurrence_range.start_offset AS occurrence_start_offset,
    occurrence_range.end_offset_exclusive AS occurrence_end_offset_exclusive
    """

private const val REVERSE_USAGE_FROM =
    """
    FROM relationships relationship
    JOIN relationship_occurrences occurrence
      ON occurrence.workspace_id = relationship.workspace_id
     AND occurrence.generation_id = relationship.generation_id
     AND occurrence.relationship_id = relationship.relationship_id
    JOIN source_sets source_set
      ON source_set.workspace_id = relationship.workspace_id
     AND source_set.generation_id = relationship.generation_id
     AND source_set.source_set_id = relationship.source_set_id
    LEFT JOIN relationship_occurrence_ranges occurrence_range
      ON occurrence_range.workspace_id = relationship.workspace_id
     AND occurrence_range.generation_id = relationship.generation_id
     AND occurrence_range.relationship_id = relationship.relationship_id
    WHERE relationship.workspace_id = ? AND relationship.generation_id = ?
      AND relationship.target_symbol_id = ?
    """

private const val REVERSE_USAGE_COUNT_SQL = "SELECT COUNT(*) $REVERSE_USAGE_FROM"

private const val REVERSE_USAGE_FORWARD_PAGE_SQL =
    """
    SELECT $OCCURRENCE_COLUMNS
    $REVERSE_USAGE_FROM
      AND (
          ? IS NULL OR occurrence.source_file_path > ? OR
          (occurrence.source_file_path = ? AND occurrence.source_line > ?) OR
          (
              occurrence.source_file_path = ? AND occurrence.source_line = ? AND
              relationship.relationship_id > ?
          )
      )
    ORDER BY occurrence.source_file_path, occurrence.source_line, relationship.relationship_id
    LIMIT ?
    """

private const val REVERSE_USAGE_BACKWARD_PAGE_SQL =
    """
    SELECT $OCCURRENCE_COLUMNS
    $REVERSE_USAGE_FROM
      AND (
          ? IS NULL OR occurrence.source_file_path < ? OR
          (occurrence.source_file_path = ? AND occurrence.source_line < ?) OR
          (
              occurrence.source_file_path = ? AND occurrence.source_line = ? AND
              relationship.relationship_id < ?
          )
      )
    ORDER BY occurrence.source_file_path DESC, occurrence.source_line DESC, relationship.relationship_id DESC
    LIMIT ?
    """

private const val RELATION_OCCURRENCE_FROM =
    """
    FROM graph_nodes source_anchor
    JOIN graph_node_closure source_closure
      ON source_closure.ancestor_node_key = source_anchor.node_key
    JOIN graph_relation_facts fact
      ON fact.source_node_key = source_closure.descendant_node_key
    JOIN graph_nodes target_anchor
      ON target_anchor.workspace_id = fact.workspace_id
     AND target_anchor.generation_id = fact.generation_id
    JOIN graph_node_closure target_closure
      ON target_closure.ancestor_node_key = target_anchor.node_key
     AND target_closure.descendant_node_key = fact.target_node_key
    JOIN relationships relationship
      ON relationship.workspace_id = fact.workspace_id
     AND relationship.generation_id = fact.generation_id
     AND relationship.relationship_id = fact.fact_id
    JOIN relationship_occurrences occurrence
      ON occurrence.workspace_id = relationship.workspace_id
     AND occurrence.generation_id = relationship.generation_id
     AND occurrence.relationship_id = relationship.relationship_id
    JOIN source_sets source_set
      ON source_set.workspace_id = relationship.workspace_id
     AND source_set.generation_id = relationship.generation_id
     AND source_set.source_set_id = relationship.source_set_id
    LEFT JOIN relationship_occurrence_ranges occurrence_range
      ON occurrence_range.workspace_id = relationship.workspace_id
     AND occurrence_range.generation_id = relationship.generation_id
     AND occurrence_range.relationship_id = relationship.relationship_id
    WHERE fact.workspace_id = ? AND fact.generation_id = ?
      AND source_anchor.semantic_id = ? AND target_anchor.semantic_id = ? AND fact.kind = ?
    """

private const val RELATION_OCCURRENCE_COUNT_SQL = "SELECT COUNT(*) $RELATION_OCCURRENCE_FROM"

private const val RELATION_OCCURRENCE_FORWARD_PAGE_SQL =
    """
    SELECT $OCCURRENCE_COLUMNS
    $RELATION_OCCURRENCE_FROM
      AND (
          ? IS NULL OR occurrence.source_file_path > ? OR
          (occurrence.source_file_path = ? AND occurrence.source_line > ?) OR
          (
              occurrence.source_file_path = ? AND occurrence.source_line = ? AND
              relationship.relationship_id > ?
          )
      )
    ORDER BY occurrence.source_file_path, occurrence.source_line, relationship.relationship_id
    LIMIT ?
    """

private const val RELATION_OCCURRENCE_BACKWARD_PAGE_SQL =
    """
    SELECT $OCCURRENCE_COLUMNS
    $RELATION_OCCURRENCE_FROM
      AND (
          ? IS NULL OR occurrence.source_file_path < ? OR
          (occurrence.source_file_path = ? AND occurrence.source_line < ?) OR
          (
              occurrence.source_file_path = ? AND occurrence.source_line = ? AND
              relationship.relationship_id < ?
          )
      )
    ORDER BY occurrence.source_file_path DESC, occurrence.source_line DESC, relationship.relationship_id DESC
    LIMIT ?
    """
