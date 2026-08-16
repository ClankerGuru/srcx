package zone.clanker.gradle.srcx.parse

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.io.File

class SourceScannerTest :
    BehaviorSpec({

        fun tempDir(): File =
            File.createTempFile("scanner", "").apply {
                delete()
                mkdirs()
                deleteOnExit()
            }

        given("collectSourceFiles") {
            `when`("directory contains kotlin and java files") {
                val dir = tempDir()
                dir.resolve("Example.kt").writeText("class Example")
                dir.resolve("Helper.java").writeText("class Helper {}")
                dir.resolve("readme.md").writeText("# Readme")

                val files = SourceScanner.collectSourceFiles(listOf(dir))

                then("it collects only source files") {
                    files shouldHaveSize 2
                    files.map { it.name }.toSet() shouldBe setOf("Example.kt", "Helper.java")
                }

                dir.deleteRecursively()
            }

            `when`("directory is empty") {
                val dir = tempDir()

                val files = SourceScanner.collectSourceFiles(listOf(dir))

                then("it returns empty list") {
                    files.shouldBeEmpty()
                }

                dir.deleteRecursively()
            }

            `when`("directory does not exist") {
                val files = SourceScanner.collectSourceFiles(listOf(File("/nonexistent/dir")))

                then("it returns empty list") {
                    files.shouldBeEmpty()
                }
            }

            `when`("multiple directories have duplicate files") {
                val dir1 = tempDir()
                val dir2 = tempDir()
                val file = dir1.resolve("Shared.kt")
                file.writeText("class Shared")

                val files = SourceScanner.collectSourceFiles(listOf(dir1, dir1))

                then("it deduplicates by path") {
                    files shouldHaveSize 1
                }

                dir1.deleteRecursively()
                dir2.deleteRecursively()
            }
        }

        given("collectAllFiles") {
            `when`("project has source and build files") {
                val srcDir = tempDir()
                srcDir.resolve("App.kt").writeText("class App")

                val projectDir = tempDir()
                projectDir.resolve("build.gradle.kts").writeText("plugins {}")
                projectDir.resolve("gradle.properties").writeText("key=value")

                val files = SourceScanner.collectAllFiles(listOf(srcDir), listOf(projectDir))

                then("it collects both source and build files") {
                    files.any { it.name == "App.kt" } shouldBe true
                    files.any { it.name == "build.gradle.kts" } shouldBe true
                    files.any { it.name == "gradle.properties" } shouldBe true
                }

                srcDir.deleteRecursively()
                projectDir.deleteRecursively()
            }

            `when`("project directory contains build output") {
                val projectDir = tempDir()
                val buildDir = projectDir.resolve("build/generated")
                buildDir.mkdirs()
                buildDir.resolve("settings.gradle.kts").writeText("// generated")
                projectDir.resolve("build.gradle.kts").writeText("plugins {}")

                val files = SourceScanner.collectAllFiles(emptyList(), listOf(projectDir))

                then("it excludes files in build directories") {
                    files.none { it.path.contains("${File.separator}build${File.separator}") } shouldBe true
                    files.any { it.name == "build.gradle.kts" } shouldBe true
                }

                projectDir.deleteRecursively()
            }
        }
    })
