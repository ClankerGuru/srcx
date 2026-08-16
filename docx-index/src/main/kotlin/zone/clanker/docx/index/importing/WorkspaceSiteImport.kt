package zone.clanker.docx.index.importing

import zone.clanker.docx.index.IndexImportOptions
import zone.clanker.docx.index.IndexResult
import zone.clanker.docx.index.IndexedGeneration
import zone.clanker.docx.index.database.SqliteDatabase
import zone.clanker.docx.index.database.queryOne
import zone.clanker.docx.index.database.update
import zone.clanker.docx.index.generation.IndexGenerationKey
import zone.clanker.docx.index.generation.activateGeneration
import zone.clanker.docx.index.generation.markProjectImported
import zone.clanker.docx.index.generation.prepareGeneration
import zone.clanker.docx.index.generation.readActiveGeneration
import zone.clanker.report.model.ProjectGraphShard
import zone.clanker.report.model.ProjectShardReference
import zone.clanker.report.model.WorkspaceSiteJson
import zone.clanker.report.model.WorkspaceSiteManifest
import zone.clanker.report.model.WorkspaceSummaryShard
import java.nio.file.Files
import java.nio.file.Path

internal fun importWorkspaceSite(
    database: SqliteDatabase,
    workspaceId: String,
    siteRoot: Path,
    options: IndexImportOptions,
): IndexResult {
    val root = normalizedSiteRoot(siteRoot)
    val manifest = readManifest(root)
    val summary = readSummary(root, manifest)
    require(manifest.projectShards == summary.projectShards) {
        "DOCX manifest and workspace summary project catalogs disagree"
    }
    val current = readActiveGeneration(database, workspaceId)
    if (current?.generationId == manifest.generationId) {
        requireCurrentIdentity(current, summary)
        if (hasMaterializedGraph(database, workspaceId, manifest.generationId)) {
            return IndexResult(current, alreadyCurrent = true)
        }
        discardUnmaterializedGeneration(database, workspaceId, manifest.generationId)
    }

    val key = IndexGenerationKey(workspaceId, manifest.generationId)
    val ownership = WorkspaceOwnership(summary)
    prepareGeneration(database, key, summary)
    consumeDecodedInOrder(
        inputs = manifest.projectShards,
        options = options,
        decode = { reference -> readProjectShard(root, reference) },
        consume = { _, shard -> importProjectShard(database, key, ownership, shard) },
    )
    materializeWorkspaceGraph(database, key, summary)
    return IndexResult(activateGeneration(database, key), alreadyCurrent = false)
}

private fun hasMaterializedGraph(
    database: SqliteDatabase,
    workspaceId: String,
    generationId: String,
): Boolean =
    database.read { connection ->
        connection.queryOne(
            """
            SELECT EXISTS (
                SELECT 1 FROM graph_nodes
                WHERE workspace_id = ? AND generation_id = ? AND kind = 'WORKSPACE'
            )
            """.trimIndent(),
            listOf(workspaceId, generationId),
        ) { result -> result.getInt(1) != 0 } ?: false
    }

private fun discardUnmaterializedGeneration(
    database: SqliteDatabase,
    workspaceId: String,
    generationId: String,
) {
    val values = listOf(workspaceId, generationId)
    database.transaction { connection ->
        connection.update(
            "DELETE FROM active_workspace_generations WHERE workspace_id = ? AND generation_id = ?",
            values,
        )
        connection.update(
            "DELETE FROM symbol_fts WHERE workspace_id = ? AND generation_id = ?",
            values,
        )
        connection.update(
            "DELETE FROM workspace_generations WHERE workspace_id = ? AND generation_id = ?",
            values,
        )
    }
}

private fun readProjectShard(
    root: Path,
    reference: ProjectShardReference,
): ProjectGraphShard {
    val shard = WorkspaceSiteJson.decodeProject(Files.readString(resolveSiteFile(root, reference.file)))
    require(shard.projectId == reference.projectId) {
        "DOCX project shard identity does not match its manifest: ${reference.projectId}"
    }
    return shard
}

private fun importProjectShard(
    database: SqliteDatabase,
    key: IndexGenerationKey,
    ownership: WorkspaceOwnership,
    shard: ProjectGraphShard,
) {
    database.transaction { connection ->
        insertProjectShard(connection, key, ownership, shard)
        markProjectImported(connection, key)
    }
}

private fun readManifest(root: Path): WorkspaceSiteManifest =
    WorkspaceSiteJson.decodeManifest(Files.readString(resolveSiteFile(root, MANIFEST_PATH)))

private fun readSummary(
    root: Path,
    manifest: WorkspaceSiteManifest,
): WorkspaceSummaryShard =
    WorkspaceSiteJson.decodeWorkspace(Files.readString(resolveSiteFile(root, manifest.workspaceFile)))

private fun normalizedSiteRoot(siteRoot: Path): Path {
    val root = siteRoot.toAbsolutePath().normalize()
    require(Files.isDirectory(root)) { "DOCX site root is not a directory: $root" }
    return root
}

private fun resolveSiteFile(
    root: Path,
    relativePath: String,
): Path {
    val file = root.resolve(relativePath).normalize()
    require(file.startsWith(root)) { "DOCX site file escapes its root: $relativePath" }
    require(Files.isRegularFile(file)) { "DOCX site file is missing: $relativePath" }
    return file
}

private fun requireCurrentIdentity(
    current: IndexedGeneration,
    summary: WorkspaceSummaryShard,
) {
    require(current.sourceWorkspaceId == summary.workspace.id) {
        "Active generation source workspace does not match the generated site"
    }
    require(current.workspaceName == summary.workspace.name) {
        "Active generation workspace name does not match the generated site"
    }
    require(current.buildCount == summary.builds.size && current.projectCount == summary.projects.size) {
        "Active generation scope counts do not match the generated site"
    }
}

private const val MANIFEST_PATH = "data/manifest.json"
