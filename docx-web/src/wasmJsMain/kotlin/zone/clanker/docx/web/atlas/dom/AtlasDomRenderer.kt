package zone.clanker.docx.web.atlas.dom

import org.w3c.dom.HTMLDivElement
import zone.clanker.docx.web.atlas.AtlasController
import zone.clanker.docx.web.atlas.dom.controls.AtlasDomControlsRenderer
import zone.clanker.docx.web.atlas.dom.controls.AtlasDomNavigatorRenderer
import zone.clanker.docx.web.atlas.dom.dashboard.AtlasDomDashboardRenderer
import zone.clanker.docx.web.atlas.dom.dashboard.AtlasDomHeaderRenderer

internal class AtlasDomRenderer(
    private val headerRenderer: AtlasDomHeaderRenderer = AtlasDomHeaderRenderer(),
    private val controlsRenderer: AtlasDomControlsRenderer = AtlasDomControlsRenderer(),
    private val navigatorRenderer: AtlasDomNavigatorRenderer = AtlasDomNavigatorRenderer(),
    private val dashboardRenderer: AtlasDomDashboardRenderer = AtlasDomDashboardRenderer(),
    private val sectionNavigator: AtlasDomSectionNavigator = AtlasDomSectionNavigator(),
) {
    fun render(
        root: HTMLDivElement,
        model: AtlasDomSurfaceModel,
        controller: AtlasController,
        actions: AtlasDomActions,
        onGraphCommand: (String) -> Unit,
    ) {
        headerRenderer.render(root, model)
        sectionNavigator.render(root, model, controller)
        controlsRenderer.render(root, model, controller, actions, onGraphCommand)
        navigatorRenderer.render(root, model, controller, actions)
        dashboardRenderer.render(root, model.summary, model.dashboard, model.frame, actions)
    }

    fun release(root: HTMLDivElement) {
        sectionNavigator.release(root)
        controlsRenderer.release(root)
    }
}
