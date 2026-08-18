package zone.clanker.srcx.atlas

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertTrue
import io.kotest.core.spec.style.BehaviorSpec

class ForbiddenPatternTest :
    BehaviorSpec({
        val mainScope = Konsist.scopeFromSourceSet("commonMain")

        given("error handling uses runCatching, not try-catch") {
            `when`("examining all main source functions") {
                then("no function contains a try-catch block") {
                    mainScope
                        .functions()
                        .filter { function -> function.containingFile.packagee?.name?.contains("srcx.atlas") == true }
                        .assertTrue {
                            !it.text.contains("try {") && !it.text.contains("try{")
                        }
                }
            }
        }

        given("no wildcard imports") {
            `when`("examining all main source files") {
                then("no import uses a wildcard") {
                    mainScope.files.assertTrue { file ->
                        file.imports.none { it.isWildcard }
                    }
                }
            }
        }
    })
