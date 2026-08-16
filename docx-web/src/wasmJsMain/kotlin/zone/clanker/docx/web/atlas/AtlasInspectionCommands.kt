package zone.clanker.docx.web.atlas

import zone.clanker.docx.web.atlas.session.AtlasFocus
import zone.clanker.docx.web.atlas.session.AtlasFocusChanged
import zone.clanker.docx.web.atlas.session.AtlasInspectionKind
import zone.clanker.docx.web.atlas.session.AtlasInspectionTarget
import zone.clanker.docx.web.atlas.session.AtlasSecondaryInspectionToggled
import zone.clanker.docx.web.atlas.session.AtlasSemanticId

internal fun AtlasController.focusSemanticNode(
    id: String?,
    recordHistory: Boolean = true,
) {
    val current = session ?: return
    val path = id?.let { value -> current.hierarchy.pathTo(AtlasSemanticId(value)) }.orEmpty()
    if (id != null && path.isEmpty()) return
    dispatch(
        AtlasFocusChanged(
            AtlasFocus(path, current.focus.expandedIds, current.focus.automaticExpandedIds),
            recordHistory = recordHistory,
        ),
    )
}

internal fun AtlasController.toggleSecondaryNode(
    id: String,
    recordHistory: Boolean = true,
) {
    dispatch(
        AtlasSecondaryInspectionToggled(
            AtlasInspectionTarget(AtlasInspectionKind.NODE, AtlasSemanticId(id)),
            recordHistory = recordHistory,
        ),
    )
}

internal fun AtlasController.clearSecondaryNodes() {
    session
        ?.inspection
        ?.secondary
        .orEmpty()
        .filter { target -> target.kind == AtlasInspectionKind.NODE }
        .forEach { target ->
            dispatch(AtlasSecondaryInspectionToggled(target, recordHistory = false))
        }
}
