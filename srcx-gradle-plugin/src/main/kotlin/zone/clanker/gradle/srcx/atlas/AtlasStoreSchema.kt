package zone.clanker.gradle.srcx.atlas

/**
 * Shared Atlas overflow-store contract for the JVM writer and the later Wasm reader.
 *
 * Cut 1 ships only [META_TABLE]. Imports stay empty until the next cut.
 */
object AtlasStoreSchema {
    const val FILE_NAME: String = "atlas.sqlite"
    const val SCHEMA_VERSION: Int = 1
    const val SEED_LIMIT: Int = 42
    const val META_TABLE: String = "meta"
    const val CREATE_META: String =
        "CREATE TABLE meta (" +
            "schema_version INTEGER NOT NULL, " +
            "generated_at TEXT NOT NULL, " +
            "workspace TEXT NOT NULL, " +
            "seed_limit INTEGER NOT NULL" +
            ")"
    const val INSERT_META: String =
        "INSERT INTO meta (schema_version, generated_at, workspace, seed_limit) VALUES (?, ?, ?, ?)"
}
