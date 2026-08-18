package zone.clanker.srcx.atlas.site

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertTrue
import io.kotest.core.spec.style.BehaviorSpec

class ForbiddenPatternTest :
    BehaviorSpec({
        val mainScope = Konsist.scopeFromSourceSet("wasmJsMain")

        given("error handling uses runCatching, not try-catch") {
            `when`("examining all main source functions") {
                then("no function contains a try-catch block") {
                    mainScope
                        .functions()
                        .filter { function -> function.containingFile.packagee?.name?.contains("srcx.atlas") == true }
                        .assertTrue { !it.text.contains("try {") && !it.text.contains("try{") }
                }
            }
        }
    })
