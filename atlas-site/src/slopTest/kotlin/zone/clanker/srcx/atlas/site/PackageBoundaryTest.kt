package zone.clanker.srcx.atlas.site

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertTrue
import io.kotest.core.spec.style.BehaviorSpec

class PackageBoundaryTest :
    BehaviorSpec({
        val mainScope = Konsist.scopeFromSourceSet("wasmJsMain")

        given("import direction enforcement") {
            `when`("files are in the atlas site package") {
                val files = mainScope.files.filter { it.packagee?.name?.contains("srcx.atlas") == true }

                then("atlas site never imports from the srcx plugin task package") {
                    files.assertTrue { it.imports.none { imp -> imp.name.contains("gradle.srcx.task") } }
                }
            }
        }
    })
