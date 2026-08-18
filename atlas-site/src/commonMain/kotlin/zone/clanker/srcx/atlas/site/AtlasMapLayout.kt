package zone.clanker.srcx.atlas.site

import zone.clanker.srcx.atlas.AtlasDrawFileNode
import zone.clanker.srcx.atlas.AtlasDrawSeed
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Deterministic ownership rooms + loose particle homes. No packer grid of file cards. */
object AtlasMapLayout {
    data class Room(
        val key: String,
        val minX: Float,
        val minY: Float,
        val maxX: Float,
        val maxY: Float,
    )

    data class Particle(
        val node: AtlasDrawFileNode,
        val x: Float,
        val y: Float,
        val radius: Float,
    )

    data class Placed(
        val rooms: List<Room>,
        val particles: List<Particle>,
    )

    fun place(
        seed: AtlasDrawSeed,
        width: Float,
        height: Float,
    ): Placed {
        val frameW = max(1f, width - 24f)
        val frameH = max(1f, height - 56f)
        val grouped = seed.fileNodes.groupBy { node -> node.build.ifBlank { "workspace" } }
        val builds =
            grouped.entries
                .sortedBy { entry -> entry.key }
                .map { entry -> entry.key to entry.value }
        val rooms = packRooms(builds, 12f, 44f, frameW, frameH)
        val particles =
            rooms.flatMap { room ->
                val members = grouped[room.key].orEmpty()
                scatter(members, room)
            }
        return Placed(rooms = rooms, particles = particles)
    }

    private fun packRooms(
        builds: List<Pair<String, List<AtlasDrawFileNode>>>,
        originX: Float,
        originY: Float,
        frameW: Float,
        frameH: Float,
    ): List<Room> {
        if (builds.isEmpty()) return emptyList()
        val gap = 16f
        val cols = if (builds.size <= 3) builds.size else 3
        val rows = builds.chunked(cols)
        val rowWeights = rows.map { row -> row.sumOf { item -> item.second.size.coerceAtLeast(1) } }
        val weightSum = rowWeights.sum().coerceAtLeast(1)
        var y = originY
        return rows.flatMapIndexed { rowIndex, row ->
            val rowH = max(120f, frameH * (rowWeights[rowIndex].toFloat() / weightSum) - gap)
            val rowTotal = row.sumOf { item -> item.second.size.coerceAtLeast(1) }.coerceAtLeast(1)
            var x = originX
            val packed =
                row.map { item ->
                    val cellW = max(140f, (frameW - gap * (row.size - 1)) * (item.second.size.coerceAtLeast(1).toFloat() / rowTotal))
                    val room =
                        Room(
                            key = item.first,
                            minX = x,
                            minY = y,
                            maxX = x + cellW,
                            maxY = y + rowH,
                        )
                    x += cellW + gap
                    room
                }
            y += rowH + gap
            packed
        }
    }

    private fun scatter(
        members: List<AtlasDrawFileNode>,
        room: Room,
    ): List<Particle> {
        val pad = 22f
        val minX = room.minX + pad
        val maxX = room.maxX - pad
        val minY = room.minY + 26f
        val maxY = room.maxY - pad
        val cx = (minX + maxX) / 2f
        val cy = (minY + maxY) / 2f
        val rx = max(24f, (maxX - minX) / 2f)
        val ry = max(24f, (maxY - minY) / 2f)
        val golden = (PI * (3 - sqrt(5.0))).toFloat()
        return members.mapIndexed { index, node ->
            val t = (index + 0.5f) / max(1, members.size)
            val angle = index * golden
            val radius = sqrt(t)
            val signal = min(1f, node.relationshipRecordCount / 24f)
            val nodeRadius = 4.5f + signal * 15.5f
            val x = (cx + cos(angle) * radius * rx * 0.78f).coerceIn(minX + nodeRadius, maxX - nodeRadius)
            val y = (cy + sin(angle) * radius * ry * 0.78f).coerceIn(minY + nodeRadius, maxY - nodeRadius)
            Particle(node = node, x = x, y = y, radius = nodeRadius)
        }
    }
}
