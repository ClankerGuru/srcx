package zone.clanker.gradle.srcx

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.gradle.testfixtures.ProjectBuilder
import org.gradle.testkit.runner.GradleRunner
import java.io.File

private fun newExtension(): Srcx.SettingsExtension {
    val objects =
        ProjectBuilder
            .builder()
            .build()
            .objects
    return objects
        .newInstance(Srcx.SettingsExtension::class.java)
        .also {
            it.outputDir.convention(Srcx.OUTPUT_DIR)
            it.autoGenerate.convention(false)
        }
}

class SrcxAutoGenerateTest :
    BehaviorSpec({

        fun tempDir(): File =
            File.createTempFile("srcx-auto", "").apply {
                delete()
                mkdirs()
                deleteOnExit()
            }

        given("wireAutoGenerate via ProjectBuilder") {
            val plugin = Srcx.SettingsPlugin()

            `when`("autoGenerate is enabled and compileKotlin task exists") {
                val projectDir = tempDir()
                projectDir.resolve("build.gradle.kts").writeText("")
                val project =
                    ProjectBuilder
                        .builder()
                        .withProjectDir(projectDir)
                        .build()

                val extension = newExtension().apply { autoGenerate.set(true) }
                plugin.registerTasks(project, extension)

                project.tasks.register("compileKotlin")

                then("compileKotlin is finalized by srcx-context") {
                    val compileTask = project.tasks.getByName("compileKotlin")
                    val finalizers = compileTask.finalizedBy.getDependencies(compileTask)
                    finalizers.any { it.name == Srcx.TASK_CONTEXT } shouldBe true
                }
            }

            `when`("autoGenerate is enabled and compileJava task exists") {
                val projectDir = tempDir()
                projectDir.resolve("build.gradle.kts").writeText("")
                val project =
                    ProjectBuilder
                        .builder()
                        .withProjectDir(projectDir)
                        .build()

                val extension = newExtension().apply { autoGenerate.set(true) }
                plugin.registerTasks(project, extension)

                project.tasks.register("compileJava")

                then("compileJava is finalized by srcx-context") {
                    val compileTask = project.tasks.getByName("compileJava")
                    val finalizers = compileTask.finalizedBy.getDependencies(compileTask)
                    finalizers.any { it.name == Srcx.TASK_CONTEXT } shouldBe true
                }
            }

            `when`("autoGenerate is disabled") {
                val projectDir = tempDir()
                projectDir.resolve("build.gradle.kts").writeText("")
                val project =
                    ProjectBuilder
                        .builder()
                        .withProjectDir(projectDir)
                        .build()

                val extension = newExtension()
                plugin.registerTasks(project, extension)

                project.tasks.register("compileKotlin")

                then("compileKotlin is NOT finalized by srcx-context") {
                    val compileTask = project.tasks.getByName("compileKotlin")
                    val finalizers = compileTask.finalizedBy.getDependencies(compileTask)
                    finalizers.none { it.name == Srcx.TASK_CONTEXT } shouldBe true
                }
            }
        }

        given("autoGenerate DSL in TestKit") {

            `when`("settings.gradle.kts uses srcx { autoGenerate = true }") {
                val projectDir = tempDir()
                projectDir.resolve("settings.gradle.kts").writeText(
                    """
                    plugins {
                        id("zone.clanker.gradle.srcx")
                    }
                    rootProject.name = "auto-test"
                    srcx {
                        autoGenerate.set(true)
                    }
                    """.trimIndent(),
                )
                projectDir.resolve("build.gradle.kts").writeText("plugins { base }")

                then("tasks still register successfully") {
                    val result =
                        GradleRunner
                            .create()
                            .withProjectDir(projectDir)
                            .withPluginClasspath()
                            .withArguments("tasks", "--group=srcx", "--stacktrace")
                            .build()
                    result.output shouldContain "srcx-context"
                }
            }
        }
    })
