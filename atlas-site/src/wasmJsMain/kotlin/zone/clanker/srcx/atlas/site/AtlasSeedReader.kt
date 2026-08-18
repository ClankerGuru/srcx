package zone.clanker.srcx.atlas.site

import zone.clanker.srcx.atlas.AtlasDrawSeedRenderer
import zone.clanker.srcx.atlas.AtlasSqliteFileReader
import zone.clanker.srcx.atlas.AtlasStoreSchema

/**
 * Minimal Kotlin/Wasm seed reader: open atlas.sqlite bytes, SELECT seed=1, hand a JS object to D3.
 *
 * Does not JSON.parse a graph string. Does not own hover/click/persist. That is Cut 3.
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
    drawAtlasSeed(AtlasDrawSeedRenderer.from(seed).toJsObject())
}

fun main() {
    // atlas-draw.js loads atlas.sqlite bytes and calls readAtlasSeed.
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
    "(data) => { if (typeof window !== 'undefined' && window.srcxAtlasDraw) { " +
        "window.srcxAtlasDraw(document.querySelector('[data-srcx-architecture-graph]'), data); } }",
)
external fun drawAtlasSeed(data: JsAny)
