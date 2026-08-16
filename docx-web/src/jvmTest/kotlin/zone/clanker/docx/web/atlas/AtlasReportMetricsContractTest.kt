package zone.clanker.docx.web.atlas

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AtlasReportMetricsContractTest {
    @Test
    fun keepsTheDocumentSummaryBoundToTheCompleteDashboard() {
        val renderer =
            sourceFile(
                "src/wasmJsMain/kotlin/zone/clanker/docx/web/atlas/dom/dashboard/AtlasDomHeaderRenderer.kt",
            ).readText()

        listOf(
            "renderReportMetrics(root, model)",
            "model.dashboard?.projectCount",
            "model.dashboard?.symbolCount",
            "model.dashboard?.dependencyCount",
            "?.findings",
        ).forEach { marker ->
            assertTrue(renderer.contains(marker), "Report summary is missing complete-dashboard metric: $marker")
        }
        assertFalse(
            renderer.contains("renderScopeMetrics(root, model)"),
            "Document-level metrics must not shrink to the currently loaded Atlas evidence shards",
        )
    }

    private fun sourceFile(path: String): File {
        val candidates = listOf(File(path), File("docx-web/$path"))
        return requireNotNull(candidates.firstOrNull(File::isFile)) {
            "Unable to locate $path from ${File(".").absolutePath}"
        }
    }
}
