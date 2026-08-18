package zone.clanker.gradle.srcx.report

import zone.clanker.srcx.atlas.AtlasDrawSeed
import zone.clanker.srcx.atlas.AtlasMapLayout

/** Inline SVG of rooms + particles + file→file edges so first paint is the map. */
object AtlasFirstPaintRenderer {
    fun svg(
        seed: AtlasDrawSeed,
        width: Float = 1440f,
        height: Float = 820f,
    ): String {
        val placed = AtlasMapLayout.place(seed, width, height)
        val byId = placed.particles.associateBy { particle -> particle.node.id }
        return buildString {
            append("""<svg id="atlas-first-paint" viewBox="0 0 ${width.toInt()} ${height.toInt()}" """)
            append("""preserveAspectRatio="xMidYMid meet" role="img" aria-label="Workspace atlas">""")
            placed.rooms.forEach { room ->
                append("""<rect x="${fmt(room.minX)}" y="${fmt(room.minY)}" """)
                append("""width="${fmt(room.maxX - room.minX)}" height="${fmt(room.maxY - room.minY)}" """)
                append("""fill="#e7dfd2" stroke="#cfc4b4"/>""")
                append("""<text x="${fmt(room.minX + 10)}" y="${fmt(room.minY + 18)}" """)
                append("""fill="#6b6258" font-size="11">${room.key.uppercase().escapeWorkspaceHtml()}</text>""")
            }
            seed.fileEdges.forEach { edge ->
                val source = byId[edge.source] ?: return@forEach
                val target = byId[edge.target] ?: return@forEach
                append("""<line x1="${fmt(source.x)}" y1="${fmt(source.y)}" """)
                append("""x2="${fmt(target.x)}" y2="${fmt(target.y)}" """)
                append("""stroke="#8a3a58" stroke-opacity="0.45" stroke-width="1.2"/>""")
            }
            placed.particles.forEach { particle ->
                val cx = fmt(particle.x)
                val cy = fmt(particle.y)
                append("""<circle cx="$cx" cy="$cy" r="${fmt(particle.radius + 13)}" fill="none" stroke="#c43d7a" stroke-opacity="0.18"/>""")
                append("""<circle cx="$cx" cy="$cy" r="${fmt(particle.radius + 10)}" fill="none" stroke="#c43d7a" stroke-opacity="0.28"/>""")
                append("""<circle cx="$cx" cy="$cy" r="${fmt(particle.radius + 7)}" fill="none" stroke="#c43d7a" stroke-opacity="0.45"/>""")
                append("""<circle cx="$cx" cy="$cy" r="${fmt(particle.radius + 4)}" fill="none" stroke="#c43d7a"/>""")
                append("""<circle cx="$cx" cy="$cy" r="${fmt(particle.radius)}" fill="#c43d7a"/>""")
                append("""<circle cx="$cx" cy="$cy" r="${fmt(particle.radius * 0.36f)}" fill="#f6e4ec"/>""")
                append("""<text x="${fmt(particle.x + particle.radius + 6)}" y="${fmt(particle.y + 4)}" """)
                append("""fill="#2a241e" font-size="11">${particle.node.name.escapeWorkspaceHtml()}</text>""")
            }
            append("</svg>")
        }
    }

    private fun fmt(value: Float): String = ((value * 10).toInt() / 10f).toString()
}
