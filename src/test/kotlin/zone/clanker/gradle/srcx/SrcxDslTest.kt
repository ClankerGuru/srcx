package zone.clanker.gradle.srcx

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.gradle.testfixtures.ProjectBuilder
import org.gradle.testkit.runner.GradleRunner
import java.io.File

/**
 * Tests for [srcx] DSL extension function.
 *
 * Verifies the type-safe DSL accessor works correctly in settings.gradle.kts.
 */
class SrcxDslTest :
    BehaviorSpec({

        fun tempProject(): File =
            File.createTempFile("srcx-dsl", "").apply {
                delete()
                mkdirs()
                deleteOnExit()
            }

        given("the srcx DSL block") {

            `when`("used in settings.gradle.kts") {
                val projectDir = tempProject()
                projectDir.resolve("settings.gradle.kts").writeText(
                    """
                    plugins {
                        id("zone.clanker.gradle.srcx")
                    }
                    rootProject.name = "dsl-test"
                    srcx {
                        outputDir.set(".custom-srcx")
                    }
                    """.trimIndent(),
                )
                projectDir.resolve("build.gradle.kts").writeText("plugins { base }")

                then("the build succeeds with custom outputDir") {
                    val result =
                        GradleRunner
                            .create()
                            .withProjectDir(projectDir)
                            .withPluginClasspath()
                            .withArguments("tasks", "--group=srcx", "--stacktrace")
                            .build()
                    result.output shouldNotBe null
                }

                projectDir.deleteRecursively()
            }

            `when`("default extension values are used") {
                val objects =
                    ProjectBuilder
                        .builder()
                        .build()
                        .objects
                val extension = objects.newInstance(Srcx.SettingsExtension::class.java)
                extension.outputDir.convention(Srcx.OUTPUT_DIR)
                extension.autoGenerate.convention(false)

                then("outputDir is .srcx") {
                    extension.outputDir.get() shouldBe ".srcx"
                }
            }
        }
    })
