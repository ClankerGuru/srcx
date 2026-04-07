package zone.clanker.gradle.srcx

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertTrue
import io.kotest.core.spec.style.BehaviorSpec

/**
 * Enforces naming conventions across the codebase.
 *
 * Every class must communicate its role through its name:
 * - Classes in `extractor` end with `Extractor` (e.g. `SymbolExtractor`)
 * - Classes in `report` end with `Renderer` (e.g. `DashboardRenderer`)
 * - Value classes in `model` use domain nouns, never generic suffixes
 *
 * Generic names like `Helper`, `Manager`, or `Util` are banned.
 */
class NamingConventionTest :
    BehaviorSpec({

        val mainScope = Konsist.scopeFromSourceSet("main")
        val allClasses = mainScope.classes()

        given("extractor package naming") {

            `when`("top-level classes are in the extractor package") {
                val extractorClasses =
                    allClasses
                        .filter { it.packagee?.name?.contains("srcx.extractor") == true }
                        .filter { it.isTopLevel }

                then("every class name ends with Extractor") {
                    extractorClasses.assertTrue { it.name.endsWith("Extractor") }
                }
            }
        }

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
