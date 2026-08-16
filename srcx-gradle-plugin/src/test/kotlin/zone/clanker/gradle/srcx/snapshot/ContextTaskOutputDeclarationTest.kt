package zone.clanker.gradle.srcx.snapshot

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldNotBe
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectories
import zone.clanker.gradle.srcx.task.ContextTask
import zone.clanker.gradle.srcx.task.IncludedBuildInfo
import zone.clanker.gradle.srcx.task.includedBuildReportDirectories
import java.io.File

class ContextTaskOutputDeclarationTest :
    BehaviorSpec({
        given("included-build reports written by the root context task") {
            val first = IncludedBuildInfo("first", File("../first"), "../first", emptyList())
            val second = IncludedBuildInfo("second", File("../second"), "../second", emptyList())

            `when`("the task models its output directories") {
                then("every existing included-build write has a deterministic declared directory") {
                    includedBuildReportDirectories(listOf(second, first), ".srcx") shouldContainExactly
                        listOf(File("../first/.srcx"), File("../second/.srcx"))
                }

                then("the collection is a Gradle output rather than undeclared local mutation") {
                    ContextTask::class.java
                        .getMethod("getIncludedBuildOutputDirectories")
                        .getAnnotation(OutputDirectories::class.java) shouldNotBe null
                }

                then("source-set ownership participates in Gradle up-to-date checks") {
                    ContextTask::class.java
                        .getMethod("getSourceLayoutFingerprint")
                        .getAnnotation(Input::class.java) shouldNotBe null
                }
            }
        }
    })
