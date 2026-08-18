package zone.clanker.srcx.atlas.site

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import zone.clanker.srcx.atlas.AtlasDrawFileNode
import zone.clanker.srcx.atlas.AtlasDrawSeed
import zone.clanker.srcx.atlas.AtlasMapLayout
import kotlin.math.abs
import kotlin.math.hypot

@Composable
fun AtlasMapPane(
    seed: AtlasDrawSeed?,
    query: String,
    kinds: Set<String>,
    usedAtLeast: Int,
    lens: String,
    selected: AtlasDrawFileNode?,
    hovered: AtlasDrawFileNode?,
    scale: Float,
    pan: Offset,
    onPan: (Offset) -> Unit,
    onScale: (Float) -> Unit,
    onHover: (AtlasDrawFileNode?) -> Unit,
    onSelect: (AtlasDrawFileNode, Offset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier = modifier) {
        val placed =
            remember(seed, maxWidth, maxHeight) {
                val width = constraints.maxWidth.toFloat()
                val height = constraints.maxHeight.toFloat()
                if (seed != null) {
                    AtlasMapLayout.place(seed, width, height)
                } else {
                    AtlasMapLayout.Placed(
                        rooms =
                            listOf(
                                AtlasMapLayout.Room("atlas", 12f, 12f, width - 12f, height - 12f),
                            ),
                        particles = emptyList(),
                    )
                }
            }
        val visibleIds =
            remember(placed, query, kinds, usedAtLeast) {
                placed
                    ?.particles
                    ?.filter { particle ->
                        AtlasVisibleNodes.matches(particle.node, query, kinds, usedAtLeast)
                    }?.map { particle -> particle.node.id }
                    ?.toSet()
                    .orEmpty()
            }

        fun hit(world: Offset): AtlasMapLayout.Particle? =
            placed
                ?.particles
                ?.asReversed()
                ?.firstOrNull { particle ->
                    particle.node.id in visibleIds &&
                        hypot((world.x - particle.x).toDouble(), (world.y - particle.y).toDouble()) <=
                        particle.radius + 16.0
                }

        fun toWorld(screen: Offset): Offset = Offset((screen.x - pan.x) / scale, (screen.y - pan.y) / scale)

        Canvas(
            modifier =
                Modifier
                    .fillMaxSize()
                    .pointerInput(placed, visibleIds, scale, pan) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            var dragged = false
                            var last = down.position
                            onHover(hit(toWorld(down.position))?.node)
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: break
                                val scroll = change.scrollDelta.y
                                if (abs(scroll) > 0.01f) {
                                    onScale(if (scroll < 0f) scale * 1.12f else scale / 1.12f)
                                    change.consume()
                                }
                                if (change.pressed) {
                                    val delta = change.position - last
                                    if (hypot(delta.x.toDouble(), delta.y.toDouble()) > 3.0) {
                                        dragged = true
                                        onPan(pan + change.positionChange())
                                        change.consume()
                                    }
                                    last = change.position
                                    onHover(hit(toWorld(change.position))?.node)
                                }
                                if (change.changedToUp()) {
                                    if (!dragged) {
                                        val particle = hit(toWorld(change.position))
                                        if (particle != null) {
                                            val fit = 2.4f
                                            onSelect(
                                                particle.node,
                                                Offset(
                                                    size.width / 2f - particle.x * fit,
                                                    size.height / 2f - particle.y * fit,
                                                ),
                                            )
                                        }
                                    }
                                    break
                                }
                            }
                        }
                    },
        ) {
            withTransform({
                translate(pan.x, pan.y)
                scale(scale, scale, pivot = Offset.Zero)
            }) {
                placed?.rooms?.forEach { room ->
                    drawRect(
                        color = AtlasPalette.room,
                        topLeft = Offset(room.minX, room.minY),
                        size = Size(room.maxX - room.minX, room.maxY - room.minY),
                    )
                    drawRect(
                        color = AtlasPalette.roomLine,
                        topLeft = Offset(room.minX, room.minY),
                        size = Size(room.maxX - room.minX, room.maxY - room.minY),
                        style = Stroke(width = 1.2f),
                    )
                    drawText(
                        textMeasurer = measurer,
                        text = room.key.uppercase(),
                        topLeft = Offset(room.minX + 10f, room.minY + 6f),
                        style = TextStyle(color = AtlasPalette.mute, fontSize = 11.sp),
                    )
                }
                val byId = placed?.particles?.associateBy { particle -> particle.node.id }.orEmpty()
                seed?.fileEdges.orEmpty().forEach { edge ->
                    val source = byId[edge.source] ?: return@forEach
                    val target = byId[edge.target] ?: return@forEach
                    if (source.node.id !in visibleIds || target.node.id !in visibleIds) return@forEach
                    val selectedId = selected?.id
                    val incident = selectedId == source.node.id || selectedId == target.node.id
                    val crossBuild = source.node.build != target.node.build
                    val allowed = if (lens == "files") true else crossBuild
                    if (!allowed) return@forEach
                    drawLine(
                        color = AtlasPalette.edge.copy(alpha = if (incident) 0.92f else 0.42f),
                        start = Offset(source.x, source.y),
                        end = Offset(target.x, target.y),
                        strokeWidth = if (incident) 2.2f else 1.2f,
                    )
                }
                placed?.particles?.forEach { particle ->
                    if (particle.node.id !in visibleIds) return@forEach
                    val center = Offset(particle.x, particle.y)
                    val active = particle.node.id == selected?.id || particle.node.id == hovered?.id
                    drawCircle(AtlasPalette.ring.copy(alpha = 0.18f), particle.radius + 13f, center, style = Stroke(1.1f))
                    drawCircle(AtlasPalette.ring.copy(alpha = 0.28f), particle.radius + 10f, center, style = Stroke(1.1f))
                    drawCircle(AtlasPalette.ring.copy(alpha = 0.45f), particle.radius + 7f, center, style = Stroke(1.2f))
                    drawCircle(AtlasPalette.ring, particle.radius + 4f, center, style = Stroke(if (active) 2.6f else 1.6f))
                    drawCircle(AtlasPalette.ring, particle.radius, center)
                    drawCircle(AtlasPalette.core, particle.radius * 0.36f, center)
                    drawText(
                        textMeasurer = measurer,
                        text = particle.node.name,
                        topLeft = Offset(particle.x + particle.radius + 6f, particle.y - 7f),
                        style = TextStyle(color = AtlasPalette.ink, fontSize = 11.sp),
                    )
                }
            }
        }
    }
}
