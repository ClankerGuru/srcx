package zone.clanker.srcx.atlas

import com.lemonappdev.konsist.api.Konsist
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty

class TaskAnnotationTest :
    BehaviorSpec({
        val mainScope = Konsist.scopeFromSourceSet("commonMain")

        given("no standalone task classes outside task package") {
            `when`("examining all classes in main source") {
                val taskClasses =
                    mainScope
                        .classes()
                        .filter { it.name.endsWith("Task") }

                then("no class is named as a Task") {
                    taskClasses.shouldBeEmpty()
                }
            }
        }
    })
