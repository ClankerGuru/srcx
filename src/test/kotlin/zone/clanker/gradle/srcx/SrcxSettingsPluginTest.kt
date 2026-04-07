package zone.clanker.gradle.srcx

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.file.shouldExist
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.gradle.testfixtures.ProjectBuilder
import java.io.File

/**
 * Unit tests for [Srcx.SettingsPlugin] internal methods.
 *
 * Tests the plugin's core logic directly without TestKit,
 * ensuring code coverage for extractProjectSummary, writeProjectReport,
 * writeGitignore, buildFileName, and runParallel.
 */
class SrcxSettingsPluginTest :
    BehaviorSpec({

        fun tempDir(): File =
            File.createTempFile("srcx-unit", "").apply {
                delete()
                mkdirs()
                deleteOnExit()
            }

        given("extractProjectSummary") {
            val plugin = Srcx.SettingsPlugin()

            `when`("project has source files") {
                val projectDir = tempDir()
                val srcDir = File(projectDir, "src/main/kotlin/com/example")
                srcDir.mkdirs()
                srcDir.resolve("Hello.kt").writeText(
                    """
                    package com.example

                    class Hello {
                        val greeting = "hi"
                        fun greet(): String = greeting
                    }
                    """.trimIndent(),
                )
                projectDir.resolve("build.gradle.kts").writeText("")

                val project =
                    ProjectBuilder
                        .builder()
                        .withProjectDir(projectDir)
                        .build()
                project.pluginManager.apply("java-library")

                val summary = plugin.extractProjectSummary(project, project)

                then("symbols are extracted") {
                    summary.symbols.size shouldBe 3
                }

                then("project path is set") {
                    summary.projectPath shouldBe ":"
                }

                then("build file is detected") {
                    summary.buildFile shouldBe "build.gradle.kts"
                }

                then("source dirs are detected") {
                    summary.sourceDirs shouldBe listOf("src/main/kotlin")
                }

                projectDir.deleteRecursively()
            }

            `when`("project has no source files") {
                val projectDir = tempDir()
                projectDir.resolve("build.gradle.kts").writeText("")

                val project =
                    ProjectBuilder
                        .builder()
                        .withProjectDir(projectDir)
                        .build()
                project.pluginManager.apply("java-library")

                val summary = plugin.extractProjectSummary(project, project)

                then("symbols list is empty") {
                    summary.symbols.size shouldBe 0
                }

                projectDir.deleteRecursively()
            }

            `when`("project is a subproject (not root)") {
                val rootDir = tempDir()
                rootDir.resolve("build.gradle.kts").writeText("")

                val rootProject =
                    ProjectBuilder
                        .builder()
                        .withProjectDir(rootDir)
                        .build()

                val subDir = File(rootDir, "sub")
                subDir.mkdirs()
                subDir.resolve("build.gradle.kts").writeText("")

                val subProject =
                    ProjectBuilder
                        .builder()
                        .withProjectDir(subDir)
                        .withParent(rootProject)
                        .withName("sub")
                        .build()
                subProject.pluginManager.apply("java-library")

                val summary = plugin.extractProjectSummary(subProject, rootProject)

                then("subprojects list is empty for non-root projects") {
                    summary.subprojects.size shouldBe 0
                }

                rootDir.deleteRecursively()
            }
        }

        given("buildFileName") {
            val plugin = Srcx.SettingsPlugin()

            `when`("project has build.gradle.kts") {
                val projectDir = tempDir()
                projectDir.resolve("build.gradle.kts").writeText("")
                val project =
                    ProjectBuilder
                        .builder()
                        .withProjectDir(projectDir)
                        .build()

                then("it returns build.gradle.kts") {
                    plugin.buildFileName(project) shouldBe "build.gradle.kts"
                }

                projectDir.deleteRecursively()
            }

            `when`("project has build.gradle") {
                val projectDir = tempDir()
                projectDir.resolve("build.gradle").writeText("")
                val project =
                    ProjectBuilder
                        .builder()
                        .withProjectDir(projectDir)
                        .build()

                then("it returns build.gradle") {
                    plugin.buildFileName(project) shouldBe "build.gradle"
                }

                projectDir.deleteRecursively()
            }

            `when`("project has no build file") {
                val projectDir = tempDir()
                val project =
                    ProjectBuilder
                        .builder()
                        .withProjectDir(projectDir)
                        .build()

                then("it returns none") {
                    plugin.buildFileName(project) shouldBe "none"
                }

                projectDir.deleteRecursively()
            }
        }

        given("writeProjectReport") {
            val plugin = Srcx.SettingsPlugin()
            val extension = Srcx.SettingsExtension()

            `when`("writing a report for a subproject") {
                val projectDir = tempDir()
                val project =
                    ProjectBuilder
                        .builder()
                        .withProjectDir(projectDir)
                        .build()
                project.pluginManager.apply("java-library")

                val summary = plugin.extractProjectSummary(project, project)
                plugin.writeProjectReport(project, summary, extension)

                then("report file is created") {
                    File(projectDir, ".srcx/root/symbols.md").shouldExist()
                }

                then("report contains project path") {
                    val content = File(projectDir, ".srcx/root/symbols.md").readText()
                    content shouldContain "# :"
                }

                projectDir.deleteRecursively()
            }
        }

        given("writeGitignore") {
            val plugin = Srcx.SettingsPlugin()
            val extension = Srcx.SettingsExtension()

            `when`("writing gitignore") {
                val projectDir = tempDir()
                val project =
                    ProjectBuilder
                        .builder()
                        .withProjectDir(projectDir)
                        .build()

                plugin.writeGitignore(project, extension)

                then(".gitignore is created with wildcard") {
                    val gitignore = File(projectDir, ".srcx/.gitignore")
                    gitignore.shouldExist()
                    gitignore.readText() shouldBe "*\n"
                }

                projectDir.deleteRecursively()
            }
        }

        given("runParallel") {
            val plugin = Srcx.SettingsPlugin()

            `when`("running with empty project list") {
                then("it prints no projects message") {
                    plugin.runParallel(emptyList()) { "OK" }
                }
            }

            `when`("running with projects") {
                val projectDir = tempDir()
                val project =
                    ProjectBuilder
                        .builder()
                        .withProjectDir(projectDir)
                        .build()

                then("it executes work for each project") {
                    plugin.runParallel(listOf(project)) { p -> "OK ${p.path}" }
                }

                projectDir.deleteRecursively()
            }
        }

        given("SettingsExtension") {

            `when`("created with defaults") {
                val extension = Srcx.SettingsExtension()

                then("outputDir defaults to .srcx") {
                    extension.outputDir shouldBe ".srcx"
                }
            }

            `when`("outputDir is changed") {
                val extension = Srcx.SettingsExtension()
                extension.outputDir = ".custom-output"

                then("outputDir reflects the new value") {
                    extension.outputDir shouldBe ".custom-output"
                }
            }
        }

        given("Srcx constants") {

            `when`("accessing constants") {
                then("GROUP is srcx") {
                    Srcx.GROUP shouldBe "srcx"
                }

                then("EXTENSION_NAME is srcx") {
                    Srcx.EXTENSION_NAME shouldBe "srcx"
                }

                then("OUTPUT_DIR is .srcx") {
                    Srcx.OUTPUT_DIR shouldBe ".srcx"
                }

                then("TASK_GENERATE is srcx-generate") {
                    Srcx.TASK_GENERATE shouldBe "srcx-generate"
                }

                then("TASK_DASHBOARD is srcx-dashboard") {
                    Srcx.TASK_DASHBOARD shouldBe "srcx-dashboard"
                }
            }
        }

        given("registerTasks") {
            val plugin = Srcx.SettingsPlugin()
            val extension = Srcx.SettingsExtension()

            `when`("registering tasks on a root project") {
                val projectDir = tempDir()
                projectDir.resolve("build.gradle.kts").writeText("")
                val project =
                    ProjectBuilder
                        .builder()
                        .withProjectDir(projectDir)
                        .build()
                project.pluginManager.apply("java-library")

                plugin.registerTasks(project, extension)

                then("srcx-generate task is registered") {
                    project.tasks.findByName(Srcx.TASK_GENERATE) shouldBe
                        project.tasks.getByName(Srcx.TASK_GENERATE)
                }

                then("srcx-dashboard task is registered") {
                    project.tasks.findByName(Srcx.TASK_DASHBOARD) shouldBe
                        project.tasks.getByName(Srcx.TASK_DASHBOARD)
                }

                then("executing srcx-generate doLast actions") {
                    val task = project.tasks.getByName(Srcx.TASK_GENERATE)
                    task.actions.forEach { it.execute(task) }
                    File(projectDir, ".srcx/root/symbols.md").shouldExist()
                    File(projectDir, ".srcx/.gitignore").shouldExist()
                }

                then("executing srcx-dashboard doLast actions") {
                    val task = project.tasks.getByName(Srcx.TASK_DASHBOARD)
                    task.actions.forEach { it.execute(task) }
                    File(projectDir, ".srcx/index.md").shouldExist()
                }

                projectDir.deleteRecursively()
            }
        }

        given("collectProjects") {
            val plugin = Srcx.SettingsPlugin()

            `when`("called on a root project") {
                val projectDir = tempDir()
                val project =
                    ProjectBuilder
                        .builder()
                        .withProjectDir(projectDir)
                        .build()

                then("it returns the root project") {
                    val projects = plugin.collectProjects(project)
                    projects.size shouldBe 1
                    projects[0] shouldBe project
                }

                projectDir.deleteRecursively()
            }
        }
    })
