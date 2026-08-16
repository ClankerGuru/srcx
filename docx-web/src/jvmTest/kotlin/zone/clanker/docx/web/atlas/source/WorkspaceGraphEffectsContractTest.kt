package zone.clanker.docx.web.atlas.source

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkspaceGraphEffectsContractTest {
    @Test
    fun mapRequestsUseTheTypedSourceWithoutBulkScopeShardLoading() {
        val graphEffects = sourceFile("application/WorkspaceGraphEffects.kt")
        val loadEffects = sourceFile("application/DocxApplicationEffects.kt")
        val legacyLoader = sourceFile("application/BuildScopeLoader.kt")

        assertTrue(graphEffects.contains("source.load(pending.request)"))
        assertTrue(graphEffects.contains("snapshotFlow { controller.session?.workspaceGraphRequest"))
        assertTrue(graphEffects.contains(".collectLatest { request ->"))
        assertFalse(graphEffects.contains("workspaceSourceHierarchySlice"))
        assertFalse(graphEffects.contains("buildProjects"))
        assertFalse(graphEffects.contains("loadedGraphProjects"))

        assertTrue(loadEffects.contains("settleSelectedScopeWithoutProjectShards"))
        assertFalse(loadEffects.contains("selection.selectedProjects"))
        assertFalse(loadEffects.contains("loadSelectedScopeState"))

        assertTrue(legacyLoader.contains("selection.projectIds.singleOrNull()"))
        assertFalse(legacyLoader.contains("selection.selectedProjects"))
        assertFalse(legacyLoader.contains("chunked("))
    }

    private fun sourceFile(name: String): String {
        val relativePath = "src/wasmJsMain/kotlin/zone/clanker/docx/web/$name"
        val candidates = listOf(File(relativePath), File("docx-web/$relativePath"))
        return requireNotNull(candidates.firstOrNull(File::isFile)) {
            "Unable to locate $relativePath from ${File(".").absolutePath}"
        }.readText()
    }
}
