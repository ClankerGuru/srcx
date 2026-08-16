package zone.clanker.docx.web.atlas.dom.controls

import org.w3c.dom.HTMLDivElement
import zone.clanker.docx.web.atlas.AtlasController
import zone.clanker.docx.web.atlas.dom.AtlasDomActions
import zone.clanker.docx.web.atlas.dom.AtlasDomSurfaceModel

internal class AtlasDomNavigatorRenderer {
    private val buildsRenderer = AtlasBuildNavigatorRenderer()
    private val projectsRenderer = AtlasProjectNavigatorRenderer()
    private val sourceSetsRenderer = AtlasSourceSetNavigatorRenderer()
    private val contextRenderer = AtlasFilterContextRenderer()
    private val levelSwitcher = AtlasScopeLevelSwitcher()

    fun render(
        root: HTMLDivElement,
        model: AtlasDomSurfaceModel,
        controller: AtlasController,
        actions: AtlasDomActions,
    ) {
        buildsRenderer.render(
            root,
            model,
            onBuildSelected = actions.onBuildSelected,
        ) {
            levelSwitcher.advanceTo(root, AtlasScopeLevel.PROJECT)
        }
        projectsRenderer.render(root, model, actions) {
            levelSwitcher.advanceTo(root, AtlasScopeLevel.SOURCE_SET)
        }
        sourceSetsRenderer.render(root, model, controller.graph, actions.onSourceSetsSelected)
        contextRenderer.render(root, model, controller.graph)
        levelSwitcher.render(root)
    }
}
