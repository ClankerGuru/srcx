package zone.clanker.gradle.srcx

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.file.shouldExist
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.gradle.testfixtures.ProjectBuilder
import zone.clanker.gradle.srcx.report.ReportWriter
import zone.clanker.gradle.srcx.scan.ProjectScanner
import zone.clanker.gradle.srcx.scan.SymbolExtractor
import java.io.File

/**
 * Unit tests for [Srcx] data object utility methods.
 *
 * Tests the core logic directly without TestKit,
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

                val summary = SymbolExtractor.extractProjectSummary(project, project)

                then("symbols are extracted") {
                    summary.symbols.size shouldBe 3
                }

                then("project path is set") {
                    summary.projectPath.value shouldBe ":"
                }

                then("build file is detected") {
                    summary.buildFile shouldBe "build.gradle.kts"
                }

                then("source dirs are detected") {
                    summary.sourceDirs shouldBe listOf("src/main/kotlin")
                }
            }

            `when`("Srcx data object is accessed") {
                @Suppress("RedundantCompanionReference")
                then("data object identity methods work") {
                    Srcx.GROUP shouldBe "srcx"
                    Srcx.toString() shouldBe "Srcx"
                    Srcx.hashCode() shouldBe Srcx.hashCode()
                    @Suppress("KotlinConstantConditions")
                    (Srcx == Srcx) shouldBe true
                    Srcx.TASK_CONTEXT shouldBe "srcx-context"
                    Srcx.TASK_CLEAN shouldBe "srcx-clean"
                    Srcx.EXTENSION_NAME shouldBe "srcx"
                    Srcx.OUTPUT_DIR shouldBe ".srcx"
                }
            }

            `when`("generateIncludedBuildReports with no included builds") {
                then("runs without error") {
                    ReportWriter.generateIncludedBuildReports(emptyList(), Srcx.OUTPUT_DIR)
                }
            }

            `when`("collectIncludedBuildSummaries with no included builds") {
                val result = ReportWriter.collectIncludedBuildSummaries(emptyList())

                then("returns empty map") {
                    result.isEmpty() shouldBe true
                }
            }

            `when`("project has dependencies via Gradle API") {
                val projectDir = tempDir()
                projectDir.resolve("build.gradle.kts").writeText("")
                val project =
                    ProjectBuilder.builder().withProjectDir(projectDir).build()
                project.pluginManager.apply("java-library")
                project.repositories.mavenCentral()
                project.dependencies.add("implementation", "com.google.guava:guava:33.0.0-jre")

                val summary = SymbolExtractor.extractProjectSummary(project, project)

                then("dependencies are extracted") {
                    summary.dependencies.any { it.artifact.value == "guava" } shouldBe true
                    summary.dependencies.first { it.artifact.value == "guava" }.scope shouldBe "implementation"
                }
            }

            `when`("project has main and test source files") {
                val projectDir = tempDir()
                val mainSrcDir = File(projectDir, "src/main/kotlin/com/example")
                mainSrcDir.mkdirs()
                mainSrcDir.resolve("Hello.kt").writeText(
                    """
                    package com.example

                    class Hello {
                        fun greet(): String = "hi"
                    }
                    """.trimIndent(),
                )
                val testSrcDir = File(projectDir, "src/test/kotlin/com/example")
                testSrcDir.mkdirs()
                testSrcDir.resolve("HelloTest.kt").writeText(
                    """
                    package com.example

                    class HelloTest {
                        fun testGreet() {}
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

                val summary = SymbolExtractor.extractProjectSummary(project, project)

                then("source sets are discovered") {
                    summary.sourceSets.size shouldBe 2
                    summary.sourceSets[0].name.value shouldBe "main"
                    summary.sourceSets[1].name.value shouldBe "test"
                }

                then("main source set has 2 symbols") {
                    summary.sourceSets[0].symbols.size shouldBe 2
                }

                then("test source set has 2 symbols") {
                    summary.sourceSets[1].symbols.size shouldBe 2
                }

                then("total symbols are combined") {
                    summary.symbols.size shouldBe 4
                }

                then("source dirs include both") {
                    summary.sourceDirs.size shouldBe 2
                }
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

                val summary = SymbolExtractor.extractProjectSummary(project, project)

                then("symbols list is empty") {
                    summary.symbols.size shouldBe 0
                }
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

                val summary = SymbolExtractor.extractProjectSummary(subProject, rootProject)

                then("subprojects list is empty for non-root projects") {
                    summary.subprojects.size shouldBe 0
                }
            }
        }

        given("buildFileName") {

            `when`("project has build.gradle.kts") {
                val projectDir = tempDir()
                projectDir.resolve("build.gradle.kts").writeText("")
                val project =
                    ProjectBuilder
                        .builder()
                        .withProjectDir(projectDir)
                        .build()

                then("it returns build.gradle.kts") {
                    ProjectScanner.buildFileName(project) shouldBe "build.gradle.kts"
                }
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
                    ProjectScanner.buildFileName(project) shouldBe "build.gradle"
                }
            }

            `when`("project has no build file") {
                val projectDir = tempDir()
                val project =
                    ProjectBuilder
                        .builder()
                        .withProjectDir(projectDir)
                        .build()

                then("it returns none") {
                    ProjectScanner.buildFileName(project) shouldBe "none"
                }
            }
        }

        given("writeProjectReport") {

            `when`("writing a report for a subproject") {
                val projectDir = tempDir()
                val project =
                    ProjectBuilder
                        .builder()
                        .withProjectDir(projectDir)
                        .build()
                project.pluginManager.apply("java-library")

                val summary = SymbolExtractor.extractProjectSummary(project, project)
                ReportWriter.writeProjectReport(project, summary, Srcx.OUTPUT_DIR)

                then("report file is created") {
                    File(projectDir, ".srcx/root/context.md").shouldExist()
                }

                then("report contains project path") {
                    val content = File(projectDir, ".srcx/root/context.md").readText()
                    content shouldContain "# :"
                }
            }
        }

        given("writeGitignore") {

            `when`("writing gitignore") {
                val projectDir = tempDir()

                ReportWriter.writeGitignore(projectDir, Srcx.OUTPUT_DIR)

                then(".gitignore is created with wildcard") {
                    val gitignore = File(projectDir, ".srcx/.gitignore")
                    gitignore.shouldExist()
                    gitignore.readText() shouldBe "*\n"
                }
            }
        }

        given("runParallel") {

            `when`("running with empty project list") {
                then("it prints no projects message") {
                    ReportWriter.runParallel(emptyList()) { "OK" }
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
                    ReportWriter.runParallel(listOf(project)) { p -> "OK ${p.path}" }
                }
            }
        }

        given("SettingsExtension") {
            val objects =
                ProjectBuilder
                    .builder()
                    .build()
                    .objects

            fun newExtension(): Srcx.SettingsExtension =
                objects
                    .newInstance(Srcx.SettingsExtension::class.java)
                    .also {
                        it.outputDir.convention(Srcx.OUTPUT_DIR)
                        it.autoGenerate.convention(false)
                    }

            `when`("created with defaults") {
                val extension = newExtension()

                then("outputDir defaults to .srcx") {
                    extension.outputDir.get() shouldBe ".srcx"
                }
            }

            `when`("outputDir is changed") {
                val extension = newExtension()
                extension.outputDir.set(".custom-output")

                then("outputDir reflects the new value") {
                    extension.outputDir.get() shouldBe ".custom-output"
                }
            }

            `when`("autoGenerate defaults") {
                val extension = newExtension()

                then("autoGenerate defaults to false") {
                    extension.autoGenerate.get() shouldBe false
                }
            }

            `when`("autoGenerate is enabled") {
                val extension = newExtension()
                extension.autoGenerate.set(true)

                then("autoGenerate reflects the new value") {
                    extension.autoGenerate.get() shouldBe true
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

                then("TASK_CONTEXT is srcx-context") {
                    Srcx.TASK_CONTEXT shouldBe "srcx-context"
                }

                then("TASK_CONTEXT is srcx-context") {
                    Srcx.TASK_CONTEXT shouldBe "srcx-context"
                }
            }
        }

        given("registerTasks") {
            val plugin = Srcx.SettingsPlugin()
            val extension =
                ProjectBuilder
                    .builder()
                    .build()
                    .objects
                    .newInstance(Srcx.SettingsExtension::class.java)
            extension.outputDir.convention(Srcx.OUTPUT_DIR)
            extension.autoGenerate.convention(false)

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

                then("srcx-context task is registered") {
                    project.tasks.findByName(Srcx.TASK_CONTEXT) shouldBe
                        project.tasks.getByName(Srcx.TASK_CONTEXT)
                }

                then("executing srcx-context produces symbols and context") {
                    val task = project.tasks.getByName(Srcx.TASK_CONTEXT)
                    task.actions.forEach { it.execute(task) }
                    File(projectDir, ".srcx/root/context.md").shouldExist()
                    File(projectDir, ".srcx/.gitignore").shouldExist()
                    File(projectDir, ".srcx/context.md").shouldExist()
                }
            }
        }

        given("collectProjects") {

            `when`("called on a root project") {
                val projectDir = tempDir()
                val project =
                    ProjectBuilder
                        .builder()
                        .withProjectDir(projectDir)
                        .build()

                then("it returns the root project") {
                    val projects = ProjectScanner.collectProjects(project)
                    projects.size shouldBe 1
                    projects[0] shouldBe project
                }
            }
        }
    })
