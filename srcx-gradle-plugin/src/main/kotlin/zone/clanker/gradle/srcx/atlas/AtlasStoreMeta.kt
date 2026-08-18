package zone.clanker.gradle.srcx.atlas

/** One-row overflow-store header written on every Atlas regen. */
data class AtlasStoreMeta(
    val schemaVersion: Int,
    val generatedAt: String,
    val workspace: String,
    val seedLimit: Int,
)
