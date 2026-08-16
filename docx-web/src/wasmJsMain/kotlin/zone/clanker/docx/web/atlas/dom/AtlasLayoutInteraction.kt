package zone.clanker.docx.web.atlas.dom

import org.w3c.dom.HTMLElement
import zone.clanker.docx.web.atlas.AtlasBridgeLayout

internal fun HTMLElement.readLayoutInteraction(): AtlasBridgeLayout? {
    val id = getAttribute(LAYOUT_NODE_ATTRIBUTE)?.takeIf(String::isNotBlank) ?: return null
    val position = readLayoutPosition() ?: return null
    val width = getAttribute(LAYOUT_WIDTH_ATTRIBUTE)?.toDoubleOrNull() ?: 0.0
    val height = getAttribute(LAYOUT_HEIGHT_ATTRIBUTE)?.toDoubleOrNull() ?: 0.0
    return AtlasBridgeLayout(id, position.first, position.second, width, height)
}

private fun HTMLElement.readLayoutPosition(): Pair<Double, Double>? =
    getAttribute(LAYOUT_X_ATTRIBUTE)?.toDoubleOrNull()?.let { x ->
        getAttribute(LAYOUT_Y_ATTRIBUTE)?.toDoubleOrNull()?.let { y -> x to y }
    }

private const val LAYOUT_NODE_ATTRIBUTE = "data-docx-atlas-layout-node-id"
private const val LAYOUT_X_ATTRIBUTE = "data-docx-atlas-layout-x"
private const val LAYOUT_Y_ATTRIBUTE = "data-docx-atlas-layout-y"
private const val LAYOUT_WIDTH_ATTRIBUTE = "data-docx-atlas-layout-width"
private const val LAYOUT_HEIGHT_ATTRIBUTE = "data-docx-atlas-layout-height"
