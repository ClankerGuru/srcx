package zone.clanker.srcx.atlas

import kotlinx.serialization.Serializable

/** Replace-written Atlas store contents for one regen. */
data class AtlasStoreContents(
    val meta: AtlasStoreMeta,
    val nodes: List<AtlasNodeRecord>,
    val relationships: List<AtlasRelationshipRecord>,
    val imports: List<AtlasImportRecord>,
)

data class AtlasNodeRecord(
    val id: String,
    val entity: String,
    val seed: Int,
    val build: String,
    val project: String,
    val sourceSet: String,
    val path: String,
    val name: String,
    val line: Int,
    val kind: String,
    val semantic: String,
    val fileId: String,
    val payload: ByteArray,
)

data class AtlasRelationshipRecord(
    val id: String,
    val sourceId: String,
    val targetId: String,
    val kind: String,
    val family: String,
    val recordCount: Int,
    val payload: ByteArray,
)

data class AtlasImportRecord(
    val sourceFileId: String,
    val targetId: String,
    val targetFileId: String,
    val recordCount: Int,
    val payload: ByteArray,
)

@Serializable
data class AtlasOccurrencePayload(
    val file: String,
    val line: Int,
    val evidence: String,
    val context: String,
)

@Serializable
data class AtlasFileSymbolPayload(
    val id: String,
    val name: String,
    val kind: String,
    val semantic: String,
    val line: Int,
)

@Serializable
data class AtlasNodePayload(
    val importance: Int? = null,
    val important: Boolean = false,
    val symbolCount: Int? = null,
    val incomingRecordCount: Int? = null,
    val outgoingRecordCount: Int? = null,
    val internalRecordCount: Int? = null,
    val content: String? = null,
    val symbols: List<AtlasFileSymbolPayload> = emptyList(),
)

@Serializable
data class AtlasRelationshipPayload(
    val occurrences: List<AtlasOccurrencePayload> = emptyList(),
    val crossBuild: Boolean = false,
)
