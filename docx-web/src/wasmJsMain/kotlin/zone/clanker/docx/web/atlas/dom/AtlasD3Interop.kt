package zone.clanker.docx.web.atlas.dom

import org.w3c.dom.HTMLElement
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsString

/** Narrow Wasm-safe boundary around the locally packaged Atlas/D3 renderer. */
internal object AtlasD3Interop {
    fun mount(
        host: HTMLElement,
        frameJson: String,
    ) {
        mountAtlasGraph(host, frameJson)
    }

    fun update(
        host: HTMLElement,
        frameJson: String,
    ) {
        updateAtlasGraph(host, frameJson)
    }

    fun command(
        host: HTMLElement,
        action: String,
    ) {
        commandAtlasGraph(host, action)
    }

    @OptIn(ExperimentalWasmJsInterop::class)
    fun snapshot(host: HTMLElement): String = snapshotAtlasGraph(host).toString()

    fun destroy(host: HTMLElement) {
        destroyAtlasGraph(host)
    }
}

@OptIn(ExperimentalWasmJsInterop::class)
@Suppress("UnusedParameter")
private fun mountAtlasGraph(
    host: HTMLElement,
    frameJson: String,
): Unit =
    js(
        """{
            const bridge = window.docxAtlasD3;
            if (!bridge || typeof bridge.mount !== "function") {
                throw new Error("The bundled DOCX Atlas/D3 bridge is unavailable");
            }
            bridge.mount(host, frameJson);
        }""",
    )

@OptIn(ExperimentalWasmJsInterop::class)
@Suppress("UnusedParameter")
private fun updateAtlasGraph(
    host: HTMLElement,
    frameJson: String,
): Unit =
    js(
        """{
            const bridge = window.docxAtlasD3;
            if (!bridge || typeof bridge.update !== "function") {
                throw new Error("The bundled DOCX Atlas/D3 bridge cannot update");
            }
            bridge.update(host, frameJson);
        }""",
    )

@OptIn(ExperimentalWasmJsInterop::class)
@Suppress("UnusedParameter")
private fun commandAtlasGraph(
    host: HTMLElement,
    action: String,
): Unit =
    js(
        """{
            const bridge = window.docxAtlasD3;
            if (!bridge || typeof bridge.command !== "function") {
                throw new Error("The bundled DOCX Atlas/D3 bridge cannot accept commands");
            }
            bridge.command(host, action);
        }""",
    )

@OptIn(ExperimentalWasmJsInterop::class)
@Suppress("UnusedParameter")
private fun snapshotAtlasGraph(host: HTMLElement): JsString =
    js(
        """{
            const bridge = window.docxAtlasD3;
            if (!bridge || typeof bridge.snapshot !== "function") return "{}";
            const snapshot = bridge.snapshot(host);
            if (typeof snapshot !== "string") {
                throw new Error("The bundled DOCX Atlas/D3 snapshot must be JSON text");
            }
            return snapshot;
        }""",
    )

@OptIn(ExperimentalWasmJsInterop::class)
@Suppress("UnusedParameter")
private fun destroyAtlasGraph(host: HTMLElement): Unit =
    js(
        """{
            const bridge = window.docxAtlasD3;
            if (bridge && typeof bridge.destroy === "function") bridge.destroy(host);
        }""",
    )
