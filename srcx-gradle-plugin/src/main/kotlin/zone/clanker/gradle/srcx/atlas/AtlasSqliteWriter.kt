package zone.clanker.gradle.srcx.atlas

import zone.clanker.srcx.atlas.AtlasImportRecord
import zone.clanker.srcx.atlas.AtlasNodeRecord
import zone.clanker.srcx.atlas.AtlasRelationshipRecord
import zone.clanker.srcx.atlas.AtlasStoreContents
import zone.clanker.srcx.atlas.AtlasStoreMeta
import zone.clanker.srcx.atlas.AtlasStoreSchema
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant

/**
 * Replace-writes `.srcx/site/atlas.sqlite` with schema 2 seed + imports.
 *
 * Linear in seed nodes, seed relationships, and aggregated import rows. Never appends.
 */
class AtlasSqliteWriter {
    fun write(
        siteDirectory: Path,
        workspace: String,
        generatedAt: String = Instant.now().toString(),
        seedLimit: Int = AtlasStoreSchema.SEED_LIMIT,
        nodes: List<AtlasNodeRecord> = emptyList(),
        relationships: List<AtlasRelationshipRecord> = emptyList(),
        imports: List<AtlasImportRecord> = emptyList(),
    ) {
        write(
            siteDirectory,
            AtlasStoreContents(
                meta =
                    AtlasStoreMeta(
                        schemaVersion = AtlasStoreSchema.SCHEMA_VERSION,
                        generatedAt = generatedAt,
                        workspace = workspace,
                        seedLimit = seedLimit,
                    ),
                nodes = nodes,
                relationships = relationships,
                imports = imports,
            ),
        )
    }

    fun write(
        siteDirectory: Path,
        contents: AtlasStoreContents,
    ) {
        val meta = contents.meta
        require(meta.workspace.isNotBlank()) { "workspace must not be blank" }
        require(meta.schemaVersion == AtlasStoreSchema.SCHEMA_VERSION) {
            "schemaVersion must be ${AtlasStoreSchema.SCHEMA_VERSION}"
        }
        require(meta.seedLimit == AtlasStoreSchema.SEED_LIMIT) {
            "seedLimit must be ${AtlasStoreSchema.SEED_LIMIT}"
        }
        require(Files.isDirectory(siteDirectory) || siteDirectory.toFile().mkdirs()) {
            "site directory could not be created: $siteDirectory"
        }
        val target = siteDirectory.resolve(AtlasStoreSchema.FILE_NAME)
        val staging = siteDirectory.resolve(".${AtlasStoreSchema.FILE_NAME}.replace")
        Files.deleteIfExists(staging)
        runCatching { writeFreshDatabase(staging, contents) }
            .onFailure { Files.deleteIfExists(staging) }
            .getOrThrow()
        Files.deleteIfExists(target)
        Files.move(staging, target)
    }

    private fun writeFreshDatabase(
        database: Path,
        contents: AtlasStoreContents,
    ) {
        runCatching { Class.forName("org.sqlite.JDBC") }.getOrThrow()
        DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(AtlasStoreSchema.CREATE_META)
                statement.execute(AtlasStoreSchema.CREATE_NODES)
                statement.execute(AtlasStoreSchema.CREATE_RELATIONSHIPS)
                statement.execute(AtlasStoreSchema.CREATE_IMPORTS)
                statement.execute(AtlasStoreSchema.CREATE_IMPORTS_TARGET_INDEX)
                statement.execute(AtlasStoreSchema.CREATE_IMPORTS_SOURCE_INDEX)
            }
            insertMeta(connection, contents.meta)
            insertNodes(connection, contents.nodes)
            insertRelationships(connection, contents.relationships)
            insertImports(connection, contents.imports)
        }
    }

    private fun insertMeta(
        connection: Connection,
        meta: AtlasStoreMeta,
    ) {
        connection.prepareStatement(AtlasStoreSchema.INSERT_META).use { statement ->
            statement.setInt(1, meta.schemaVersion)
            statement.setString(2, meta.generatedAt)
            statement.setString(3, meta.workspace)
            statement.setInt(4, meta.seedLimit)
            statement.executeUpdate()
        }
    }

    private fun insertNodes(
        connection: Connection,
        nodes: List<AtlasNodeRecord>,
    ) {
        connection.prepareStatement(AtlasStoreSchema.INSERT_NODE).use { statement ->
            nodes.forEach { node ->
                statement.setString(1, node.id)
                statement.setString(2, node.entity)
                statement.setInt(3, node.seed)
                statement.setString(4, node.build)
                statement.setString(5, node.project)
                statement.setString(6, node.sourceSet)
                statement.setString(7, node.path)
                statement.setString(8, node.name)
                statement.setInt(9, node.line)
                statement.setString(10, node.kind)
                statement.setString(11, node.semantic)
                statement.setString(12, node.fileId)
                statement.setBytes(13, node.payload)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun insertRelationships(
        connection: Connection,
        relationships: List<AtlasRelationshipRecord>,
    ) {
        connection.prepareStatement(AtlasStoreSchema.INSERT_RELATIONSHIP).use { statement ->
            relationships.forEach { relationship ->
                statement.setString(1, relationship.id)
                statement.setString(2, relationship.sourceId)
                statement.setString(3, relationship.targetId)
                statement.setString(4, relationship.kind)
                statement.setString(5, relationship.family)
                statement.setInt(6, relationship.recordCount)
                statement.setBytes(7, relationship.payload)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun insertImports(
        connection: Connection,
        imports: List<AtlasImportRecord>,
    ) {
        connection.prepareStatement(AtlasStoreSchema.INSERT_IMPORT).use { statement ->
            imports.forEach { row ->
                statement.setString(1, row.sourceFileId)
                statement.setString(2, row.targetId)
                statement.setString(3, row.targetFileId)
                statement.setInt(4, row.recordCount)
                statement.setBytes(5, row.payload)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }
}
