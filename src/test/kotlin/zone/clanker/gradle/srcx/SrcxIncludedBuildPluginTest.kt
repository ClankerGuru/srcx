package zone.clanker.gradle.srcx

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.file.shouldExist
import io.kotest.matchers.string.shouldContain
import org.gradle.testkit.runner.GradleRunner
import java.io.File

class SrcxIncludedBuildPluginTest :
    BehaviorSpec({

        fun tempProject(): File =
            File.createTempFile("srcx-ib-test", "").apply {
                delete()
                mkdirs()
                deleteOnExit()
            }

        @Suppress("LongMethod")
        fun File.withIncludedBuildSetup(): File {
            val libDir = resolve("lib-build")
            libDir.mkdirs()
            libDir.resolve("settings.gradle.kts").writeText(
                """
                rootProject.name = "lib-build"
                """.trimIndent(),
            )
            libDir.resolve("build.gradle.kts").writeText(
                """
                plugins { kotlin("jvm") version "2.1.20" }
                group = "com.example"
                version = "1.0.0"
                """.trimIndent(),
            )
            val libSrc = File(libDir, "src/main/kotlin/com/example/lib")
            libSrc.mkdirs()
            libSrc.resolve("Lib.kt").writeText(
                """
                package com.example.lib

                class Lib {
                    val name = "lib"
                    fun greet(): String = "Hello from lib"
                }
                """.trimIndent(),
            )
            val libTestSrc = File(libDir, "src/test/kotlin/com/example/lib")
            libTestSrc.mkdirs()
            libTestSrc.resolve("LibTest.kt").writeText(
                """
                package com.example.lib

                class LibTest {
                    fun testGreet() {}
                }
                """.trimIndent(),
            )

            resolve("settings.gradle.kts").writeText(
                """
                plugins {
                    id("zone.clanker.gradle.srcx")
                }
                rootProject.name = "test-root"
                include(":app")
                includeBuild("lib-build")
                """.trimIndent(),
            )
            resolve("build.gradle.kts").writeText("plugins { base }")

            val appDir = resolve("app")
            appDir.mkdirs()
            appDir.resolve("build.gradle.kts").writeText(
                """
                plugins { kotlin("jvm") version "2.1.20" }
                dependencies { implementation("com.example:lib-build:1.0.0") }
                """.trimIndent(),
            )
            val appSrc = File(appDir, "src/main/kotlin/com/example/app")
            appSrc.mkdirs()
            appSrc.resolve("App.kt").writeText(
                """
                package com.example.app

                class App {
                    fun run(): String = "running"
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

        given("srcx with included builds") {

            `when`("running srcx-context") {
                val projectDir = tempProject().withIncludedBuildSetup()

                then("it generates reports in the root project") {
                    projectDir.gradle(Srcx.TASK_CONTEXT).build()

                    projectDir.resolve(".srcx/app/context.md").shouldExist()
                    projectDir.resolve(".srcx/root/context.md").shouldExist()
                }

                then("it generates reports inside the included build's own directory") {
                    projectDir.gradle(Srcx.TASK_CONTEXT).build()

                    val libSrcx = projectDir.resolve("lib-build/.srcx")
                    val libDashboard = libSrcx.resolve("context.md")
                    libDashboard.shouldExist()
                    val libContent = libDashboard.readText()
                    libContent shouldContain "# lib-build"
                    libContent shouldContain "## main"

                    val libSymbols = libSrcx.resolve("root/context.md")
                    libSymbols.shouldExist()
                    val symbolsContent = libSymbols.readText()
                    symbolsContent shouldContain "Lib"
                    symbolsContent shouldContain "## main"
                    symbolsContent shouldContain "## test"
                    symbolsContent shouldContain "LibTest"
                }
            }

            `when`("running srcx-context") {
                val projectDir = tempProject().withIncludedBuildSetup()

                then("dashboard shows included build with project and symbol counts") {
                    projectDir.gradle(Srcx.TASK_CONTEXT).build()

                    val index = projectDir.resolve(".srcx/context.md")
                    index.shouldExist()
                    val content = index.readText()
                    content shouldContain "## Included Builds"
                    content shouldContain "lib-build"
                    content shouldContain "| Build | Projects | Symbols | Warnings | Context |"
                }
            }
        }
    })
