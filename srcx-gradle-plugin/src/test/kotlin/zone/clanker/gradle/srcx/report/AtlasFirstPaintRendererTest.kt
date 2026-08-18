package zone.clanker.gradle.srcx.report

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import zone.clanker.srcx.atlas.AtlasDrawEdge
import zone.clanker.srcx.atlas.AtlasDrawFileNode
import zone.clanker.srcx.atlas.AtlasDrawSeed

class AtlasFirstPaintRendererTest :
    BehaviorSpec({
        given("a two-file seed") {
            val seed =
                AtlasDrawSeed(
                    workspace = "lab",
                    nodeLimit = 42,
                    fileNodes =
                        listOf(
                            AtlasDrawFileNode(
                                id = "a",
                                name = "A.kt",
                                path = "A.kt",
                                build = "clikt-src",
                                project = "app",
                                sourceSet = "main",
                                symbols = emptyList(),
                                important = false,
                                relationshipRecordCount = 2,
                                content = "",
                            ),
                            AtlasDrawFileNode(
                                id = "b",
                                name = "B.kt",
                                path = "B.kt",
                                build = "mosaic",
                                project = "app",
                                sourceSet = "main",
                                symbols = emptyList(),
                                important = false,
                                relationshipRecordCount = 1,
                                content = "",
                            ),
                        ),
                    fileEdges = listOf(AtlasDrawEdge("e", "a", "b", "call", 1)),
                    nodes = emptyList(),
                    builds = listOf("clikt-src", "mosaic"),
                )

            then("the SVG is rooms, particles, and a file-file edge") {
                val svg = AtlasFirstPaintRenderer.svg(seed)
                svg shouldContain "id=\"atlas-first-paint\""
                svg shouldContain "CLIKT-SRC"
                svg shouldContain "MOSAIC"
                svg shouldContain "<circle"
                svg shouldContain "<line"
                svg shouldNotContain "architecture-fallback-files"
            }
        }
    })
