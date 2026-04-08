package zone.clanker.gradle.srcx.parse

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.shouldBe
import org.gradle.testfixtures.ProjectBuilder
import java.io.File

class SourceScannerProjectTest :
    BehaviorSpec({

        fun tempDir(): File =
            File.createTempFile("scanner-project", "").apply {
                delete()
                mkdirs()
                deleteOnExit()
            }

        given("discoverSourceDirs with Project") {
            `when`("project has Java plugin and source files") {
                val dir = tempDir()
                val srcDir = dir.resolve("src/main/kotlin/com/example")
                srcDir.mkdirs()
                srcDir.resolve("App.kt").writeText("package com.example\nclass App")
                dir.resolve("build.gradle.kts").writeText("")

                val project =
                    ProjectBuilder
                        .builder()
                        .withProjectDir(dir)
                        .build()
                project.pluginManager.apply("java-library")

                val dirs = SourceScanner.discoverSourceDirs(project)

                then("it discovers source directories") {
                    dirs.isNotEmpty() shouldBe true
                }

                dir.deleteRecursively()
            }

            `when`("project has no plugins and no source dirs") {
                val dir = tempDir()
                dir.resolve("build.gradle.kts").writeText("")

                val project =
                    ProjectBuilder
                        .builder()
                        .withProjectDir(dir)
                        .build()

                val dirs = SourceScanner.discoverSourceDirs(project)

                then("it returns empty list") {
                    dirs.shouldBeEmpty()
                }

                dir.deleteRecursively()
            }

            `when`("project has conventional source layout without plugins") {
                val dir = tempDir()
                val srcDir = dir.resolve("src/main/kotlin")
                srcDir.mkdirs()
                srcDir.resolve("App.kt").writeText("class App")
                dir.resolve("build.gradle.kts").writeText("")

                val project =
                    ProjectBuilder
                        .builder()
                        .withProjectDir(dir)
                        .build()

                val dirs = SourceScanner.discoverSourceDirs(project)

                then("it discovers conventional dirs") {
                    dirs.isNotEmpty() shouldBe true
                }

                dir.deleteRecursively()
            }

            `when`("project has java source dir") {
                val dir = tempDir()
                val srcDir = dir.resolve("src/main/java")
                srcDir.mkdirs()
                srcDir.resolve("App.java").writeText("class App {}")
                dir.resolve("build.gradle.kts").writeText("")

                val project =
                    ProjectBuilder
                        .builder()
                        .withProjectDir(dir)
                        .build()

                val dirs = SourceScanner.discoverSourceDirs(project)

                then("it finds the java dir") {
                    dirs.any { it.path.endsWith("java") } shouldBe true
                }

                dir.deleteRecursively()
            }
        }

        given("discoverSourceDirs with multiple projects") {
            `when`("passing a list of projects") {
                val dir = tempDir()
                val srcDir = dir.resolve("src/main/kotlin")
                srcDir.mkdirs()
                srcDir.resolve("App.kt").writeText("class App")
                dir.resolve("build.gradle.kts").writeText("")

                val project =
                    ProjectBuilder
                        .builder()
                        .withProjectDir(dir)
                        .build()
                project.pluginManager.apply("java-library")

                val dirs = SourceScanner.discoverSourceDirs(listOf(project))

                then("it discovers source dirs across all projects") {
                    dirs.isNotEmpty() shouldBe true
                }

                dir.deleteRecursively()
            }
        }

        given("resolveProjects") {
            `when`("module is null") {
                val dir = tempDir()
                dir.resolve("build.gradle.kts").writeText("")
                val project =
                    ProjectBuilder
                        .builder()
                        .withProjectDir(dir)
                        .build()

                val resolved = SourceScanner.resolveProjects(project, null)

                then("it returns the root project") {
                    resolved shouldHaveAtLeastSize 1
                    resolved.first() shouldBe project
                }

                dir.deleteRecursively()
            }

            `when`("module is specified") {
                val dir = tempDir()
                dir.resolve("build.gradle.kts").writeText("")
                val project =
                    ProjectBuilder
                        .builder()
                        .withProjectDir(dir)
                        .withName("myproject")
                        .build()

                val resolved = SourceScanner.resolveProjects(project, "myproject")

                then("it filters to matching project") {
                    resolved.isNotEmpty() shouldBe true
                }

                dir.deleteRecursively()
            }

            `when`("module doesn't match any project") {
                val dir = tempDir()
                dir.resolve("build.gradle.kts").writeText("")
                val project =
                    ProjectBuilder
                        .builder()
                        .withProjectDir(dir)
                        .build()

                val resolved = SourceScanner.resolveProjects(project, "nonexistent")

                then("it returns empty list") {
                    resolved.shouldBeEmpty()
                }

                dir.deleteRecursively()
            }
        }
    })
