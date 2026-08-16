package zone.clanker.gradle.srcx

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import zone.clanker.gradle.srcx.scan.ProjectScanner
import zone.clanker.gradle.srcx.scan.SymbolExtractor
import java.io.File

class IncludedBuildTraversalTest :
    BehaviorSpec({

        fun tempDir(): File =
            File.createTempFile("srcx-ib", "").apply {
                delete()
                mkdirs()
                deleteOnExit()
            }

        given("extractStandaloneProjectSummary") {

            `when`("extracting from a project with main and test sources") {
                val projectDir = tempDir()
                projectDir.resolve("build.gradle.kts").writeText(
                    """
                    plugins { kotlin("jvm") version "2.1.20" }
                    group = "com.example"
                    dependencies {
                        implementation("com.example:shared-models:1.0.0")
                        testImplementation("io.kotest:kotest-runner-junit5:5.9.1")
                    }
                    """.trimIndent(),
                )

                val mainDir = File(projectDir, "src/main/kotlin/com/example")
                mainDir.mkdirs()
                mainDir.resolve("Service.kt").writeText(
                    """
                    package com.example

                    class Service {
                        fun process(): String = "done"
                    }
                    """.trimIndent(),
                )

                val testDir = File(projectDir, "src/test/kotlin/com/example")
                testDir.mkdirs()
                testDir.resolve("ServiceTest.kt").writeText(
                    """
                    package com.example

                    class ServiceTest {
                        fun testProcess() {}
                    }
                    """.trimIndent(),
                )

                val summary =
                    SymbolExtractor.extractStandaloneProjectSummary(
                        projectDir,
                        ":",
                    )

                then("it discovers both source sets") {
                    summary.sourceSets.size shouldBe 2
                    summary.sourceSets[0].name.value shouldBe "main"
                    summary.sourceSets[1].name.value shouldBe "test"
                }

                then("main has 2 symbols") {
                    summary.sourceSets[0].symbols.size shouldBe 2
                }

                then("test has 2 symbols") {
                    summary.sourceSets[1].symbols.size shouldBe 2
                }

                then("total symbols includes all source sets") {
                    summary.symbols.size shouldBe 4
                }

                then("dependencies are extracted from build file") {
                    summary.dependencies.size shouldBe 2
                    summary.dependencies[0].artifact.value shouldBe "shared-models"
                    summary.dependencies[1].artifact.value shouldBe "kotest-runner-junit5"
                }

                then("build file is detected") {
                    summary.buildFile shouldBe "build.gradle.kts"
                }
            }

            `when`("extracting from a project with no sources") {
                val projectDir = tempDir()
                projectDir.resolve("build.gradle.kts").writeText("")

                val summary =
                    SymbolExtractor.extractStandaloneProjectSummary(
                        projectDir,
                        ":",
                    )

                then("source sets are empty") {
                    summary.sourceSets.shouldBeEmpty()
                }

                then("symbols are empty") {
                    summary.symbols.shouldBeEmpty()
                }
            }
        }

        given("extractStandaloneProjectSummary edge cases") {

            `when`("project has build.gradle (groovy)") {
                val projectDir = tempDir()
                projectDir.resolve("build.gradle").writeText(
                    """
                    apply plugin: 'java'
                    """.trimIndent(),
                )
                val srcDir = File(projectDir, "src/main/java/com/example")
                srcDir.mkdirs()
                srcDir.resolve("App.java").writeText(
                    """
                    package com.example;
                    public class App {}
                    """.trimIndent(),
                )

                val summary =
                    SymbolExtractor.extractStandaloneProjectSummary(
                        projectDir,
                        ":",
                    )

                then("it detects build.gradle") {
                    summary.buildFile shouldBe "build.gradle"
                }
            }

            `when`("project has no build file") {
                val projectDir = tempDir()
                val srcDir = File(projectDir, "src/main/kotlin/com/example")
                srcDir.mkdirs()
                srcDir.resolve("App.kt").writeText("package com.example\nclass App")

                val summary =
                    SymbolExtractor.extractStandaloneProjectSummary(
                        projectDir,
                        ":",
                    )

                then("it returns none for build file") {
                    summary.buildFile shouldBe "none"
                }
            }

            `when`("project has only groovy source files") {
                val projectDir = tempDir()
                projectDir.resolve("build.gradle.kts").writeText("")
                val groovyDir = File(projectDir, "src/main/groovy/com/example")
                groovyDir.mkdirs()
                groovyDir.resolve("App.groovy").writeText("package com.example\nclass App {}")

                val sourceSets = ProjectScanner.discoverSourceSets(projectDir)

                then("it does not discover groovy-only source sets (PSI does not support groovy)") {
                    sourceSets.isEmpty() shouldBe true
                }
            }
        }

        given("extractDependenciesFromBuildFile") {

            `when`("build file has dependencies") {
                val projectDir = tempDir()
                projectDir.resolve("build.gradle.kts").writeText(
                    """
                    plugins { kotlin("jvm") version "2.1.20" }
                    dependencies {
                        api("com.example:codec:1.0.0")
                        implementation("com.example:serialization:2.0.0")
                        testImplementation("io.kotest:kotest-runner-junit5:5.9.1")
                    }
                    """.trimIndent(),
                )

                val deps = SymbolExtractor.extractDependenciesFromBuildFile(projectDir)

                then("it extracts all three dependencies") {
                    deps.size shouldBe 3
                }

                then("first dep is api scope") {
                    deps[0].scope shouldBe "api"
                    deps[0].group.value shouldBe "com.example"
                    deps[0].artifact.value shouldBe "codec"
                    deps[0].version.value shouldBe "1.0.0"
                }

                then("second dep is implementation scope") {
                    deps[1].scope shouldBe "implementation"
                }

                then("third dep is testImplementation scope") {
                    deps[2].scope shouldBe "testImplementation"
                }
            }

            `when`("no build file exists") {
                val projectDir = tempDir()

                val deps = SymbolExtractor.extractDependenciesFromBuildFile(projectDir)

                then("it returns empty") {
                    deps.shouldBeEmpty()
                }
            }
        }
    })
