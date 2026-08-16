package zone.clanker.report.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object WorkspaceGraphJson {
    private val codec =
        Json {
            encodeDefaults = true
            ignoreUnknownKeys = false
        }

    fun encodeSlice(slice: WorkspaceGraphSlice): String = codec.encodeToString(slice)

    fun decodeSlice(value: String): WorkspaceGraphSlice = codec.decodeFromString(value)

    fun encodeRequest(request: WorkspaceGraphRequest): String = codec.encodeToString(request)

    fun decodeRequest(value: String): WorkspaceGraphRequest = codec.decodeFromString(value)
}
