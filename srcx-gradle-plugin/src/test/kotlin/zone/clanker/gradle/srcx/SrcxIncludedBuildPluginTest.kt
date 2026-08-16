package zone.clanker.gradle.srcx

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.file.shouldExist
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
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
            libSrc.resolve("UnusedLib.kt").writeText(
                """
                package com.example.lib

                class UnusedLib
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

                import com.example.lib.Lib

                class App {
                    fun run(): String = Lib().greet()
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

        fun File.withSwitchableIncludedBuilds(): File {
            listOf("feature-lib" to "FeatureApi", "bugfix-lib" to "BugfixApi").forEach { (dirName, className) ->
                val libDir = resolve(dirName)
                libDir.resolve("src/main/java/example").mkdirs()
                libDir.resolve("settings.gradle.kts").writeText("rootProject.name = \"selected-lib\"")
                libDir.resolve("build.gradle.kts").writeText("plugins { java }")
                libDir.resolve("src/main/java/example/$className.java").writeText(
                    """
                    package example;

                    public final class $className {}
                    """.trimIndent(),
                )
            }
            resolve("settings.gradle.kts").writeText(
                """
                plugins {
                    id("zone.clanker.gradle.srcx")
                }
                rootProject.name = "switchable-root"
                val includedDir = providers.gradleProperty("includedDir").orElse("feature-lib").get()
                includeBuild(includedDir) { name = "selected-lib" }
                """.trimIndent(),
            )
            resolve("build.gradle.kts").writeText("plugins { base }")
            return this
        }

        fun File.withConfiguredKmpIncludedBuild(): File {
            val includedBuild = resolve("custom-lib")
            includedBuild.resolve("modules/runtime").mkdirs()
            includedBuild.resolve("settings.gradle.kts").writeText(
                """
                pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
                rootProject.name = "custom-lib"
                include(":impl")
                project(":impl").projectDir = file("modules/runtime")
                """.trimIndent(),
            )
            includedBuild.resolve("build.gradle.kts").writeText("plugins { base }")
            includedBuild.resolve("modules/runtime/build.gradle.kts").writeText(
                """
                plugins { kotlin("multiplatform") version "2.3.0" }
                kotlin {
                    jvm()
                    sourceSets {
                        commonMain {
                            kotlin.srcDir("production")
                            kotlin.srcDir(layout.buildDirectory.dir("generated/sources"))
                        }
                        jvmMain { kotlin.srcDir("platform@jvm") }
                    }
                }
                """.trimIndent(),
            )
            includedBuild.resolve("modules/runtime/production/sample/SharedApi.kt").apply {
                parentFile.mkdirs()
                writeText("package sample\nclass SharedApi")
            }
            includedBuild.resolve("modules/runtime/platform@jvm/sample/JvmBridge.kt").apply {
                parentFile.mkdirs()
                writeText("package sample\nclass JvmBridge")
            }
            includedBuild.resolve("modules/runtime/build/generated/sources/sample/GeneratedApi.kt").apply {
                parentFile.mkdirs()
                writeText("package sample\nclass GeneratedApi")
            }
            resolve("settings.gradle.kts").writeText(
                """
                plugins { id("zone.clanker.gradle.srcx") }
                rootProject.name = "configured-root"
                includeBuild("custom-lib")
                """.trimIndent(),
            )
            resolve("build.gradle.kts").writeText("plugins { base }")
            return this
        }

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

        given("the same included build name at different worktree paths") {
            val projectDir = tempProject().withSwitchableIncludedBuilds()

            `when`("the selected worktree path changes") {
                val first = projectDir.gradle(Srcx.TASK_CONTEXT, "-PincludedDir=feature-lib").build()
                val second = projectDir.gradle(Srcx.TASK_CONTEXT, "-PincludedDir=bugfix-lib").build()

                then("srcx-context regenerates instead of remaining up-to-date") {
                    first.task(":${Srcx.TASK_CONTEXT}")?.outcome shouldBe TaskOutcome.SUCCESS
                    second.task(":${Srcx.TASK_CONTEXT}")?.outcome shouldBe TaskOutcome.SUCCESS
                }

                then("the dashboard and report point to the newly selected worktree") {
                    projectDir.resolve(".srcx/context.md").readText() shouldContain "bugfix-lib/.srcx/context.md"
                    val report = projectDir.resolve("bugfix-lib/.srcx/context.md")
                    report.shouldExist()
                    report.readText() shouldContain "BugfixApi"
                }
            }

            projectDir.deleteRecursively()
        }

        given("a Gradle 9.6.1 included build with a remapped KMP project") {
            val projectDir = tempProject().withConfiguredKmpIncludedBuild()

            `when`("srcx captures the configured included-build source model") {
                val result =
                    projectDir
                        .gradle(Srcx.TASK_CONTEXT)
                        .withGradleVersion("9.6.1")
                        .build()

                then("the remapped project is discovered by its project path") {
                    result.task(":${Srcx.TASK_CONTEXT}")?.outcome shouldBe TaskOutcome.SUCCESS
                    projectDir.resolve("custom-lib/.srcx/impl/context.md").shouldExist()
                }

                then("custom KMP roots retain source-set ownership without generated output") {
                    val report = projectDir.resolve("custom-lib/.srcx/impl/context.md").readText()
                    report shouldContain "## commonMain"
                    report shouldContain "## jvmMain"
                    report shouldContain "SharedApi"
                    report shouldContain "JvmBridge"
                    report.contains("GeneratedApi") shouldBe false
                }
            }

            projectDir.deleteRecursively()
        }
    })
