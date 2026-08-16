package zone.clanker.docx.web.atlas

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AtlasFullscreenSessionContractTest {
    @Test
    fun preservesTheEmbeddedAtlasSessionAcrossFullscreen() {
        val renderer =
            sourceFile(
                "src/wasmJsMain/kotlin/zone/clanker/docx/web/atlas/dom/controls/AtlasViewportControlsRenderer.kt",
            ).readText()

        listOf(
            "element.__docxFullscreenReturnX = window.scrollX",
            "element.__docxFullscreenReturnY = window.scrollY",
            "window.scrollTo(returnX, returnY)",
            "element.__docxFullscreenScrollContainer = scrollContainer",
            "shadowHost?.matches?.(\"[data-docx-atlas-surface]\")",
            "scrollContainer.scrollTop = returnScrollTop",
            "scrollContainer.scrollTop += rect.top - returnGraphTop",
            "cancelRestoration",
            "releaseCancellation",
            "docx-atlas-fullscreen-transition",
            "data-srcx-fullscreen-restore-state",
            "data-srcx-fullscreen-restore-graph-top",
            "requestAnimationFrame(() => requestAnimationFrame(() => restore(60)))",
            "focus({ preventScroll: true })",
        ).forEach { marker ->
            assertTrue(renderer.contains(marker), "Fullscreen restoration is missing: $marker")
        }
        assertFalse(
            renderer.contains("element.querySelector('[data-srcx-graph-action=\"fit\"]')?.click()"),
            "Fullscreen must not reset the canonical Atlas camera with an implicit Fit command",
        )
    }

    private fun sourceFile(path: String): File {
        val candidates = listOf(File(path), File("docx-web/$path"))
        return requireNotNull(candidates.firstOrNull(File::isFile)) {
            "Unable to locate $path from ${File(".").absolutePath}"
        }
    }
}
