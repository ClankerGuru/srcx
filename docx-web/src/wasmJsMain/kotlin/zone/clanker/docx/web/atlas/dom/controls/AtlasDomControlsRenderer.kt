package zone.clanker.docx.web.atlas.dom.controls

import org.w3c.dom.HTMLDivElement
import zone.clanker.docx.web.atlas.AtlasController
import zone.clanker.docx.web.atlas.dom.AtlasDomActions
import zone.clanker.docx.web.atlas.dom.AtlasDomSurfaceModel

internal class AtlasDomControlsRenderer {
    private val lensRenderer = AtlasLensControlsRenderer()
    private val declarationRenderer = AtlasDeclarationControlsRenderer()
    private val relationshipRenderer = AtlasRelationshipControlsRenderer()
    private val relationshipCountRenderer = AtlasRelationshipCountControlsRenderer()
    private val searchRenderer = AtlasSearchControlsRenderer()
    private val viewportRenderer = AtlasViewportControlsRenderer()
    private val sessionRenderer = AtlasSessionControlsRenderer()

    fun render(
        root: HTMLDivElement,
        model: AtlasDomSurfaceModel,
        controller: AtlasController,
        actions: AtlasDomActions,
        onGraphCommand: (String) -> Unit,
    ) {
        val graphEnabled = model.slice != null || model.frame != null
        val scopeFiltersEnabled = graphEnabled
        lensRenderer.render(root, model, controller, graphEnabled)
        declarationRenderer.render(root, model, controller.graph, scopeFiltersEnabled)
        relationshipRenderer.render(root, model, controller.graph, scopeFiltersEnabled)
        relationshipCountRenderer.render(root, controller.graph, scopeFiltersEnabled)
        searchRenderer.render(root, controller.graph, scopeFiltersEnabled)
        viewportRenderer.render(root, controller, onGraphCommand)
        sessionRenderer.render(root, model, controller, actions, onGraphCommand)
    }

    fun release(root: HTMLDivElement) {
        searchRenderer.release()
        relationshipCountRenderer.release()
        viewportRenderer.release(root)
        sessionRenderer.release(root)
    }
}
