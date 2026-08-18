@file:OptIn(ExperimentalJsExport::class, ExperimentalWasmJsInterop::class)

package zone.clanker.srcx.atlas.site

import zone.clanker.srcx.atlas.AtlasDrawSeedRenderer
import zone.clanker.srcx.atlas.AtlasSqliteFileReader
import zone.clanker.srcx.atlas.AtlasStoreSchema

/**
 * Minimal Kotlin/Wasm seed reader: open atlas.sqlite bytes, SELECT seed=1, hand a JS object to D3.
 *
 * Does not JSON.parse a graph string. Persist stays Cut 3.
 */
@JsExport
fun readAtlasSeed(encoded: String) {
    drawSeedBytes(decodeBase64(encoded))
}

@JsExport
fun readAtlasSeedBytes(bytes: JsAny) {
    drawSeedBytes(jsU8ToBytes(bytes))
}

private fun drawSeedBytes(bytes: ByteArray) {
    val seed = AtlasSqliteFileReader(bytes).readSeed()
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

@JsFun(
    "(u8) => { let s = ''; const chunk = 0x8000; for (let i = 0; i < u8.length; i += chunk) { " +
        "s += String.fromCharCode.apply(null, u8.subarray(i, Math.min(i + chunk, u8.length))); } return s; }",
)
private external fun jsU8ToBin(u8: JsAny): String

private fun jsU8ToBytes(u8: JsAny): ByteArray {
    val binary = jsU8ToBin(u8)
    return ByteArray(binary.length) { index -> binary[index].code.toByte() }
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

@JsFun(
    "(data) => { if (typeof window !== 'undefined' && window.srcxAtlasDraw) { " +
        "window.srcxAtlasDraw(document.querySelector('[data-srcx-architecture-graph]'), data); } }",
)
external fun drawAtlasSeed(data: JsAny)
