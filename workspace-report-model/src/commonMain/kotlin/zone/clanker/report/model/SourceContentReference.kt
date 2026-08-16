package zone.clanker.report.model

import kotlinx.serialization.Serializable

/** Content-addressed source body kept outside a project metadata shard. */
@Serializable
data class SourceContentReference(
    val fileId: String,
    val contentHash: String,
    val file: String = path(contentHash),
    val encodedByteSize: Long,
) {
    init {
        requireValidId(fileId, "Source-content file")
        require(CONTENT_HASH.matches(contentHash)) { "Source-content hash must be lowercase SHA-256" }
        requireNormalizedRelativePath(file, "Source-content path")
        require(file == path(contentHash)) { "Source-content path must be derived from its hash" }
        require(encodedByteSize >= 0) { "Source-content byte size must not be negative" }
    }

    companion object {
        fun path(contentHash: String): String = "data/sources/$contentHash.txt"

        private val CONTENT_HASH: Regex = Regex("[0-9a-f]{64}")
    }
}
