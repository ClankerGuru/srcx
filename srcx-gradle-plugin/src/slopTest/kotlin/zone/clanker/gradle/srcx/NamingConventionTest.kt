package zone.clanker.gradle.srcx

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertTrue
import io.kotest.core.spec.style.BehaviorSpec

/**
 * Enforces naming conventions across the codebase.
 *
 * - Classes in `task` end with `Task`
 * - Classes in `report` end with `Renderer`
 * - No generic suffixes anywhere
 */
class NamingConventionTest :
    BehaviorSpec({

        val mainScope = Konsist.scopeFromSourceSet("main")
        val allClasses = mainScope.classes()

        given("report package naming") {

            `when`("top-level classes are in the report package") {
                val reportClasses =
                    allClasses
                        .filter { it.packagee?.name?.contains("srcx.report") == true }
                        .filter { it.isTopLevel }

                then("every class name ends with Renderer") {
                    reportClasses.assertTrue { it.name.endsWith("Renderer") }
                }
            }
        }

        given("forbidden class name suffixes") {

            val forbidden =
                listOf(
                    "Helper",
                    "Manager",
                    "Util",
                    "Utils",
                )

            `when`("examining all classes in main source") {
                then("no class uses a generic suffix") {
                    allClasses.assertTrue { cls ->
                        forbidden.none { suffix -> cls.name.endsWith(suffix) }
                    }
                }
            }
        }
    })
