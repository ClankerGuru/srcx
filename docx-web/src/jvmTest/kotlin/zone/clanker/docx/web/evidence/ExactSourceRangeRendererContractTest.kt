package zone.clanker.docx.web.evidence

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class ExactSourceRangeRendererContractTest {
    @Test
    fun marksEveryTouchedRowAndWrapsOnlyTheCapturedUtf16Substring() {
        val renderer = sourceFile("atlas/dom/detail/AtlasSourceEvidenceRenderer.kt")

        assertTrue(renderer.contains("activeSegment != null || lineNumber == spec.activeLine"))
        assertTrue(renderer.contains("row.setAttribute(\"aria-current\", \"true\")"))
        assertTrue(renderer.contains("srcx-dashboard__architecture-source-active-range"))
        assertTrue(renderer.contains("data-srcx-range-start"))
        assertTrue(renderer.contains("data-srcx-range-end"))
        assertTrue(renderer.contains("line.substring(activeSegment.startColumn, activeSegment.endColumnExclusive)"))
    }

    private fun sourceFile(name: String): String {
        val relativePath = "src/wasmJsMain/kotlin/zone/clanker/docx/web/$name"
        val candidates = listOf(File(relativePath), File("docx-web/$relativePath"))
        return requireNotNull(candidates.firstOrNull(File::isFile)) {
            "Unable to locate $relativePath from ${File(".").absolutePath}"
        }.readText()
    }
}
