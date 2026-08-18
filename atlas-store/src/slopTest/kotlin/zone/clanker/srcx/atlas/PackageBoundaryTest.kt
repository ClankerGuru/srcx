package zone.clanker.srcx.atlas

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertTrue
import io.kotest.core.spec.style.BehaviorSpec

class PackageBoundaryTest :
    BehaviorSpec({
        val mainScope = Konsist.scopeFromSourceSet("commonMain")

        given("import direction enforcement") {
            `when`("files are in the atlas store package") {
                val atlasFiles = mainScope.files.filter { it.packagee?.name?.contains("srcx.atlas") == true }

                then("atlas store never imports from the srcx plugin task package") {
                    atlasFiles.assertTrue {
                        it.imports.none { imp -> imp.name.contains("gradle.srcx.task") }
                    }
                }

                then("atlas store never imports from the srcx plugin report package") {
                    atlasFiles.assertTrue {
                        it.imports.none { imp -> imp.name.contains("gradle.srcx.report") }
                    }
                }
            }
        }
    })
