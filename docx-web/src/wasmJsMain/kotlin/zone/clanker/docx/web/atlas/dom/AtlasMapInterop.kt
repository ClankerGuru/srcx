package zone.clanker.docx.web.atlas.dom

import org.w3c.dom.HTMLElement
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsString

/** Wasm-safe boundary around the bounded Canvas semantic-map renderer. */
internal object AtlasMapInterop {
    fun mount(
        host: HTMLElement,
        sliceJson: String,
    ) {
        mountAtlasMap(host, sliceJson)
    }

    fun update(
        host: HTMLElement,
        sliceJson: String,
    ) {
        updateAtlasMap(host, sliceJson)
    }

    fun command(
        host: HTMLElement,
        action: String,
    ) {
        commandAtlasMap(host, action)
    }

    @OptIn(ExperimentalWasmJsInterop::class)
    fun snapshot(host: HTMLElement): String = snapshotAtlasMap(host).toString()

    fun destroy(host: HTMLElement) {
        destroyAtlasMap(host)
    }
}

@OptIn(ExperimentalWasmJsInterop::class)
@Suppress("UnusedParameter")
private fun mountAtlasMap(
    host: HTMLElement,
    sliceJson: String,
): Unit =
    js(
        """{
            const bridge = window.docxAtlasMap;
            if (!bridge || typeof bridge.mount !== "function") {
                throw new Error("The bundled DOCX Atlas Canvas renderer is unavailable");
            }
            bridge.mount(host, sliceJson);
        }""",
    )

@OptIn(ExperimentalWasmJsInterop::class)
@Suppress("UnusedParameter")
private fun updateAtlasMap(
    host: HTMLElement,
    sliceJson: String,
): Unit =
    js(
        """{
            const bridge = window.docxAtlasMap;
            if (!bridge || typeof bridge.update !== "function") {
                throw new Error("The bundled DOCX Atlas Canvas renderer cannot update");
            }
            bridge.update(host, sliceJson);
        }""",
    )

@OptIn(ExperimentalWasmJsInterop::class)
@Suppress("UnusedParameter")
private fun commandAtlasMap(
    host: HTMLElement,
    action: String,
): Unit =
    js(
        """{
            const bridge = window.docxAtlasMap;
            if (!bridge || typeof bridge.command !== "function") {
                throw new Error("The bundled DOCX Atlas Canvas renderer cannot accept commands");
            }
            bridge.command(host, action);
        }""",
    )

@OptIn(ExperimentalWasmJsInterop::class)
@Suppress("UnusedParameter")
private fun snapshotAtlasMap(host: HTMLElement): JsString =
    js(
        """{
            const bridge = window.docxAtlasMap;
            if (!bridge || typeof bridge.snapshot !== "function") return "{}";
            return bridge.snapshot(host);
        }""",
    )

@OptIn(ExperimentalWasmJsInterop::class)
@Suppress("UnusedParameter")
private fun destroyAtlasMap(host: HTMLElement): Unit =
    js(
        """{
            const bridge = window.docxAtlasMap;
            if (bridge && typeof bridge.destroy === "function") bridge.destroy(host);
        }""",
    )
