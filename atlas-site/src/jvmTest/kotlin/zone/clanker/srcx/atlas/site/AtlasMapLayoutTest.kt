package zone.clanker.srcx.atlas.site

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import zone.clanker.srcx.atlas.AtlasDrawFileNode
import zone.clanker.srcx.atlas.AtlasDrawSeed

class AtlasMapLayoutTest :
    BehaviorSpec({
        given("a 42-seed with two ownership rooms") {
            val seed =
                AtlasDrawSeed(
                    workspace = "lab",
                    nodeLimit = 42,
                    fileNodes =
                        (1..6).map { index ->
                            AtlasDrawFileNode(
                                id = "file::$index",
                                name = "File$index.kt",
                                path = "src/File$index.kt",
                                build = if (index < 4) "clikt-src" else "mosaic",
                                project = "app",
                                sourceSet = "main",
                                symbols = emptyList(),
                                important = false,
                                relationshipRecordCount = index,
                                content = "",
                            )
                        },
                    fileEdges = emptyList(),
                    nodes = emptyList(),
                    builds = listOf("clikt-src", "mosaic"),
                )

            then("particles sit in rooms of air and do not share one x column") {
                val placed = AtlasMapLayout.place(seed, 1440f, 900f)
                placed.rooms shouldHaveSize 2
                placed.particles shouldHaveSize 6
                placed.rooms.forEach { room ->
                    (room.maxX - room.minX) shouldBeGreaterThan 80f
                    (room.maxY - room.minY) shouldBeGreaterThan 80f
                }
                val uniqueX = placed.particles.map { particle -> particle.x.toInt() }.toSet()
                uniqueX.size shouldBeGreaterThan 2
            }
        }
    })
