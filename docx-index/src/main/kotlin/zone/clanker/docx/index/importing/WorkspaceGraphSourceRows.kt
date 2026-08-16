@file:Suppress("MagicNumber")

package zone.clanker.docx.index.importing

import zone.clanker.docx.index.database.query
import zone.clanker.docx.index.generation.IndexGenerationKey
import zone.clanker.report.model.FindingSeverity
import zone.clanker.report.model.RelationshipKind
import zone.clanker.report.model.SymbolKind
import java.sql.Connection

internal class WorkspaceGraphSourceRows(
    connection: Connection,
    key: IndexGenerationKey,
) {
    val sourceSets: List<GraphSourceSetRow> = connection.graphSourceSets(key)
    val files: List<GraphFileRow> = connection.graphFiles(key)
    val symbols: List<GraphSymbolRow> = connection.graphSymbols(key)
    val findings: List<GraphFindingRow> = connection.graphFindings(key)
    val cycles: List<GraphCycleRow> = connection.graphCycles(key)
    val relationships: List<GraphRelationshipRow> = connection.graphRelationships(key)
}

internal data class GraphSourceSetRow(
    val id: String,
    val projectId: String,
    val name: String,
)

internal data class GraphFileRow(
    val id: String,
    val sourceSetId: String,
    val path: String,
)

internal data class GraphSymbolRow(
    val id: String,
    val fileId: String,
    val name: String,
    val qualifiedName: String,
    val packageName: String,
    val kind: SymbolKind,
)

internal data class GraphFindingRow(
    val id: String,
    val projectId: String,
    val fileId: String?,
    val severity: FindingSeverity,
    val message: String,
    val symbolIds: List<String>,
)

internal data class GraphCycleRow(
    val id: String,
    val projectId: String,
    val symbolIds: List<String>,
)

internal data class GraphRelationshipRow(
    val id: String,
    val sourceSymbolId: String?,
    val sourceFileId: String,
    val targetSymbolId: String,
    val kind: RelationshipKind,
)

private fun Connection.graphSourceSets(key: IndexGenerationKey): List<GraphSourceSetRow> =
    query(
        """
        SELECT source_set_id, project_id, name
        FROM source_sets
        WHERE workspace_id = ? AND generation_id = ?
        ORDER BY source_set_id
        """.trimIndent(),
        key.values,
    ) { result -> GraphSourceSetRow(result.getString(1), result.getString(2), result.getString(3)) }

private fun Connection.graphFiles(key: IndexGenerationKey): List<GraphFileRow> =
    query(
        """
        SELECT file_id, source_set_id, project_relative_path
        FROM source_files
        WHERE workspace_id = ? AND generation_id = ?
        ORDER BY file_id
        """.trimIndent(),
        key.values,
    ) { result -> GraphFileRow(result.getString(1), result.getString(2), result.getString(3)) }

private fun Connection.graphSymbols(key: IndexGenerationKey): List<GraphSymbolRow> =
    query(
        """
        SELECT symbol_id, file_id, name, qualified_name, package_name, kind
        FROM symbols
        WHERE workspace_id = ? AND generation_id = ?
        ORDER BY symbol_id
        """.trimIndent(),
        key.values,
    ) { result ->
        GraphSymbolRow(
            id = result.getString(1),
            fileId = result.getString(2),
            name = result.getString(3),
            qualifiedName = result.getString(4),
            packageName = result.getString(5),
            kind = SymbolKind.valueOf(result.getString(6)),
        )
    }

private fun Connection.graphFindings(key: IndexGenerationKey): List<GraphFindingRow> {
    val symbolsByFinding =
        query(
            """
            SELECT project_id, finding_id, symbol_id
            FROM graph_finding_symbols
            WHERE workspace_id = ? AND generation_id = ?
            ORDER BY project_id, finding_id, symbol_id
            """.trimIndent(),
            key.values,
        ) { result -> GraphFindingSymbolRow(result.getString(1), result.getString(2), result.getString(3)) }
            .groupBy({ row -> FindingKey(row.projectId, row.findingId) }, GraphFindingSymbolRow::symbolId)
    return query(
        """
        SELECT project_id, finding_id, file_id, severity, message
        FROM graph_finding_evidence
        WHERE workspace_id = ? AND generation_id = ?
        ORDER BY finding_id, project_id
        """.trimIndent(),
        key.values,
    ) { result ->
        val projectId = result.getString(1)
        val findingId = result.getString(2)
        GraphFindingRow(
            id = findingId,
            projectId = projectId,
            fileId = result.getString(3),
            severity = FindingSeverity.valueOf(result.getString(4)),
            message = result.getString(5),
            symbolIds = symbolsByFinding[FindingKey(projectId, findingId)].orEmpty(),
        )
    }
}

private fun Connection.graphCycles(key: IndexGenerationKey): List<GraphCycleRow> {
    val symbolsByCycle =
        query(
            """
            SELECT cycle_id, symbol_id
            FROM graph_cycle_symbols
            WHERE workspace_id = ? AND generation_id = ?
            ORDER BY cycle_id, position
            """.trimIndent(),
            key.values,
        ) { result -> GraphCycleSymbolRow(result.getString(1), result.getString(2)) }
            .groupBy({ row -> row.cycleId }, GraphCycleSymbolRow::symbolId)
    return query(
        """
        SELECT cycle_id, project_id
        FROM graph_cycles
        WHERE workspace_id = ? AND generation_id = ?
        ORDER BY cycle_id
        """.trimIndent(),
        key.values,
    ) { result ->
        val cycleId = result.getString(1)
        GraphCycleRow(cycleId, result.getString(2), symbolsByCycle.getValue(cycleId))
    }
}

private fun Connection.graphRelationships(key: IndexGenerationKey): List<GraphRelationshipRow> =
    query(
        """
        SELECT relationship_id, source_symbol_id, source_file_id, target_symbol_id, kind
        FROM relationships
        WHERE workspace_id = ? AND generation_id = ?
        ORDER BY relationship_id
        """.trimIndent(),
        key.values,
    ) { result ->
        GraphRelationshipRow(
            id = result.getString(1),
            sourceSymbolId = result.getString(2),
            sourceFileId = result.getString(3),
            targetSymbolId = result.getString(4),
            kind = RelationshipKind.valueOf(result.getString(5)),
        )
    }

private data class FindingKey(
    val projectId: String,
    val findingId: String,
)

private data class GraphFindingSymbolRow(
    val projectId: String,
    val findingId: String,
    val symbolId: String,
)

private data class GraphCycleSymbolRow(
    val cycleId: String,
    val symbolId: String,
)

private val IndexGenerationKey.values: List<String>
    get() = listOf(workspaceId, generationId)
