package zone.clanker.srcx.atlas

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertTrue
import io.kotest.core.spec.style.BehaviorSpec

class NamingConventionTest :
    BehaviorSpec({
        val allClasses = Konsist.scopeFromSourceSet("commonMain").classes()

        given("forbidden class name suffixes") {
            val forbidden = listOf("Helper", "Manager", "Util", "Utils")

            `when`("examining all classes in main source") {
                then("no class uses a generic suffix") {
                    allClasses.assertTrue { cls ->
                        forbidden.none { suffix -> cls.name.endsWith(suffix) }
                    }
                }
            }
        }
    })
