package zone.clanker.srcx.atlas

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray

/** CBOR evidence payloads shared by the JVM writer and the Wasm reader. */
object AtlasCborRenderer {
    @OptIn(ExperimentalSerializationApi::class)
    private val cbor = Cbor { ignoreUnknownKeys = true }

    @OptIn(ExperimentalSerializationApi::class)
    fun encodeNode(payload: AtlasNodePayload): ByteArray = cbor.encodeToByteArray(payload)

    @OptIn(ExperimentalSerializationApi::class)
    fun decodeNode(bytes: ByteArray): AtlasNodePayload = cbor.decodeFromByteArray(bytes)

    @OptIn(ExperimentalSerializationApi::class)
    fun encodeRelationship(payload: AtlasRelationshipPayload): ByteArray = cbor.encodeToByteArray(payload)

    @OptIn(ExperimentalSerializationApi::class)
    fun decodeRelationship(bytes: ByteArray): AtlasRelationshipPayload = cbor.decodeFromByteArray(bytes)

    @OptIn(ExperimentalSerializationApi::class)
    fun encodeOccurrences(payload: List<AtlasOccurrencePayload>): ByteArray = cbor.encodeToByteArray(payload)

    @OptIn(ExperimentalSerializationApi::class)
    fun decodeOccurrences(bytes: ByteArray): List<AtlasOccurrencePayload> = cbor.decodeFromByteArray(bytes)
}
