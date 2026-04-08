package zone.clanker.gradle.srcx

import com.lemonappdev.konsist.api.Konsist
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty

/**
 * Enforces that no task classes exist in srcx outside the task package.
 *
 * All tasks live in the root plugin class (Srcx.SettingsPlugin).
 * The task/ package is reserved for future Gradle task classes
 * if the plugin grows to need them.
 */
class TaskAnnotationTest :
    BehaviorSpec({

        val mainScope = Konsist.scopeFromSourceSet("main")

        given("no standalone task classes outside task package") {

            `when`("examining all classes in main source") {
                val taskClasses =
                    mainScope
                        .classes()
                        .filter { it.name.endsWith("Task") }
                        .filter { it.packagee?.name?.contains("srcx") == true }
                        .filter { it.packagee?.name?.contains("srcx.task") != true }

                then("no class outside task/ is named as a Task") {
                    taskClasses.shouldBeEmpty()
                }
            }
        }
    })
