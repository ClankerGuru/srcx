package zone.clanker.gradle.srcx.snapshot

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat

internal fun stableSnapshotId(
    kind: String,
    vararg parts: String,
): String {
    require(kind.isNotBlank()) { "Snapshot ID kind must not be blank" }
    val canonical = parts.joinToString(separator = "") { part -> "${part.length}:$part" }
    val digest =
        MessageDigest
            .getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
    return "$kind:${HexFormat.of().formatHex(digest)}"
}
