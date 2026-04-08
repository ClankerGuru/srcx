package zone.clanker.gradle.srcx

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.file.shouldExist
import io.kotest.matchers.file.shouldNotExist
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.gradle.testfixtures.ProjectBuilder
import org.gradle.testkit.runner.GradleRunner
import java.io.File

class SrcxContextCleanTest :
    BehaviorSpec({

        fun tempDir(): File =
            File.createTempFile("srcx-cc", "").apply {
                delete()
                mkdirs()
                deleteOnExit()
            }

        val plugin = Srcx.SettingsPlugin()

        given("Srcx constants") {

            `when`("accessing new constants") {
                then("TASK_CONTEXT is srcx-context") {
                    Srcx.TASK_CONTEXT shouldBe "srcx-context"
                }

                then("TASK_CLEAN is srcx-clean") {
                    Srcx.TASK_CLEAN shouldBe "srcx-clean"
                }
            }
        }

        given("cleanOutputDir") {

            `when`("output directory exists with files") {
                val outputDir = tempDir()
                File(outputDir, "sub").mkdirs()
                File(outputDir, "index.md").writeText("# test")
                File(outputDir, "sub/symbols.md").writeText("# sub")
                File(outputDir, ".gitignore").writeText("*\n")

                plugin.cleanOutputDir(outputDir)

                then("the directory is deleted") {
                    outputDir.shouldNotExist()
                }
            }

            `when`("output directory does not exist") {
                val outputDir = File(tempDir(), "nonexistent")

                then("it prints nothing to clean") {
                    plugin.cleanOutputDir(outputDir)
                }
            }
        }

        given("task registration via ProjectBuilder") {
            val extension = Srcx.SettingsExtension()

            `when`("registering tasks") {
                val projectDir = tempDir()
                projectDir.resolve("build.gradle.kts").writeText("")
                val project =
                    ProjectBuilder
                        .builder()
                        .withProjectDir(projectDir)
                        .build()
                project.pluginManager.apply("java-library")

                plugin.registerTasks(project, extension)

                then("srcx-context task is registered") {
                    project.tasks.findByName(Srcx.TASK_CONTEXT) shouldBe
                        project.tasks.getByName(Srcx.TASK_CONTEXT)
                }

                then("srcx-clean task is registered") {
                    project.tasks.findByName(Srcx.TASK_CLEAN) shouldBe
                        project.tasks.getByName(Srcx.TASK_CLEAN)
                }

                then("executing srcx-clean doLast actions cleans output") {
                    val srcxDir = File(projectDir, ".srcx")
                    srcxDir.mkdirs()
                    File(srcxDir, "test.md").writeText("test")

                    val task = project.tasks.getByName(Srcx.TASK_CLEAN)
                    task.actions.forEach { it.execute(task) }

                    srcxDir.shouldNotExist()
                }
            }
        }

        given("srcx-context and srcx-clean in TestKit") {

            fun tempProject(): File =
                File.createTempFile("srcx-ctx-tk", "").apply {
                    delete()
                    mkdirs()
                    deleteOnExit()
                }

            fun File.withProject(): File {
                resolve("settings.gradle.kts").writeText(
                    """
                    plugins {
                        id("zone.clanker.gradle.srcx")
                    }
                    rootProject.name = "ctx-test"
                    """.trimIndent(),
                )
                resolve("build.gradle.kts").writeText(
                    """
                    plugins { kotlin("jvm") version "2.1.20" }
                    """.trimIndent(),
                )
                val srcDir = resolve("src/main/kotlin/com/example")
                srcDir.mkdirs()
                srcDir.resolve("App.kt").writeText(
                    """
                    package com.example

                    class App {
                        fun run() = "go"
                    }
                    """.trimIndent(),
                )
                return this
            }

            fun File.gradle(vararg args: String) =
                GradleRunner
                    .create()
                    .withProjectDir(this)
                    .withPluginClasspath()
                    .withArguments(*args, "--stacktrace")

            `when`("running srcx-context") {
                val projectDir = tempProject().withProject()

                then("it runs generate and dashboard as dependencies") {
                    val result = projectDir.gradle(Srcx.TASK_CONTEXT).build()
                    result.output shouldContain "srcx: context written"
                    result.output shouldContain "srcx: context written"

                    val dashboardFile = projectDir.resolve(".srcx/context.md")
                    dashboardFile.shouldExist()
                    val content = dashboardFile.readText()
                    content shouldContain "# ctx-test"
                }
            }

            `when`("running srcx-clean after generate") {
                val projectDir = tempProject().withProject()

                then("it deletes the .srcx directory") {
                    projectDir.gradle(Srcx.TASK_CONTEXT).build()
                    projectDir.resolve(".srcx").shouldExist()

                    val result = projectDir.gradle(Srcx.TASK_CLEAN).build()
                    result.output shouldContain "srcx: deleted"
                    projectDir.resolve(".srcx").shouldNotExist()
                }
            }

            `when`("listing tasks") {
                val projectDir = tempProject().withProject()

                then("both tasks are visible") {
                    val result = projectDir.gradle("tasks", "--group=srcx").build()
                    result.output shouldContain "srcx-context"
                    result.output shouldContain "srcx-clean"
                }
            }
        }
    })
