package zone.clanker.docx.web.atlas.dom

import org.w3c.dom.HTMLElement
import kotlin.js.ExperimentalWasmJsInterop

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun("() => globalThis.performance.now()")
internal external fun atlasDomTimestamp(): Double

internal class AtlasDomRuntimeTiming {
    private val startedAt = atlasDomTimestamp()
    private var rendererReadyAt = startedAt
    private var searchReadyAt = startedAt
    private var bridgeReadyAt = startedAt
    private var detailReadyAt = startedAt

    fun rendererReady() {
        rendererReadyAt = atlasDomTimestamp()
    }

    fun searchReady() {
        searchReadyAt = atlasDomTimestamp()
    }

    fun bridgeReady() {
        bridgeReadyAt = atlasDomTimestamp()
    }

    fun detailReady() {
        detailReadyAt = atlasDomTimestamp()
    }

    fun publish(host: HTMLElement) {
        host.setAttribute("data-docx-atlas-runtime-update-ms", (atlasDomTimestamp() - startedAt).toString())
        host.setAttribute(
            "data-docx-atlas-runtime-update-breakdown",
            "renderer=${rendererReadyAt - startedAt};" +
                "search=${searchReadyAt - rendererReadyAt};" +
                "bridge=${bridgeReadyAt - searchReadyAt};" +
                "detail=${detailReadyAt - bridgeReadyAt};" +
                "status=${atlasDomTimestamp() - detailReadyAt}",
        )
    }
}
