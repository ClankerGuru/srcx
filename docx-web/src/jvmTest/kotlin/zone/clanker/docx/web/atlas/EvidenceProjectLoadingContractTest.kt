package zone.clanker.docx.web.atlas

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EvidenceProjectLoadingContractTest {
    @Test
    fun loadsSelectionEvidenceWithoutChangingTheAtlasScope() {
        val runtime = sourceFile("atlas/dom/AtlasDomRuntime.kt")
        val requestRuntime = sourceFile("atlas/dom/AtlasEvidenceRequestRuntime.kt")
        val statusRenderer = sourceFile("atlas/dom/AtlasDomStatusRenderer.kt")
        val application = sourceFile("application/DocxApplication.kt")
        val effects = sourceFile("application/EvidenceProjectEffects.kt")
        val sourceEffects = sourceFile("application/SourceContentEffects.kt")
        val surface = sourceFile("atlas/dom/AtlasDomSurface.kt")
        val sourceRenderer = sourceFile("atlas/dom/detail/AtlasSourceEvidenceRenderer.kt")

        assertTrue(requestRuntime.contains("actions.onProjectRequested(projectId)"))
        assertTrue(requestRuntime.contains("actions.onSourceRequested(target.projectId, target.fileId)"))
        assertFalse(runtime.contains("onProjectSelected"))
        assertTrue(statusRenderer.contains("data-docx-atlas-evidence-state"))
        assertTrue(statusRenderer.contains("data-docx-atlas-source-state"))
        assertTrue(application.contains("current.copy(evidence = evidence)"))
        assertTrue(effects.contains("snapshotFlow { currentReadyProvider.value()?.evidenceProjectRequest() }"))
        assertTrue(effects.contains("cache.get(generationId, request.projectId)"))
        assertTrue(effects.contains("loader.loadProjectResource(request.site, request.projectId)"))
        assertTrue(sourceEffects.contains("snapshotFlow { currentReadyProvider.value()?.sourceContentRequest() }"))
        assertTrue(sourceEffects.contains("loader.loadSourceContent(request.site, request.projectId, request.fileId)"))
        assertTrue(sourceRenderer.contains("val content = spec.content"))
        assertFalse(effects.contains("AtlasController"))
        assertFalse(effects.contains("selectScope"))
        assertFalse(effects.contains("attachProject"))
        assertFalse(effects.contains("attachSelection"))
        assertFalse(sourceEffects.contains("AtlasController"))
        assertFalse(sourceEffects.contains("selectScope"))
        assertFalse(sourceEffects.contains("attachProject"))
        assertTrue(surface.contains("evidence = state.evidence"))
    }

    @Test
    fun mountsExactSelectorsPagedNavigationAndLazySourceLoadingInTheActiveInspector() {
        val requestRuntime = sourceFile("atlas/dom/AtlasEvidenceRequestRuntime.kt")
        val domRuntime = sourceFile("atlas/dom/AtlasDomRuntime.kt")
        val pageRenderer = sourceFile("atlas/dom/detail/AtlasPagedEvidenceRenderer.kt")
        val sourceDetail = sourceFile("atlas/dom/detail/AtlasSourceDetailRenderer.kt")

        assertTrue(requestRuntime.contains("WorkspaceRelationshipOccurrenceSelector("))
        assertFalse(requestRuntime.contains("sampleFactIds"))
        assertTrue(domRuntime.contains("exactEvidence.select("))
        assertTrue(domRuntime.contains("onPrevious = exactEvidence::previous"))
        assertTrue(domRuntime.contains("onNext = exactEvidence::next"))
        assertTrue(domRuntime.contains("onSelect = exactEvidence::selectOccurrence"))
        assertTrue(pageRenderer.contains("state.occurrences.forEachIndexed"))
        assertTrue(pageRenderer.contains("actions.onSelect(index)"))
        assertTrue(sourceDetail.contains("appendDeclarationEvidence("))
        assertTrue(sourceDetail.contains("appendPagedRelationshipEvidence("))
        assertTrue(sourceDetail.contains("exactDeclarationLoadStatus(model, declaration.location.projectId)"))
        assertTrue(sourceDetail.contains("Loading the declaration's bounded project and source file"))
    }

    private fun sourceFile(name: String): String {
        val relativePath = "src/wasmJsMain/kotlin/zone/clanker/docx/web/$name"
        val candidates = listOf(File(relativePath), File("docx-web/$relativePath"))
        return requireNotNull(candidates.firstOrNull(File::isFile)) {
            "Unable to locate $relativePath from ${File(".").absolutePath}"
        }.readText()
    }
}
