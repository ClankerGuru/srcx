package zone.clanker.gradle.srcx.report

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class AtlasComposeHostRendererTest :
    BehaviorSpec({
        given("the Compose Atlas HTML host") {
            val html = AtlasComposeHostRenderer().document("foo-bar-workspace")

            then("it is a kilobyte mount with no file-card dump and no D3") {
                html.length shouldBeLessThan 2_000
                html shouldContain "id=\"atlas-root\""
                html shouldContain "atlas-host.js"
                html shouldContain "foo-bar-workspace"
                html shouldNotContain "d3.js"
                html shouldNotContain "atlas-draw.js"
                html shouldNotContain "architecture-fallback-files"
                html shouldNotContain "OptionGroupsTest.kt"
                html shouldNotContain "data-srcx-architecture-data"
                html shouldNotContain "sql.js"
            }
        }
    })
