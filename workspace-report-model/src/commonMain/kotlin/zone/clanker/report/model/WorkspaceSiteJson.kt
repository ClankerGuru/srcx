package zone.clanker.report.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Canonical serializer for the static site's manifest and lazy shards. */
@OptIn(ExperimentalSerializationApi::class)
data object WorkspaceSiteJson {
    private val format =
        Json {
            encodeDefaults = true
            explicitNulls = false
            ignoreUnknownKeys = true
            prettyPrint = true
            prettyPrintIndent = "  "
        }

    fun encodeManifest(manifest: WorkspaceSiteManifest): String = format.encodeToString(manifest) + "\n"

    fun decodeManifest(content: String): WorkspaceSiteManifest = format.decodeFromString(content)

    fun encodeWorkspace(summary: WorkspaceSummaryShard): String = format.encodeToString(summary) + "\n"

    fun decodeWorkspace(content: String): WorkspaceSummaryShard = format.decodeFromString(content)

    fun encodeDashboard(dashboard: WorkspaceDashboardShard): String = format.encodeToString(dashboard) + "\n"

    fun decodeDashboard(content: String): WorkspaceDashboardShard = format.decodeFromString(content)

    fun encodeAtlasFrame(frame: AtlasFrame): String = format.encodeToString(frame) + "\n"

    fun decodeAtlasFrame(content: String): AtlasFrame = format.decodeFromString(content)

    fun encodeAtlasOverviews(overviews: WorkspaceAtlasOverviewShard): String =
        format.encodeToString(overviews) + "\n"

    fun decodeAtlasOverviews(content: String): WorkspaceAtlasOverviewShard = format.decodeFromString(content)

    fun encodeProject(shard: ProjectGraphShard): String = format.encodeToString(shard) + "\n"

    fun decodeProject(content: String): ProjectGraphShard = format.decodeFromString(content)

    fun encodeStatus(status: WorkspaceSiteStatus): String = format.encodeToString(status) + "\n"

    fun decodeStatus(content: String): WorkspaceSiteStatus = format.decodeFromString(content)
}
