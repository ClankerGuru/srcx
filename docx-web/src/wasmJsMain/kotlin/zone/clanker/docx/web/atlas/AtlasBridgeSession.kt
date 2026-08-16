package zone.clanker.docx.web.atlas

import zone.clanker.docx.web.atlas.session.AtlasCamera
import zone.clanker.docx.web.atlas.session.AtlasCameraChanged
import zone.clanker.docx.web.atlas.session.AtlasCameraCoordinateSpace
import zone.clanker.docx.web.atlas.session.AtlasCameraMotion
import zone.clanker.docx.web.atlas.session.AtlasCameraTransition
import zone.clanker.docx.web.atlas.session.AtlasInspectionKind
import zone.clanker.docx.web.atlas.session.AtlasInspectionTarget
import zone.clanker.docx.web.atlas.session.AtlasLayoutOverride
import zone.clanker.docx.web.atlas.session.AtlasLayoutOverrideChanged
import zone.clanker.docx.web.atlas.session.AtlasOverlay
import zone.clanker.docx.web.atlas.session.AtlasPoint
import zone.clanker.docx.web.atlas.session.AtlasSecondaryInspectionToggled
import zone.clanker.docx.web.atlas.session.AtlasSemanticId
import zone.clanker.docx.web.atlas.session.AtlasSize

internal data class AtlasBridgeCamera(
    val x: Double,
    val y: Double,
    val scale: Double,
    val coordinateSpace: AtlasCameraCoordinateSpace,
)

internal data class AtlasBridgeViewport(
    val x: Double,
    val y: Double,
    val scale: Double,
    val width: Double,
    val height: Double,
    val mode: GraphViewportMode,
    val coordinateSpace: AtlasCameraCoordinateSpace,
)

internal data class AtlasBridgeLayout(
    val id: String,
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
)

internal data class AtlasBridgeSecondarySelection(
    val nodeIds: Set<String>,
    val relationIds: Set<String>,
)

internal data class AtlasBridgePrimarySelection(
    val nodeId: String?,
    val relationId: String?,
)

internal val AtlasController.searchOverlayIsOpen: Boolean
    get() = session?.overlay == AtlasOverlay.Search

internal val AtlasController.bridgeCamera: AtlasBridgeCamera?
    get() =
        session?.camera?.let { camera ->
            AtlasBridgeCamera(camera.position.x, camera.position.y, camera.scale, camera.coordinateSpace)
        }

internal val AtlasController.bridgeCameraHasMeasuredViewport: Boolean
    get() =
        session
            ?.camera
            ?.viewport
            ?.let { viewport -> viewport.width > 0.0 && viewport.height > 0.0 }
            ?: false

internal val AtlasController.bridgeLayerNames: List<String>
    get() =
        session
            ?.layers
            ?.enabled
            .orEmpty()
            .map { layer -> layer.name.lowercase() }

internal val AtlasController.bridgeSecondarySelection: AtlasBridgeSecondarySelection
    get() {
        val secondary = session?.inspection?.secondary.orEmpty()
        return AtlasBridgeSecondarySelection(
            nodeIds =
                secondary
                    .filter { target -> target.kind == AtlasInspectionKind.NODE }
                    .mapTo(mutableSetOf()) { target -> target.id.value },
            relationIds =
                secondary
                    .filter { target -> target.kind != AtlasInspectionKind.NODE }
                    .mapTo(mutableSetOf()) { target -> target.id.value },
        )
    }

internal val AtlasController.bridgePrimarySelection: AtlasBridgePrimarySelection
    get() {
        val primary = session?.inspection?.primary
        return AtlasBridgePrimarySelection(
            nodeId = primary?.takeIf { target -> target.kind == AtlasInspectionKind.NODE }?.id?.value,
            relationId = primary?.takeIf { target -> target.kind != AtlasInspectionKind.NODE }?.id?.value,
        )
    }

internal fun AtlasController.recordBridgeViewport(viewport: AtlasBridgeViewport) {
    dispatch(
        AtlasCameraChanged(
            AtlasCamera(
                position = AtlasPoint(viewport.x, viewport.y),
                scale = viewport.scale,
                viewport = AtlasSize(viewport.width, viewport.height),
                transition = viewport.mode.cameraTransition,
                coordinateSpace = viewport.coordinateSpace,
            ),
            recordHistory = false,
        ),
    )
}

private val GraphViewportMode.cameraTransition: AtlasCameraTransition
    get() =
        when (this) {
            GraphViewportMode.FIT -> AtlasCameraTransition.Moving(AtlasCameraMotion.FIT)
            GraphViewportMode.RESET -> AtlasCameraTransition.Moving(AtlasCameraMotion.RESET)
            GraphViewportMode.MANUAL -> AtlasCameraTransition.Idle
        }

internal fun AtlasController.recordBridgeLayout(layout: AtlasBridgeLayout) {
    val id = AtlasSemanticId(layout.id)
    if (session?.hierarchy?.contains(id) != true) return
    val value =
        AtlasLayoutOverride(
            position = AtlasPoint(layout.x, layout.y),
            minimumSize = AtlasSize(layout.width, layout.height),
        ).takeUnless(AtlasLayoutOverride::isCalculatedDefault)
    dispatch(AtlasLayoutOverrideChanged(id, value))
}

internal fun AtlasController.toggleBridgeSecondary(
    id: String,
    node: Boolean,
) {
    val kind = if (node) AtlasInspectionKind.NODE else AtlasInspectionKind.RELATION
    dispatch(AtlasSecondaryInspectionToggled(AtlasInspectionTarget(kind, AtlasSemanticId(id))))
}

private fun Double.closeTo(other: Double): Boolean = kotlin.math.abs(this - other) < LAYOUT_EPSILON

private fun AtlasLayoutOverride.isCalculatedDefault(): Boolean =
    position?.let { point -> point.x.closeTo(0.0) && point.y.closeTo(0.0) } != false &&
        minimumSize?.let { size -> size.width.closeTo(0.0) && size.height.closeTo(0.0) } != false

private const val LAYOUT_EPSILON: Double = 0.0001
