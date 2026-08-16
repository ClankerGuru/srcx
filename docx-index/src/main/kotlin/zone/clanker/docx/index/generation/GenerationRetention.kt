package zone.clanker.docx.index.generation

import zone.clanker.docx.index.database.queryOne
import zone.clanker.docx.index.database.update
import java.sql.Connection

internal fun Connection.activeGenerationId(workspaceId: String): String? =
    queryOne(
        "SELECT generation_id FROM active_workspace_generations WHERE workspace_id = ?",
        listOf(workspaceId),
    ) { result -> result.getString(1) }

internal fun Connection.pruneGenerations(
    current: IndexGenerationKey,
    previousGenerationId: String?,
) {
    val retainedIds = listOfNotNull(current.generationId, previousGenerationId).distinct()
    val retainedPlaceholders = List(retainedIds.size) { "?" }.joinToString(", ")
    val values = listOf(current.workspaceId) + retainedIds
    update(
        "DELETE FROM symbol_fts WHERE workspace_id = ? AND generation_id NOT IN ($retainedPlaceholders)",
        values,
    )
    update(
        "DELETE FROM workspace_generations WHERE workspace_id = ? AND generation_id NOT IN ($retainedPlaceholders)",
        values,
    )
}
