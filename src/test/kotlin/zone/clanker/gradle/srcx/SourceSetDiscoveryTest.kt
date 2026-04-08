package zone.clanker.gradle.srcx

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.io.File

class SourceSetDiscoveryTest :
    BehaviorSpec({

        fun tempDir(): File =
            File.createTempFile("srcx-ss", "").apply {
                delete()
                mkdirs()
                deleteOnExit()
            }

        val plugin = Srcx.SettingsPlugin()

        given("discoverSourceSets") {

            `when`("project has main and test source sets") {
                val projectDir = tempDir()
                val mainDir = File(projectDir, "src/main/kotlin/com/example")
                mainDir.mkdirs()
                mainDir.resolve("App.kt").writeText("package com.example\nclass App")

                val testDir = File(projectDir, "src/test/kotlin/com/example")
                testDir.mkdirs()
                testDir.resolve("AppTest.kt").writeText("package com.example\nclass AppTest")

                val result = plugin.discoverSourceSets(projectDir)

                then("it discovers both source sets in order") {
                    result.map { it.value } shouldContainExactly listOf("main", "test")
                }
            }

            `when`("project has main, test, and androidTest") {
                val projectDir = tempDir()
                for (set in listOf("main", "test", "androidTest")) {
                    val dir = File(projectDir, "src/$set/kotlin/com/example")
                    dir.mkdirs()
                    dir.resolve("Src.kt").writeText("package com.example\nclass Src")
                }

                val result = plugin.discoverSourceSets(projectDir)

                then("it orders main, test, androidTest") {
                    result.map { it.value } shouldContainExactly listOf("main", "test", "androidTest")
                }
            }

            `when`("project has java source files") {
                val projectDir = tempDir()
                val javaDir = File(projectDir, "src/main/java/com/example")
                javaDir.mkdirs()
                javaDir.resolve("App.java").writeText("package com.example;\npublic class App {}")

                val result = plugin.discoverSourceSets(projectDir)

                then("it discovers the main source set") {
                    result.map { it.value } shouldContainExactly listOf("main")
                }
            }

            `when`("project has no src directory") {
                val projectDir = tempDir()

                val result = plugin.discoverSourceSets(projectDir)

                then("it returns empty") {
                    result.shouldBeEmpty()
                }
            }

            `when`("src is a file not a directory") {
                val projectDir = tempDir()
                File(projectDir, "src").writeText("not a directory")

                val result = plugin.discoverSourceSets(projectDir)

                then("it returns empty for null listFiles") {
                    result.shouldBeEmpty()
                }
            }

            `when`("project has src dir but no source files") {
                val projectDir = tempDir()
                File(projectDir, "src/main/kotlin").mkdirs()

                val result = plugin.discoverSourceSets(projectDir)

                then("it returns empty because no source files exist") {
                    result.shouldBeEmpty()
                }
            }

            `when`("project has custom source sets") {
                val projectDir = tempDir()
                for (set in listOf("main", "test", "integrationTest", "functionalTest")) {
                    val dir = File(projectDir, "src/$set/kotlin/com/example")
                    dir.mkdirs()
                    dir.resolve("Src.kt").writeText("package com.example\nclass Src")
                }

                val result = plugin.discoverSourceSets(projectDir)

                then("main and test come first, then custom sets alphabetically") {
                    result.map { it.value } shouldContainExactly
                        listOf("main", "test", "functionalTest", "integrationTest")
                }
            }
        }

        given("sourceSetDirs") {

            `when`("given a source set name") {
                val projectDir = tempDir()
                val dirs = plugin.sourceSetDirs(projectDir, "test")

                then("it returns kotlin and java dirs for that source set") {
                    dirs.size shouldBe 2
                    dirs[0].path shouldBe File(projectDir, "src/test/kotlin").path
                    dirs[1].path shouldBe File(projectDir, "src/test/java").path
                }
            }
        }

        given("extractSymbolsFromDirs") {

            `when`("scanning test sources") {
                val projectDir = tempDir()
                val testDir = File(projectDir, "src/test/kotlin/com/example")
                testDir.mkdirs()
                testDir.resolve("AppTest.kt").writeText(
                    """
                    package com.example

                    class AppTest {
                        fun testRun() {}
                        val fixture = "test"
                    }
                    """.trimIndent(),
                )

                val dirs = listOf(File(projectDir, "src/test/kotlin"))
                val symbols = plugin.extractSymbolsFromDirs(dirs)

                then("it extracts test class, function, and property") {
                    symbols.size shouldBe 3
                    symbols[0].name.value shouldBe "AppTest"
                    symbols[1].name.value shouldBe "testRun"
                    symbols[2].name.value shouldBe "fixture"
                }
            }

            `when`("scanning dirs that don't exist") {
                val dirs = listOf(File("/nonexistent/path"))
                val symbols = plugin.extractSymbolsFromDirs(dirs)

                then("it returns empty") {
                    symbols.shouldBeEmpty()
                }
            }
        }
    })
