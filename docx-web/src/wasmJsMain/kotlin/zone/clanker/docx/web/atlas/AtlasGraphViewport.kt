package zone.clanker.docx.web.atlas

import androidx.compose.ui.geometry.Offset
import zone.clanker.docx.web.atlas.session.AtlasCamera
import zone.clanker.docx.web.atlas.session.AtlasCameraChanged
import zone.clanker.docx.web.atlas.session.AtlasCameraMotion
import zone.clanker.docx.web.atlas.session.AtlasCameraTransition
import zone.clanker.docx.web.atlas.session.AtlasEvent
import zone.clanker.docx.web.atlas.session.AtlasPoint
import zone.clanker.docx.web.atlas.session.AtlasSession
import zone.clanker.docx.web.atlas.session.AtlasSize

internal interface AtlasGraphViewport {
    val scale: Float
    val pan: Offset
    val mode: GraphViewportMode
    val viewportHeightCss: Float

    fun panBy(delta: Offset)

    fun zoomBy(
        factor: Float,
        anchor: Offset = Offset.Zero,
    )

    fun fit()

    fun reset()

    fun flyToNode(nodeId: String)

    fun previewNode(nodeId: String?)

    fun updateViewportHeight(cssHeight: Float)

    fun bindViewportCommands(command: (String) -> Unit)

    fun unbindViewportCommands()
}

internal class AtlasGraphViewportController(
    private val session: () -> AtlasSession?,
    private val dispatch: (AtlasEvent) -> Unit,
) : AtlasGraphViewport {
    override val scale: Float
        get() = (session()?.camera?.scale ?: 1.0).toFloat()

    override val pan: Offset
        get() =
            session()
                ?.camera
                ?.position
                ?.let { point -> Offset(point.x.toFloat(), point.y.toFloat()) }
                ?: Offset.Zero

    override val mode: GraphViewportMode
        get() = session()?.camera?.viewportMode ?: GraphViewportMode.FIT

    override val viewportHeightCss: Float
        get() = (session()?.camera?.viewport?.height ?: 0.0).toFloat()

    private var viewportCommandSink: ((String) -> Unit)? = null

    override fun panBy(delta: Offset) {
        viewportCommandSink?.let { sink ->
            sink("pan:${delta.x},${delta.y}")
            return
        }
        updateCamera(AtlasCameraMotion.PAN) { camera ->
            camera.copy(
                position = AtlasPoint(camera.position.x + delta.x.toDouble(), camera.position.y + delta.y.toDouble()),
            )
        }
    }

    override fun zoomBy(
        factor: Float,
        anchor: Offset,
    ) {
        viewportCommandSink?.let { sink ->
            sink(if (factor >= 1f) "zoom-in" else "zoom-out")
            return
        }
        updateCamera(AtlasCameraMotion.ZOOM) { camera ->
            val nextScale = (camera.scale * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
            if (nextScale == camera.scale) return@updateCamera camera
            val anchorX = anchor.x.toDouble()
            val anchorY = anchor.y.toDouble()
            val worldX = (anchorX - camera.position.x) / camera.scale
            val worldY = (anchorY - camera.position.y) / camera.scale
            camera.copy(
                scale = nextScale,
                position = AtlasPoint(anchorX - worldX * nextScale, anchorY - worldY * nextScale),
            )
        }
    }

    override fun fit() {
        if (viewportCommandSink != null) {
            viewportCommandSink?.invoke("fit")
        } else {
            updateCamera(AtlasCameraMotion.FIT) { camera -> camera }
        }
    }

    override fun reset() {
        viewportCommandSink?.let { sink ->
            sink("reset")
            return
        }
        val viewport = session()?.camera?.viewport ?: AtlasSize.EMPTY
        dispatch(
            AtlasCameraChanged(
                AtlasCamera(
                    viewport = viewport,
                    transition = AtlasCameraTransition.Moving(AtlasCameraMotion.RESET),
                ),
                recordHistory = false,
            ),
        )
    }

    override fun flyToNode(nodeId: String) {
        require(nodeId.isNotBlank()) { "Atlas fly-to node identity must not be blank" }
        viewportCommandSink?.invoke("fly-to-node:$nodeId")
    }

    override fun previewNode(nodeId: String?) {
        viewportCommandSink?.invoke(nodeId?.let { id -> "preview-node:$id" } ?: "clear-preview")
    }

    override fun updateViewportHeight(cssHeight: Float) {
        val camera = session()?.camera ?: return
        if (camera.viewport.height == cssHeight.toDouble()) return
        dispatch(
            AtlasCameraChanged(
                camera.copy(viewport = AtlasSize(camera.viewport.width, cssHeight.toDouble().coerceAtLeast(0.0))),
                recordHistory = false,
            ),
        )
    }

    override fun bindViewportCommands(command: (String) -> Unit) {
        viewportCommandSink = command
    }

    override fun unbindViewportCommands() {
        viewportCommandSink = null
    }

    private fun updateCamera(
        motion: AtlasCameraMotion,
        transform: (AtlasCamera) -> AtlasCamera,
    ) {
        val camera = session()?.camera ?: return
        val updated = transform(camera).copy(transition = AtlasCameraTransition.Moving(motion))
        if (updated != camera) dispatch(AtlasCameraChanged(updated, recordHistory = false))
    }

    private val AtlasCamera.viewportMode: GraphViewportMode
        get() =
            when ((transition as? AtlasCameraTransition.Moving)?.reason) {
                AtlasCameraMotion.FIT -> GraphViewportMode.FIT
                AtlasCameraMotion.RESET -> GraphViewportMode.RESET
                else -> if (viewport == AtlasSize.EMPTY) GraphViewportMode.FIT else GraphViewportMode.MANUAL
            }

    private companion object {
        const val MIN_ZOOM = 0.08
        const val MAX_ZOOM = 3.2
    }
}
