package zone.clanker.srcx.atlas.site

import kotlinx.coroutines.await
import zone.clanker.srcx.atlas.AtlasDrawSeed
import zone.clanker.srcx.atlas.AtlasDrawSeedRenderer
import zone.clanker.srcx.atlas.AtlasSqliteFileReader
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.Promise

/** HTTP fetch of atlas.sqlite. No sidecar base64. */
object AtlasSqliteFetch {
    suspend fun readSeed(url: String = "atlas.sqlite"): AtlasDrawSeed {
        val bytes = fetchBytes(url)
        return AtlasDrawSeedRenderer.from(AtlasSqliteFileReader(bytes).readSeed())
    }
}

@OptIn(ExperimentalWasmJsInterop::class)
private suspend fun fetchBytes(url: String): ByteArray {
    val buffer = fetchArrayBuffer(url).await<JsAny>()
    return arrayBufferToBytes(buffer)
}

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun(
    "(url) => fetch(url).then((response) => { " +
        "if (!response.ok) throw new Error('fetch ' + url + ' -> ' + response.status); " +
        "return response.arrayBuffer(); })",
)
private external fun fetchArrayBuffer(url: String): Promise<JsAny>

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun(
    "(buf) => { const u8 = new Uint8Array(buf); let s = ''; const chunk = 0x8000; " +
        "for (let i = 0; i < u8.length; i += chunk) { " +
        "s += String.fromCharCode.apply(null, u8.subarray(i, Math.min(i + chunk, u8.length))); } " +
        "return s; }",
)
private external fun arrayBufferToBin(buf: JsAny): String

private fun arrayBufferToBytes(buf: JsAny): ByteArray {
    val binary = arrayBufferToBin(buf)
    return ByteArray(binary.length) { index -> binary[index].code.toByte() }
}
