package zone.clanker.srcx.atlas.site

import zone.clanker.srcx.atlas.AtlasCborRenderer
import zone.clanker.srcx.atlas.AtlasNodeRecord
import zone.clanker.srcx.atlas.AtlasRelationshipRecord
import zone.clanker.srcx.atlas.AtlasSqliteFileReader
import zone.clanker.srcx.atlas.AtlasStoreContents
import zone.clanker.srcx.atlas.AtlasStoreSchema

/**
 * Minimal Kotlin/Wasm seed reader: open atlas.sqlite bytes, SELECT seed=1, hand to the D3 adapter.
 *
 * Does not own hover/click/persist. That is Cut 3.
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
fun readAtlasSeed(encoded: String) {
    val seed = AtlasSqliteFileReader(decodeBase64(encoded)).readSeed()
    require(seed.meta.schemaVersion == AtlasStoreSchema.SCHEMA_VERSION) {
        "atlas.sqlite schema must be ${AtlasStoreSchema.SCHEMA_VERSION}"
    }
    require(seed.meta.seedLimit == AtlasStoreSchema.SEED_LIMIT) {
        "atlas.sqlite seed_limit must be ${AtlasStoreSchema.SEED_LIMIT}"
    }
    drawAtlasSeed(encodeSeed(seed))
}

fun main() {
    // atlas-draw.js fetches atlas.sqlite and calls readAtlasSeed.
}

private fun encodeSeed(seed: AtlasStoreContents): String {
    val files = seed.nodes.filter { node -> node.entity == AtlasStoreSchema.ENTITY_FILE }
    val symbols = seed.nodes.filter { node -> node.entity == AtlasStoreSchema.ENTITY_SYMBOL }
    val fileIds = files.map { node -> node.id }.toSet()
    val fileEdges =
        seed.relationships.filter { relationship ->
            relationship.sourceId in fileIds && relationship.targetId in fileIds
        }
    val fileNodes = files.joinToString(prefix = "[", postfix = "]") { node -> encodeFileNode(node) }
    val edges = fileEdges.joinToString(prefix = "[", postfix = "]") { edge -> encodeEdge(edge) }
    val symbolNodes = symbols.joinToString(prefix = "[", postfix = "]") { node -> encodeSymbol(node) }
    val builds =
        files.map { node -> node.build }.distinct().joinToString(prefix = "[", postfix = "]") { name ->
            """{"name":${jsonString(name)}}"""
        }
    return """{"workspace":${jsonString(seed.meta.workspace)},"defaultView":"files",""" +
        """"nodeLimit":${seed.meta.seedLimit},"fileNodeLimit":${seed.meta.seedLimit},""" +
        """"fileNodes":$fileNodes,"fileEdges":$edges,"nodes":$symbolNodes,"edges":[],""" +
        """"builds":$builds,"sourceFiles":$fileNodes,""" +
        """"availableNodes":[],"availableEdges":[],"availableFileNodes":[],"availableFileEdges":[]}"""
}

private fun encodeFileNode(node: AtlasNodeRecord): String {
    val payload = runCatching { AtlasCborRenderer.decodeNode(node.payload) }.getOrNull()
    val symbolsJson =
        (payload?.symbols ?: emptyList()).joinToString(prefix = "[", postfix = "]") { symbol ->
            """{"id":${jsonString(symbol.id)},"name":${jsonString(symbol.name)},"kind":${jsonString(symbol.kind)},""" +
                """"declarationSemantic":${jsonString(symbol.semantic)},"line":${symbol.line}}"""
        }
    return """{"id":${jsonString(node.id)},"name":${jsonString(node.name)},"path":${jsonString(node.path)},""" +
        """"build":${jsonString(node.build)},"project":${jsonString(node.project)},""" +
        """"sourceSet":${jsonString(node.sourceSet)},"symbols":$symbolsJson,""" +
        """"content":${jsonString(payload?.content ?: "")}}"""
}

private fun encodeSymbol(node: AtlasNodeRecord): String =
    """{"id":${jsonString(node.id)},"name":${jsonString(node.name)},"build":${jsonString(node.build)},""" +
        """"project":${jsonString(node.project)},"sourceSet":${jsonString(node.sourceSet)},""" +
        """"file":${jsonString(node.path)},"line":${node.line},"kind":${jsonString(node.kind)}}"""

private fun encodeEdge(edge: AtlasRelationshipRecord): String =
    """{"id":${jsonString(edge.id)},"source":${jsonString(edge.sourceId)},"target":${jsonString(edge.targetId)},""" +
        """"kind":${jsonString(edge.kind)},"recordCount":${edge.recordCount}}"""

private fun jsonString(value: String): String =
    buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
        append('"')
    }

private fun decodeBase64(encoded: String): ByteArray {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    val padding = encoded.count { char -> char == '=' }
    val clean = encoded.filter { char -> char != '=' && !char.isWhitespace() }
    val padded = clean + "A".repeat((4 - clean.length % 4) % 4)
    val out = ArrayList<Byte>(padded.length * 3 / 4)
    var index = 0
    while (index < padded.length) {
        val a = alphabet.indexOf(padded[index])
        val b = alphabet.indexOf(padded[index + 1])
        val c = alphabet.indexOf(padded[index + 2])
        val d = alphabet.indexOf(padded[index + 3])
        require(a >= 0 && b >= 0 && c >= 0 && d >= 0) { "invalid base64" }
        val triple = (a shl 18) or (b shl 12) or (c shl 6) or d
        out += ((triple shr 16) and 0xFF).toByte()
        out += ((triple shr 8) and 0xFF).toByte()
        out += (triple and 0xFF).toByte()
        index += 4
    }
    return out.dropLast(padding).toByteArray()
}

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun(
    "(json) => { if (typeof window !== 'undefined' && window.srcxAtlasDraw) { " +
        "window.srcxAtlasDraw(document.querySelector('[data-srcx-architecture-graph]'), JSON.parse(json)); } }",
)
external fun drawAtlasSeed(json: String)
