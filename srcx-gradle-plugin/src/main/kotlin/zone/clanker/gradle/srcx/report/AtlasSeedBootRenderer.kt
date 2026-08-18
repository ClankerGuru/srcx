package zone.clanker.gradle.srcx.report

import java.util.Base64

/** Classic-script Wasm boot so file:// open of index.html does not need instantiateStreaming. */
class AtlasSeedBootRenderer {
    fun wasmBytesScript(wasm: ByteArray): String =
        "window.srcxAtlasWasmBase64 = \"${Base64.getEncoder().encodeToString(wasm)}\";\n"

    fun sqliteBytesScript(sqlite: ByteArray): String =
        "window.srcxAtlasSqliteBase64 = \"${Base64.getEncoder().encodeToString(sqlite)}\";\n"

    fun classicLoader(uninstantiatedSource: String): String {
        val withoutExport =
            uninstantiatedSource.replace("export async function instantiate", "async function instantiate")
        val withoutMeta = WITHOUT_IMPORT_META.replace(withoutExport, "undefined")
        val browserInstantiate =
            """
            if (isBrowser) {
              const wasmBuffer = srcxDecodeBase64(window.srcxAtlasWasmBase64);
              wasmInstance = (await WebAssembly.instantiate(wasmBuffer, importObject, { builtins: [''] })).instance;
            }
            """.trimIndent()
        val withBrowser = BROWSER_INSTANTIATE.replace(withoutMeta, browserInstantiate)
        return buildString {
            appendLine(withBrowser.trim())
            appendLine()
            appendLine(DECODE_BASE64)
            appendLine()
            appendLine(
                """
                instantiate({}).then(function (result) {
                  window.srcxAtlasReadSeed = result.exports.readAtlasSeed;
                  window.srcxAtlasReadSeedBytes = result.exports.readAtlasSeedBytes;
                  if (window.srcxAtlasBoot) window.srcxAtlasBoot();
                }).catch(function (error) {
                  console.error("SRCX atlas wasm failed", error);
                });
                """.trimIndent(),
            )
        }
    }

    private companion object {
        val WITHOUT_IMPORT_META = Regex("import\\.meta(?:\\.resolve|\\.url)?")
        val BROWSER_INSTANTIATE =
            Regex(
                """if \(isBrowser\) \{\s*wasmInstance = \(await WebAssembly\.instantiateStreaming\([\s\S]*?\)\)\.instance;\s*\}""",
            )
        const val DECODE_BASE64 =
            """
function srcxDecodeBase64(encoded) {
  const binary = atob(encoded);
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index += 1) bytes[index] = binary.charCodeAt(index);
  return bytes;
}
"""
    }
}
