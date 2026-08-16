package zone.clanker.docx.web.atlas

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import zone.clanker.docx.web.atlas.session.AtlasLayoutOverride
import zone.clanker.docx.web.atlas.session.AtlasSemanticId
import zone.clanker.report.model.WorkspaceGraphSlice

internal fun atlasMapPayload(
    slice: WorkspaceGraphSlice,
    layoutOverrides: Map<AtlasSemanticId, AtlasLayoutOverride>,
    automaticExpandedIds: List<AtlasSemanticId>,
): String {
    val encodedSlice = MAP_PAYLOAD_JSON.encodeToJsonElement(WorkspaceGraphSlice.serializer(), slice).jsonObject
    return JsonObject(
        buildMap {
            putAll(encodedSlice)
            put("layoutOverrides", layoutOverridesJson(layoutOverrides))
            put(
                "automaticExpandedIds",
                JsonArray(automaticExpandedIds.map { id -> JsonPrimitive(id.value) }),
            )
        },
    ).toString()
}

private fun layoutOverridesJson(overrides: Map<AtlasSemanticId, AtlasLayoutOverride>): JsonObject =
    buildJsonObject {
        overrides.keys.sortedBy(AtlasSemanticId::value).forEach { id ->
            val override = overrides.getValue(id)
            put(
                id.value,
                buildJsonObject {
                    override.position?.let { position ->
                        put(
                            "position",
                            buildJsonObject {
                                put("x", position.x)
                                put("y", position.y)
                            },
                        )
                    }
                    override.minimumSize?.let { size ->
                        put(
                            "minimumSize",
                            buildJsonObject {
                                put("width", size.width)
                                put("height", size.height)
                            },
                        )
                    }
                },
            )
        }
    }

private val MAP_PAYLOAD_JSON: Json = Json { encodeDefaults = true }
