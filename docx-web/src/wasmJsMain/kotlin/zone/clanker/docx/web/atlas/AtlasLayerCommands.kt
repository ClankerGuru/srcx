package zone.clanker.docx.web.atlas

import zone.clanker.docx.web.atlas.session.AtlasLayer
import zone.clanker.docx.web.atlas.session.AtlasLayersChanged

internal fun AtlasController.toggleLayer(layer: AtlasLayer) {
    val current = session ?: return
    val enabled =
        if (layer in current.layers.enabled) {
            current.layers.enabled - layer
        } else {
            current.layers.enabled + layer
        }
    dispatch(AtlasLayersChanged(enabled.sortedBy(AtlasLayer::ordinal)))
}

internal fun AtlasController.replaceLayers(layers: Iterable<AtlasLayer>) {
    dispatch(AtlasLayersChanged(layers.distinct().sortedBy(AtlasLayer::ordinal)))
}

internal fun AtlasController.navigateToSection(section: AtlasReportSection) {
    activeSection = section
    requestedSection = section
    sectionNavigationRevision += 1
}

internal fun AtlasController.observeSection(section: AtlasReportSection) {
    activeSection = section
}
