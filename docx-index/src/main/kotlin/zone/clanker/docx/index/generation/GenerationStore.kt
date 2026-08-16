package zone.clanker.docx.index.generation

import zone.clanker.docx.index.IndexedGeneration
import zone.clanker.docx.index.database.SqliteDatabase
import zone.clanker.docx.index.database.bind
import zone.clanker.docx.index.database.executeBatches
import zone.clanker.docx.index.database.executeSql
import zone.clanker.docx.index.database.queryOne
import zone.clanker.docx.index.database.update
import zone.clanker.report.model.BuildEdgeSnapshot
import zone.clanker.report.model.BuildSnapshot
import zone.clanker.report.model.ProjectSnapshot
import zone.clanker.report.model.WorkspaceSummaryShard
import java.sql.Connection

internal fun prepareGeneration(
    database: SqliteDatabase,
    key: IndexGenerationKey,
    summary: WorkspaceSummaryShard,
) {
    database.transaction { connection ->
        connection.update(
            "DELETE FROM symbol_fts WHERE workspace_id = ? AND generation_id = ?",
            key.values,
        )
        connection.update(
            "DELETE FROM workspace_generations WHERE workspace_id = ? AND generation_id = ?",
            key.values,
        )
        connection.insertGeneration(key, summary)
        connection.insertBuilds(key, summary.builds)
        connection.insertProjects(key, summary.projects)
        connection.insertBuildEdges(key, summary.buildEdges)
    }
}

internal fun markProjectImported(
    connection: Connection,
    key: IndexGenerationKey,
) {
    val changed =
        connection.update(
            """
            UPDATE workspace_generations
            SET imported_project_count = imported_project_count + 1
            WHERE workspace_id = ? AND generation_id = ? AND state = 'STAGING'
            """.trimIndent(),
            key.values,
        )
    check(changed == 1) { "Staged DOCX generation disappeared while importing a project shard" }
}

internal fun activateGeneration(
    database: SqliteDatabase,
    key: IndexGenerationKey,
): IndexedGeneration =
    database.transaction { connection ->
        val previousGenerationId = connection.activeGenerationId(key.workspaceId)
        val progress = connection.generationProgress(key)
        check(progress.importedProjects == progress.expectedProjects) {
            "DOCX generation imported ${progress.importedProjects} of ${progress.expectedProjects} project shards"
        }
        val counts = connection.generationCounts(key)
        connection.markReady(key, counts)
        connection.activate(key)
        connection.pruneGenerations(key, previousGenerationId)
        connection.executeSql("PRAGMA optimize")
        checkNotNull(connection.readGeneration(key)) { "Activated DOCX generation could not be read back" }
    }

private fun Connection.insertGeneration(
    key: IndexGenerationKey,
    summary: WorkspaceSummaryShard,
) {
    update(
        """
        INSERT INTO workspace_generations (
            workspace_id, generation_id, source_workspace_id, workspace_name, state,
            expected_project_count, imported_project_count, build_count
        ) VALUES (?, ?, ?, ?, 'STAGING', ?, 0, ?)
        """.trimIndent(),
        key.values +
            listOf(
                summary.workspace.id,
                summary.workspace.name,
                summary.projects.size,
                summary.builds.size,
            ),
    )
}

private fun Connection.insertBuilds(
    key: IndexGenerationKey,
    builds: List<BuildSnapshot>,
) {
    prepareStatement(INSERT_BUILD_SQL).use { statement ->
        statement.executeBatches(builds) { build ->
            bind(key.values + listOf(build.id, build.name, build.kind.name, build.relativePath))
        }
    }
}

private fun Connection.insertProjects(
    key: IndexGenerationKey,
    projects: List<ProjectSnapshot>,
) {
    prepareStatement(INSERT_PROJECT_SQL).use { statement ->
        statement.executeBatches(projects) { project ->
            bind(key.values + listOf(project.id, project.buildId, project.path, project.buildFile))
        }
    }
}

private fun Connection.insertBuildEdges(
    key: IndexGenerationKey,
    edges: List<BuildEdgeSnapshot>,
) {
    prepareStatement(INSERT_BUILD_EDGE_SQL).use { statement ->
        statement.executeBatches(edges) { edge ->
            bind(key.values + listOf(edge.id, edge.sourceBuildId, edge.targetBuildId))
        }
    }
}

private fun Connection.generationProgress(key: IndexGenerationKey): GenerationProgress =
    checkNotNull(
        queryOne(
            """
            SELECT expected_project_count, imported_project_count
            FROM workspace_generations
            WHERE workspace_id = ? AND generation_id = ? AND state = 'STAGING'
            """.trimIndent(),
            key.values,
        ) { result -> GenerationProgress(result.getInt(1), result.getInt(2)) },
    ) { "DOCX generation is not staged for activation" }

private fun Connection.generationCounts(key: IndexGenerationKey): GenerationCounts =
    GenerationCounts(
        sourceSets = countRows("source_sets", key),
        files = countRows("source_files", key),
        symbols = countRows("symbols", key),
        relationships = countRows("relationships", key),
        findings = countRows("findings", key),
    )

private fun Connection.countRows(
    table: String,
    key: IndexGenerationKey,
): Int =
    checkNotNull(
        queryOne(
            "SELECT COUNT(*) FROM $table WHERE workspace_id = ? AND generation_id = ?",
            key.values,
        ) { result -> result.getInt(1) },
    )

private fun Connection.markReady(
    key: IndexGenerationKey,
    counts: GenerationCounts,
) {
    val changed =
        update(
            """
            UPDATE workspace_generations
            SET state = 'READY', source_set_count = ?, file_count = ?, symbol_count = ?,
                relationship_count = ?, finding_count = ?
            WHERE workspace_id = ? AND generation_id = ? AND state = 'STAGING'
            """.trimIndent(),
            counts.values + key.values,
        )
    check(changed == 1) { "Staged DOCX generation could not be marked ready" }
}

private fun Connection.activate(key: IndexGenerationKey) {
    update(
        """
        INSERT INTO active_workspace_generations (workspace_id, generation_id)
        VALUES (?, ?)
        ON CONFLICT (workspace_id) DO UPDATE SET generation_id = excluded.generation_id
        """.trimIndent(),
        key.values,
    )
}

private val IndexGenerationKey.values: List<String>
    get() = listOf(workspaceId, generationId)

private data class GenerationProgress(
    val expectedProjects: Int,
    val importedProjects: Int,
)

private data class GenerationCounts(
    val sourceSets: Int,
    val files: Int,
    val symbols: Int,
    val relationships: Int,
    val findings: Int,
) {
    val values: List<Int>
        get() = listOf(sourceSets, files, symbols, relationships, findings)
}

private const val INSERT_BUILD_SQL =
    """
    INSERT INTO builds (workspace_id, generation_id, build_id, name, kind, relative_path)
    VALUES (?, ?, ?, ?, ?, ?)
    """

private const val INSERT_PROJECT_SQL =
    """
    INSERT INTO projects (workspace_id, generation_id, project_id, build_id, path, build_file)
    VALUES (?, ?, ?, ?, ?, ?)
    """

private const val INSERT_BUILD_EDGE_SQL =
    """
    INSERT INTO build_edges (
        workspace_id, generation_id, edge_id, source_build_id, target_build_id
    ) VALUES (?, ?, ?, ?, ?)
    """
