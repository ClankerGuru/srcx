package zone.clanker.gradle.srcx.report

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class AtlasComposeHostRendererTest :
    BehaviorSpec({
        given("the Compose Atlas HTML host") {
            val html = AtlasComposeHostRenderer().document("foo-bar-workspace")

            then("the empty host has no file-card dump and no D3") {
                html.length shouldBeLessThan 2_000
                html shouldContain "id=\"atlas-root\""
                html shouldContain "atlas-host.js"
                html shouldContain "foo-bar-workspace"
                html shouldNotContain "d3.js"
                html shouldNotContain "atlas-draw.js"
                html shouldNotContain "architecture-fallback-files"
                html shouldNotContain "data-srcx-architecture-data"
                html shouldNotContain "sql.js"
            }
        }

        given("a first-paint map") {
            val html =
                AtlasComposeHostRenderer().document(
                    "foo-bar-workspace",
                    """<svg id="atlas-first-paint"><rect/><circle/><text>CLIKT-SRC</text></svg>""",
                )

            then("first paint is rooms and particles, not a card list") {
                html shouldContain "id=\"atlas-first-paint\""
                html shouldContain "CLIKT-SRC"
                html shouldContain "<circle"
                html shouldNotContain "architecture-fallback-files"
            }
        }
    })
