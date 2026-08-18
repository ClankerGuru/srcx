package zone.clanker.gradle.srcx.atlas

import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant

/**
 * Replace-writes `.srcx/site/atlas.sqlite` with a single [AtlasStoreSchema.META_TABLE] row.
 *
 * Linear in one header insert. Never appends to an existing file.
 */
class AtlasSqliteWriter {
    fun write(
        siteDirectory: Path,
        workspace: String,
        generatedAt: String = Instant.now().toString(),
        seedLimit: Int = AtlasStoreSchema.SEED_LIMIT,
    ) {
        write(
            siteDirectory,
            AtlasStoreMeta(
                schemaVersion = AtlasStoreSchema.SCHEMA_VERSION,
                generatedAt = generatedAt,
                workspace = workspace,
                seedLimit = seedLimit,
            ),
        )
    }

    fun write(
        siteDirectory: Path,
        meta: AtlasStoreMeta,
    ) {
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
        runCatching { writeFreshDatabase(staging, meta) }
            .onFailure { Files.deleteIfExists(staging) }
            .getOrThrow()
        Files.deleteIfExists(target)
        Files.move(staging, target)
    }

    private fun writeFreshDatabase(
        database: Path,
        meta: AtlasStoreMeta,
    ) {
        runCatching { Class.forName("org.sqlite.JDBC") }.getOrThrow()
        DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(AtlasStoreSchema.CREATE_META)
            }
            connection.prepareStatement(AtlasStoreSchema.INSERT_META).use { statement ->
                statement.setInt(1, meta.schemaVersion)
                statement.setString(2, meta.generatedAt)
                statement.setString(3, meta.workspace)
                statement.setInt(4, meta.seedLimit)
                statement.executeUpdate()
            }
        }
    }
}
