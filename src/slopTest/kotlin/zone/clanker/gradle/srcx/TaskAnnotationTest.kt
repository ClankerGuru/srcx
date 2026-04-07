package zone.clanker.gradle.srcx

import com.lemonappdev.konsist.api.Konsist
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty

/**
 * Enforces that no task classes exist in srcx.
 *
 * Unlike wrkx which has per-repo task classes, srcx registers all
 * tasks inline in the SettingsPlugin. There should be no standalone
 * Task classes in the codebase.
 */
class TaskAnnotationTest :
    BehaviorSpec({

        val mainScope = Konsist.scopeFromSourceSet("main")

        given("no standalone task classes") {

            `when`("examining all classes in main source") {
                val taskClasses =
                    mainScope
                        .classes()
                        .filter { it.name.endsWith("Task") }
                        .filter { it.packagee?.name?.contains("srcx") == true }

                then("no class is named as a Task") {
                    taskClasses.shouldBeEmpty()
                }
            }
        }
    })
