package zone.clanker.srcx.atlas.site

import com.lemonappdev.konsist.api.Konsist
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty

class TaskAnnotationTest :
    BehaviorSpec({
        given("no standalone task classes") {
            `when`("examining all classes in main source") {
                then("no class is named as a Task") {
                    Konsist
                        .scopeFromSourceSet("wasmJsMain")
                        .classes()
                        .filter { it.name.endsWith("Task") }
                        .shouldBeEmpty()
                }
            }
        }
    })
