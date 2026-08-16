package zone.clanker.report.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** The only JSON boundary accepted by the Atlas D3 renderer bridge. */
@OptIn(ExperimentalSerializationApi::class)
data object AtlasFrameJson {
    private val format =
        Json {
            encodeDefaults = true
            explicitNulls = false
            ignoreUnknownKeys = true
        }

    fun encode(frame: AtlasFrame): String = format.encodeToString(frame)

    fun decode(content: String): AtlasFrame = format.decodeFromString(content)
}
