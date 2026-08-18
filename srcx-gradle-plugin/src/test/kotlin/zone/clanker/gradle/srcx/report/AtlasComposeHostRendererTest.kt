package zone.clanker.gradle.srcx.report

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class AtlasComposeHostRendererTest :
    BehaviorSpec({
        given("the Compose Atlas HTML host") {
            val html = AtlasComposeHostRenderer().document("foo-bar-workspace")

            then("the host is a Compose mount, not a dumped map or file-card list") {
                html.length shouldBeLessThan 2_000
                html shouldContain "id=\"atlas-root\""
                html shouldContain "atlas-host.js"
                html shouldContain "foo-bar-workspace"
                html shouldNotContain "d3.js"
                html shouldNotContain "atlas-draw.js"
                html shouldNotContain "atlas-first-paint"
                html shouldNotContain "architecture-fallback-files"
                html shouldNotContain "data-srcx-architecture-data"
                html shouldNotContain "sql.js"
            }
        }
    })
