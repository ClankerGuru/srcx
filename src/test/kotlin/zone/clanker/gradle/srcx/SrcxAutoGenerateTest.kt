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

            `when`("autoGenerate is enabled and the base plugin is applied") {
                val projectDir = tempDir()
                projectDir.resolve("build.gradle.kts").writeText("")
                val project =
                    ProjectBuilder
                        .builder()
                        .withProjectDir(projectDir)
                        .build()
                project.pluginManager.apply("base")

                val extension = newExtension().apply { autoGenerate.set(true) }
                plugin.registerTasks(project, extension)

                then("assemble depends on srcx-context") {
                    val assemble = project.tasks.getByName("assemble")
                    val dependencies = assemble.taskDependencies.getDependencies(assemble)
                    dependencies.any { it.name == Srcx.TASK_CONTEXT } shouldBe true
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
                project.pluginManager.apply("base")

                val extension = newExtension()
                plugin.registerTasks(project, extension)

                then("assemble does not depend on srcx-context") {
                    val assemble = project.tasks.getByName("assemble")
                    val dependencies = assemble.taskDependencies.getDependencies(assemble)
                    dependencies.none { it.name == Srcx.TASK_CONTEXT } shouldBe true
                }
            }

            `when`("clean and assemble are requested together") {
                val projectDir = tempDir()
                projectDir.resolve("build.gradle.kts").writeText("")
                val project =
                    ProjectBuilder
                        .builder()
                        .withProjectDir(projectDir)
                        .build()
                project.pluginManager.apply("base")

                val extension = newExtension().apply { autoGenerate.set(true) }
                plugin.registerTasks(project, extension)

                then("srcx-context runs after srcx-clean") {
                    val context = project.tasks.getByName(Srcx.TASK_CONTEXT)
                    val ordering = context.mustRunAfter.getDependencies(context)
                    ordering.any { it.name == Srcx.TASK_CLEAN } shouldBe true
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

                then("assemble generates the static documentation site") {
                    val result =
                        GradleRunner
                            .create()
                            .withProjectDir(projectDir)
                            .withPluginClasspath()
                            .withArguments("assemble", "--stacktrace")
                            .build()
                    result.output shouldContain ":srcx-context"
                    projectDir.resolve(".srcx/site/index.html").isFile shouldBe true
                    projectDir.resolve(".srcx/site/report.html").isFile shouldBe true
                }
            }
        }
    })
