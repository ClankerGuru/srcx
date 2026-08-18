package zone.clanker.srcx.atlas.site

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertTrue
import io.kotest.core.spec.style.BehaviorSpec

class ForbiddenPackageTest :
    BehaviorSpec({
        val mainScope = Konsist.scopeFromSourceSet("wasmJsMain")

        given("no junk-drawer packages exist") {
            val forbidden =
                listOf("utils", "helpers", "managers", "common", "misc", "shared", "core", "base", "internal", "support")

            `when`("examining all main source files") {
                then("no file lives in a forbidden package") {
                    mainScope.files.assertTrue {
                        val lastSegment = (it.packagee?.name ?: "").substringAfterLast(".")
                        lastSegment !in forbidden
                    }
                }
            }
        }
    })
