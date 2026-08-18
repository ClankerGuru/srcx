package zone.clanker.srcx.atlas

/** Shared Atlas overflow-store contract for the JVM writer and the Wasm seed reader. */
object AtlasStoreSchema {
    const val FILE_NAME: String = "atlas.sqlite"
    const val SCHEMA_VERSION: Int = 2
    const val SEED_LIMIT: Int = 42
    const val META_TABLE: String = "meta"
    const val NODES_TABLE: String = "nodes"
    const val RELATIONSHIPS_TABLE: String = "relationships"
    const val IMPORTS_TABLE: String = "imports"
    const val ENTITY_FILE: String = "file"
    const val ENTITY_SYMBOL: String = "symbol"
    const val FAMILY_NON_IMPORT: String = "non_import"
    const val RELATIONSHIP_ID_SEPARATOR: Char = '\u0000'

    const val CREATE_META: String =
        "CREATE TABLE meta (" +
            "schema_version INTEGER NOT NULL, " +
            "generated_at TEXT NOT NULL, " +
            "workspace TEXT NOT NULL, " +
            "seed_limit INTEGER NOT NULL" +
            ")"
    const val INSERT_META: String =
        "INSERT INTO meta (schema_version, generated_at, workspace, seed_limit) VALUES (?, ?, ?, ?)"

    const val CREATE_NODES: String =
        "CREATE TABLE nodes (" +
            "id TEXT PRIMARY KEY, " +
            "entity TEXT NOT NULL, " +
            "seed INTEGER NOT NULL, " +
            "build TEXT, " +
            "project TEXT, " +
            "source_set TEXT, " +
            "path TEXT, " +
            "name TEXT, " +
            "line INTEGER, " +
            "kind TEXT, " +
            "semantic TEXT, " +
            "file_id TEXT, " +
            "payload BLOB" +
            ")"
    const val INSERT_NODE: String =
        "INSERT INTO nodes (id, entity, seed, build, project, source_set, path, name, line, kind, semantic, file_id, payload) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"

    const val CREATE_RELATIONSHIPS: String =
        "CREATE TABLE relationships (" +
            "id TEXT PRIMARY KEY, " +
            "source_id TEXT NOT NULL, " +
            "target_id TEXT NOT NULL, " +
            "kind TEXT NOT NULL, " +
            "family TEXT NOT NULL, " +
            "record_count INTEGER NOT NULL, " +
            "payload BLOB" +
            ")"
    const val INSERT_RELATIONSHIP: String =
        "INSERT INTO relationships (id, source_id, target_id, kind, family, record_count, payload) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?)"

    const val CREATE_IMPORTS: String =
        "CREATE TABLE imports (" +
            "source_file_id TEXT NOT NULL, " +
            "target_id TEXT NOT NULL, " +
            "target_file_id TEXT, " +
            "record_count INTEGER NOT NULL, " +
            "payload BLOB" +
            ")"
    const val INSERT_IMPORT: String =
        "INSERT INTO imports (source_file_id, target_id, target_file_id, record_count, payload) VALUES (?, ?, ?, ?, ?)"
    const val CREATE_IMPORTS_TARGET_INDEX: String =
        "CREATE INDEX imports_target_id ON imports (target_id)"
    const val CREATE_IMPORTS_SOURCE_INDEX: String =
        "CREATE INDEX imports_source_file_id ON imports (source_file_id)"

    const val SELECT_SEED_NODES: String = "SELECT * FROM nodes WHERE seed = 1"
    const val SELECT_SEED_RELATIONSHIPS: String = "SELECT * FROM relationships WHERE family = 'non_import'"

    fun relationshipId(
        sourceId: String,
        kind: String,
        targetId: String,
    ): String = sourceId + RELATIONSHIP_ID_SEPARATOR + kind + RELATIONSHIP_ID_SEPARATOR + targetId

    fun fileId(
        build: String,
        project: String,
        sourceSet: String,
        path: String,
    ): String = "file::$build::$project::$sourceSet::$path"
}
