package zone.clanker.report.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Canonical Kotlin-serialization boundary shared by SRCX producers and DOCX consumers. */
@OptIn(ExperimentalSerializationApi::class)
data object WorkspaceSnapshotJson {
    private val format =
        Json {
            encodeDefaults = true
            explicitNulls = false
            ignoreUnknownKeys = true
            prettyPrint = true
            prettyPrintIndent = "  "
        }

    fun encode(snapshot: WorkspaceSnapshot): String = format.encodeToString(snapshot) + "\n"

    fun decode(content: String): WorkspaceSnapshot = format.decodeFromString(content)
}
