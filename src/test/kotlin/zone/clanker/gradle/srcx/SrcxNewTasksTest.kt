package zone.clanker.gradle.srcx

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.file.shouldExist
import io.kotest.matchers.string.shouldContain
import org.gradle.testkit.runner.GradleRunner
import java.io.File

class SrcxNewTasksTest :
    BehaviorSpec({

        fun tempProject(): File =
            File.createTempFile("srcx-tasks", "").apply {
                delete()
                mkdirs()
                deleteOnExit()
            }

        fun File.withMultiSourceSetProject(): File {
            resolve("settings.gradle.kts").writeText(
                """
                plugins {
                    id("zone.clanker.gradle.srcx")
                }
                rootProject.name = "test-workspace"
                include(":app")
                """.trimIndent(),
            )
            resolve("build.gradle.kts").writeText("plugins { base }")

            resolve("app").mkdirs()
            resolve("app/build.gradle.kts").writeText(
                """
                plugins { kotlin("jvm") version "2.1.20" }
                """.trimIndent(),
            )

            val mainDir = resolve("app/src/main/kotlin/com/example")
            mainDir.mkdirs()
            mainDir.resolve("App.kt").writeText(
                """
                package com.example

                class App {
                    fun run(): String = "running"
                }
                """.trimIndent(),
            )

            val testDir = resolve("app/src/test/kotlin/com/example")
            testDir.mkdirs()
            testDir.resolve("AppTest.kt").writeText(
                """
                package com.example

                class AppTest {
                    fun testRun() {}
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

        given("srcx tasks registration") {

            `when`("listing tasks in the srcx group") {
                val projectDir = tempProject().withMultiSourceSetProject()

                then("both tasks are registered") {
                    val result = projectDir.gradle("tasks", "--group=srcx").build()
                    result.output shouldContain "srcx-context"
                    result.output shouldContain "srcx-clean"
                }

                projectDir.deleteRecursively()
            }
        }

        given("srcx-context with source sets") {

            `when`("running on a project with main and test sources") {
                val projectDir = tempProject().withMultiSourceSetProject()

                then("report groups symbols by source set") {
                    projectDir.gradle(Srcx.TASK_CONTEXT).build()
                    val report = projectDir.resolve(".srcx/app/symbols.md")
                    report.shouldExist()
                    val content = report.readText()
                    content shouldContain "## main"
                    content shouldContain "## test"
                    content shouldContain "App"
                    content shouldContain "AppTest"
                }

                projectDir.deleteRecursively()
            }
        }

        given("srcx-context with source sets") {

            `when`("running on a multi-source-set project") {
                val projectDir = tempProject().withMultiSourceSetProject()

                then("dashboard shows source set names") {
                    projectDir.gradle(Srcx.TASK_CONTEXT).build()
                    val index = projectDir.resolve(".srcx/context.md")
                    index.shouldExist()
                    val content = index.readText()
                    content shouldContain "Source Sets"
                    content shouldContain "main, test"
                }

                projectDir.deleteRecursively()
            }
        }
    })
