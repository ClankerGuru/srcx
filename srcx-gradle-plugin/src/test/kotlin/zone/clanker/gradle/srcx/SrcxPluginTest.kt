package zone.clanker.gradle.srcx

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.file.shouldExist
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import zone.clanker.gradle.srcx.report.WorkspaceHtmlResourceRenderer
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import java.io.File

/**
 * Gradle TestKit tests for the srcx settings plugin.
 *
 * Each test creates a temporary multi-project workspace, applies the plugin,
 * and verifies symbol extraction and report generation.
 */
class SrcxPluginTest :
    BehaviorSpec({

        fun tempProject(): File =
            File.createTempFile("srcx-test", "").apply {
                delete()
                mkdirs()
                deleteOnExit()
            }

        fun File.withRootBuild(): File {
            resolve("settings.gradle.kts").writeText(
                """
                plugins {
                    id("zone.clanker.gradle.srcx")
                }
                rootProject.name = "test-workspace"
                include(":app", ":lib", ":core")
                """.trimIndent(),
            )
            resolve("build.gradle.kts").writeText("plugins { base }")
            return this
        }

        fun File.withCoreModule(): File {
            resolve("core").mkdirs()
            resolve("core/build.gradle.kts").writeText(
                """
                plugins { kotlin("jvm") version "2.1.20" }
                """.trimIndent(),
            )
            val coreDir = resolve("core/src/main/kotlin/com/example/core")
            coreDir.mkdirs()
            coreDir.resolve("Core.kt").writeText(
                """
                package com.example.core

                class Core {
                    val version = "1.0"
                    fun process(): String = "processed"
                }
                """.trimIndent(),
            )
            return this
        }

        fun File.withLibModule(): File {
            resolve("lib").mkdirs()
            resolve("lib/build.gradle.kts").writeText(
                """
                plugins { kotlin("jvm") version "2.1.20" }
                dependencies { implementation(project(":core")) }
                """.trimIndent(),
            )
            val libDir = resolve("lib/src/main/kotlin/com/example/lib")
            libDir.mkdirs()
            libDir.resolve("Lib.kt").writeText(
                """
                package com.example.lib

                import com.example.core.Core

                class Lib {
                    private val core = Core()
                    fun run(): String = core.process()
                }
                """.trimIndent(),
            )
            return this
        }

        fun File.withAppModule(): File {
            resolve("app").mkdirs()
            resolve("app/build.gradle.kts").writeText(
                """
                plugins { kotlin("jvm") version "2.1.20" }
                dependencies { implementation(project(":lib")) }
                """.trimIndent(),
            )
            val appDir = resolve("app/src/main/kotlin/com/example/app")
            appDir.mkdirs()
            appDir.resolve("App.kt").writeText(
                """
                package com.example.app

                import com.example.lib.Lib

                class App {
                    fun main() {
                        val lib = Lib()
                        println(lib.run())
                    }
                }

                fun entrypoint() {
                    App().main()
                }
                """.trimIndent(),
            )
            return this
        }

        fun File.withMultiProject(): File =
            withRootBuild().withCoreModule().withLibModule().withAppModule()

        fun File.gradle(vararg args: String) =
            GradleRunner
                .create()
                .withProjectDir(this)
                .withPluginClasspath()
                .withArguments(*args, "--stacktrace")

        given("the srcx plugin applied in settings") {

            `when`("plugin is applied to a multi-project build") {
                val projectDir = tempProject().withMultiProject()

                then("the plugin applies without error") {
                    val result = projectDir.gradle("tasks", "--group=srcx").build()
                    result.output shouldContain "srcx-context"
                    result.output shouldContain "srcx-context"
                }
            }

            `when`("srcx-context runs on a multi-project build") {
                val projectDir = tempProject().withMultiProject()

                then("it creates the .srcx directory") {
                    projectDir.gradle(Srcx.TASK_CONTEXT).build()
                    val srcxDir = projectDir.resolve(".srcx")
                    srcxDir.shouldExist()
                }
            }

            `when`("srcx-context produces per-project reports") {
                val projectDir = tempProject().withMultiProject()

                then("each subproject has its own report") {
                    projectDir.gradle(Srcx.TASK_CONTEXT).build()
                    projectDir.resolve(".srcx/app/context.md").shouldExist()
                    projectDir.resolve(".srcx/lib/context.md").shouldExist()
                    projectDir.resolve(".srcx/core/context.md").shouldExist()
                    projectDir.resolve(".srcx/root/context.md").shouldExist()
                }
            }

            `when`("srcx-context runs") {
                val projectDir = tempProject().withMultiProject()

                then("dashboard context.md exists with links to all projects") {
                    projectDir.gradle(Srcx.TASK_CONTEXT).build()
                    val indexFile = projectDir.resolve(".srcx/context.md")
                    indexFile.shouldExist()
                    val content = indexFile.readText()
                    content shouldContain "# test-workspace"
                    content shouldContain ":app"
                    content shouldContain ":lib"
                    content shouldContain ":core"
                }
            }

            `when`("srcx-context renders static documentation") {
                val projectDir = tempProject().withMultiProject()

                then("the standalone page and embedded fragment come from the workspace model") {
                    projectDir.gradle(Srcx.TASK_CONTEXT).build()
                    val standalone = projectDir.resolve(".srcx/site/index.html")
                    val fragment = projectDir.resolve(".srcx/site/report.html")
                    val styles = projectDir.resolve(".srcx/site/${Srcx.HTML_STYLES_FILE}")
                    val d3 = projectDir.resolve(".srcx/site/${Srcx.HTML_D3_FILE}")
                    standalone.shouldExist()
                    fragment.shouldExist()
                    styles.shouldExist()
                    d3.shouldExist()
                    standalone.readText() shouldContain "<!doctype html>"
                    standalone.readText() shouldContain "test-workspace"
                    standalone.readText() shouldContain
                        "<link rel=\"stylesheet\" href=\"${Srcx.HTML_STYLES_FILE}\" data-srcx-theme=\"gort\">"
                    standalone.readText() shouldContain
                        "<script src=\"${Srcx.HTML_D3_FILE}\" data-srcx-vendor=\"d3-7.9.0\"></script>"
                    standalone.readText() shouldContain
                        "<script src=\"${Srcx.HTML_DRAW_FILE}\" data-srcx-owned=\"atlas-draw\"></script>"
                    standalone.readText() shouldContain "<script src=\"${Srcx.HTML_SEED_LOADER_FILE}\"></script>"
                    standalone.readText() shouldNotContain "data-srcx-architecture-data"
                    projectDir.resolve(".srcx/site/${Srcx.HTML_SEED_LOADER_FILE}").shouldExist()
                    projectDir.resolve(".srcx/site/${Srcx.HTML_SQLITE_BYTES_FILE}").shouldExist()
                    standalone.readText() shouldNotContain "<style data-srcx-theme=\"gort\">"
                    fragment.readText() shouldNotContain "<style data-srcx-theme=\"gort\">"
                    styles.readText() shouldBe WorkspaceHtmlResourceRenderer().styles()
                    d3.readText() shouldBe WorkspaceHtmlResourceRenderer().d3Vendor()
                }
            }

            `when`("symbols are extracted from source") {
                val projectDir = tempProject().withMultiProject()

                then("symbols are extracted correctly") {
                    projectDir.gradle(Srcx.TASK_CONTEXT).build()
                    val coreReport = projectDir.resolve(".srcx/core/context.md").readText()
                    coreReport shouldContain "Core"
                    coreReport shouldContain "CLASS"
                    coreReport shouldContain "process"
                    coreReport shouldContain "FUNCTION"
                    coreReport shouldContain "com.example.core"
                }
            }

            `when`("dependencies are extracted") {
                val projectDir = tempProject().withMultiProject()

                then("dependencies appear in the report") {
                    projectDir.gradle(Srcx.TASK_CONTEXT).build()
                    val appReport = projectDir.resolve(".srcx/app/context.md").readText()
                    appReport shouldContain "Dependencies"
                }
            }

            `when`("srcx-context runs") {
                val projectDir = tempProject().withMultiProject()

                then(".srcx/.gitignore is created") {
                    projectDir.gradle(Srcx.TASK_CONTEXT).build()
                    val gitignore = projectDir.resolve(".srcx/.gitignore")
                    gitignore.shouldExist()
                    gitignore.readText().trim() shouldBe "*"
                }
            }

            `when`("srcx-context runs twice") {
                val projectDir = tempProject().withMultiProject()

                then("second run is up-to-date when sources unchanged") {
                    projectDir.gradle(Srcx.TASK_CONTEXT).build()
                    val firstContent = projectDir.resolve(".srcx/core/context.md").readText()

                    val result = projectDir.gradle(Srcx.TASK_CONTEXT).build()
                    result.task(":${Srcx.TASK_CONTEXT}")?.outcome shouldBe TaskOutcome.UP_TO_DATE
                    val secondContent = projectDir.resolve(".srcx/core/context.md").readText()
                    secondContent shouldBe firstContent
                }
            }

            `when`("the generated static page is deleted") {
                val projectDir = tempProject().withMultiProject()

                then("srcx-context regenerates the complete output directory") {
                    projectDir.gradle(Srcx.TASK_CONTEXT).build()
                    val standalone = projectDir.resolve(".srcx/site/index.html")
                    standalone.delete()

                    val result = projectDir.gradle(Srcx.TASK_CONTEXT).build()
                    result.task(":${Srcx.TASK_CONTEXT}")?.outcome shouldBe TaskOutcome.SUCCESS
                    standalone.shouldExist()
                }
            }
        }
    })
