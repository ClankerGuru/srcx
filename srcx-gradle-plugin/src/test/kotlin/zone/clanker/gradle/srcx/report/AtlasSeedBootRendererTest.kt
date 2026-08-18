package zone.clanker.gradle.srcx.report

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class AtlasSeedBootRendererTest :
    BehaviorSpec({
        given("the generated uninstantiated Wasm loader") {
            val source =
                requireNotNull(
                    AtlasSeedBootRenderer::class.java.getResourceAsStream(
                        "/zone/clanker/gradle/srcx/report/html/wasm/atlas-seed.uninstantiated.mjs",
                    ),
                ).use { stream -> stream.readBytes().toString(Charsets.UTF_8) }

            then("the classic boot does not use instantiateStreaming, import.meta, or JSON.parse") {
                val loader = AtlasSeedBootRenderer().classicLoader(source)
                loader shouldContain "srcxAtlasWasmBase64"
                loader shouldContain "WebAssembly.instantiate(wasmBuffer"
                loader shouldContain "window.srcxAtlasReadSeed"
                loader shouldContain "window.srcxAtlasReadSeedBytes"
                loader shouldContain ".catch("
                loader shouldNotContain "instantiateStreaming"
                loader shouldNotContain "import.meta"
                loader shouldNotContain "export async function"
                loader shouldNotContain "JSON.parse"
            }
        }
    })
