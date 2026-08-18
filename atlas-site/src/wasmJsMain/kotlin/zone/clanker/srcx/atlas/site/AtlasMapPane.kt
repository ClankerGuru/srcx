package zone.clanker.srcx.atlas.site

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import zone.clanker.srcx.atlas.AtlasDrawFileNode
import zone.clanker.srcx.atlas.AtlasDrawSeed
import kotlin.math.abs
import kotlin.math.hypot

@OptIn(ExperimentalComposeUiApi::class)
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
            seed?.let { AtlasMapLayout.place(it, constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat()) }
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
                    particle.radius + 14.0
            }

    fun toWorld(screen: Offset): Offset = Offset((screen.x - pan.x) / scale, (screen.y - pan.y) / scale)

    Canvas(
        modifier =
            Modifier
                .fillMaxSize()
                .onPointerEvent(PointerEventType.Move) { event ->
                    val position = event.changes.firstOrNull()?.position ?: return@onPointerEvent
                    onHover(hit(toWorld(position))?.node)
                }
                .onPointerEvent(PointerEventType.Scroll) { event ->
                    val scroll = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                    if (abs(scroll) < 0.01f) return@onPointerEvent
                    val next = if (scroll < 0f) scale * 1.08f else scale / 1.08f
                    onScale(next)
                }
                .pointerInput(scale, pan, visibleIds, placed) {
                    detectTapGestures { tap ->
                        val particle = hit(toWorld(tap)) ?: return@detectTapGestures
                        val center = Offset(size.width / 2f, size.height / 2f)
                        onSelect(
                            particle.node,
                            Offset(
                                center.x - particle.x * 2.2f,
                                center.y - particle.y * 2.2f,
                            ),
                        )
                    }
                }
                .pointerInput(pan) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        onPan(pan + drag)
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
                val showAll = selectedId == null && lens == "files"
                val incident = selectedId == source.node.id || selectedId == target.node.id
                val crossBuild = source.node.build != target.node.build
                val allowed =
                    when {
                        lens == "files" && (showAll || incident) -> true
                        lens != "files" && crossBuild -> true
                        else -> false
                    }
                if (!allowed) return@forEach
                drawLine(
                    color = AtlasPalette.edge.copy(alpha = if (incident) 0.9f else 0.28f),
                    start = Offset(source.x, source.y),
                    end = Offset(target.x, target.y),
                    strokeWidth = if (incident) 2.2f else 1.1f,
                )
            }
            placed?.particles?.forEach { particle ->
                if (particle.node.id !in visibleIds) return@forEach
                val center = Offset(particle.x, particle.y)
                val active = particle.node.id == selected?.id || particle.node.id == hovered?.id
                drawCircle(AtlasPalette.ring.copy(alpha = 0.18f), particle.radius + 13f, center, style = Stroke(1.1f))
                drawCircle(AtlasPalette.ring.copy(alpha = 0.28f), particle.radius + 10f, center, style = Stroke(1.1f))
                drawCircle(AtlasPalette.ring.copy(alpha = 0.45f), particle.radius + 7f, center, style = Stroke(1.2f))
                drawCircle(AtlasPalette.ring, particle.radius + 4f, center, style = Stroke(if (active) 2.4f else 1.6f))
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
