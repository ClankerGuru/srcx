package zone.clanker.report.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Canonical serializer for the generation search catalog and its bounded prefix shards. */
@OptIn(ExperimentalSerializationApi::class)
data object WorkspaceSearchJson {
    private val format =
        Json {
            encodeDefaults = true
            explicitNulls = false
            ignoreUnknownKeys = true
            prettyPrint = true
            prettyPrintIndent = "  "
        }

    fun encodeCatalog(catalog: WorkspaceSearchCatalog): String = format.encodeToString(catalog) + "\n"

    fun decodeCatalog(content: String): WorkspaceSearchCatalog = format.decodeFromString(content)

    fun encodeShard(shard: WorkspaceSearchShard): String = format.encodeToString(shard) + "\n"

    fun decodeShard(content: String): WorkspaceSearchShard = format.decodeFromString(content)
}
