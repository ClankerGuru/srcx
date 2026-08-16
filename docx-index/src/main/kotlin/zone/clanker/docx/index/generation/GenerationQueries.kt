package zone.clanker.docx.index.generation

import zone.clanker.docx.index.IndexedGeneration
import zone.clanker.docx.index.IndexedRecordCounts
import zone.clanker.docx.index.IndexedScopeCounts
import zone.clanker.docx.index.database.SqliteDatabase
import zone.clanker.docx.index.database.queryOne
import zone.clanker.docx.index.database.update
import java.sql.Connection
import java.sql.ResultSet

internal fun readActiveGeneration(
    database: SqliteDatabase,
    workspaceId: String,
): IndexedGeneration? =
    database.read { connection ->
        connection.queryOne(
            ACTIVE_GENERATION_SQL,
            listOf(workspaceId),
            ResultSet::indexedGeneration,
        )
    }

internal fun removeWorkspace(
    database: SqliteDatabase,
    workspaceId: String,
): Boolean =
    database.transaction { connection ->
        val existed =
            connection.queryOne(
                "SELECT COUNT(*) FROM workspace_generations WHERE workspace_id = ?",
                listOf(workspaceId),
            ) { result -> result.getInt(1) } != 0
        connection.update("DELETE FROM symbol_fts WHERE workspace_id = ?", listOf(workspaceId))
        connection.update("DELETE FROM active_workspace_generations WHERE workspace_id = ?", listOf(workspaceId))
        connection.update("DELETE FROM workspace_generations WHERE workspace_id = ?", listOf(workspaceId))
        existed
    }

internal fun Connection.readGeneration(key: IndexGenerationKey): IndexedGeneration? =
    queryOne(GENERATION_SQL, key.values, ResultSet::indexedGeneration)

private fun ResultSet.indexedGeneration(): IndexedGeneration =
    IndexedGeneration(
        workspaceId = getString("workspace_id"),
        sourceWorkspaceId = getString("source_workspace_id"),
        workspaceName = getString("workspace_name"),
        generationId = getString("generation_id"),
        scopes =
            IndexedScopeCounts(
                builds = getInt("build_count"),
                projects = getInt("project_count"),
                sourceSets = getInt("source_set_count"),
            ),
        records =
            IndexedRecordCounts(
                files = getInt("file_count"),
                symbols = getInt("symbol_count"),
                relationships = getInt("relationship_count"),
                findings = getInt("finding_count"),
            ),
    )

private val IndexGenerationKey.values: List<String>
    get() = listOf(workspaceId, generationId)

private const val GENERATION_COLUMNS =
    """
    g.workspace_id, g.source_workspace_id, g.workspace_name, g.generation_id,
    g.build_count, g.expected_project_count AS project_count, g.source_set_count,
    g.file_count, g.symbol_count, g.relationship_count, g.finding_count
    """

private const val GENERATION_SQL =
    """
    SELECT $GENERATION_COLUMNS
    FROM workspace_generations g
    WHERE g.workspace_id = ? AND g.generation_id = ? AND g.state = 'READY'
    """

private const val ACTIVE_GENERATION_SQL =
    """
    SELECT $GENERATION_COLUMNS
    FROM active_workspace_generations active
    JOIN workspace_generations g
      ON g.workspace_id = active.workspace_id AND g.generation_id = active.generation_id
    WHERE active.workspace_id = ? AND g.state = 'READY'
    """
