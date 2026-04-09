package zone.clanker.gradle.srcx

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.file.shouldExist
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import zone.clanker.gradle.srcx.model.ArtifactGroup
import zone.clanker.gradle.srcx.model.ArtifactName
import zone.clanker.gradle.srcx.model.ArtifactVersion
import zone.clanker.gradle.srcx.model.DependencyEntry
import zone.clanker.gradle.srcx.report.ReportWriter
import zone.clanker.gradle.srcx.scan.SymbolExtractor
import zone.clanker.gradle.srcx.task.IncludedBuildInfo
import java.io.File

class IncludedBuildReportTest :
    BehaviorSpec({

        fun tempDir(): File =
            File.createTempFile("srcx-ibr", "").apply {
                delete()
                mkdirs()
                deleteOnExit()
            }

        given("generateIncludedBuildReports via extractStandaloneProjectSummary") {

            `when`("processing an included build with subprojects") {
                val buildDir = tempDir()
                buildDir.resolve("settings.gradle.kts").writeText(
                    """
                    rootProject.name = "codec"
                    include(":core")
                    """.trimIndent(),
                )
                buildDir.resolve("build.gradle.kts").writeText(
                    """
                    plugins { kotlin("jvm") version "2.1.20" }
                    group = "com.example"
                    """.trimIndent(),
                )

                val rootSrcDir = File(buildDir, "src/main/kotlin/com/example")
                rootSrcDir.mkdirs()
                rootSrcDir.resolve("Codec.kt").writeText(
                    """
                    package com.example

                    interface Codec<T> {
                        fun encode(value: T): ByteArray
                        fun decode(bytes: ByteArray): T
                    }
                    """.trimIndent(),
                )

                val coreDir = File(buildDir, "core")
                coreDir.mkdirs()
                coreDir.resolve("build.gradle.kts").writeText(
                    """
                    plugins { kotlin("jvm") version "2.1.20" }
                    dependencies {
                        implementation("com.example:codec:1.0.0")
                    }
                    """.trimIndent(),
                )
                val coreSrcDir = File(coreDir, "src/main/kotlin/com/example/core")
                coreSrcDir.mkdirs()
                coreSrcDir.resolve("StringCodec.kt").writeText(
                    """
                    package com.example.core

                    class StringCodec {
                        fun encode(value: String): ByteArray = value.encodeToByteArray()
                    }
                    """.trimIndent(),
                )

                val rootSummary = SymbolExtractor.extractStandaloneProjectSummary(buildDir, ":")
                val coreSummary = SymbolExtractor.extractStandaloneProjectSummary(coreDir, ":core")

                then("root project subprojects are empty (discovered by Gradle API)") {
                    rootSummary.subprojects shouldBe emptyList()
                }

                then("root project extracts symbols") {
                    rootSummary.symbols.size shouldBe 3
                    rootSummary.symbols.any { it.name.value == "Codec" } shouldBe true
                }

                then("root project has main source set") {
                    rootSummary.sourceSets.size shouldBe 1
                    rootSummary.sourceSets[0].name.value shouldBe "main"
                }

                then("core subproject has its own symbols") {
                    coreSummary.symbols.size shouldBe 2
                    coreSummary.symbols.any { it.name.value == "StringCodec" } shouldBe true
                }

                then("core subproject has no subprojects") {
                    coreSummary.subprojects.shouldBe(emptyList())
                }

                then("core subproject dependencies extracted from build file") {
                    coreSummary.dependencies.size shouldBe 1
                    coreSummary.dependencies[0].artifact.value shouldBe "codec"
                }
            }
        }

        given("extractProjectSummaryFromData") {

            `when`("extracting with pre-computed deps") {
                val projectDir = tempDir()
                val srcDir = File(projectDir, "src/main/kotlin/com/example")
                srcDir.mkdirs()
                srcDir.resolve("Service.kt").writeText(
                    """
                    package com.example
                    class Service
                    """.trimIndent(),
                )
                projectDir.resolve("build.gradle.kts").writeText("")

                val deps =
                    listOf(
                        DependencyEntry(
                            ArtifactGroup("com.example"),
                            ArtifactName("lib"),
                            ArtifactVersion("1.0"),
                            "implementation",
                        ),
                    )

                val summary =
                    SymbolExtractor.extractProjectSummaryFromData(
                        projectDir, ":app", listOf(":lib"), deps,
                    )

                then("it includes pre-computed dependencies") {
                    summary.dependencies.size shouldBe 1
                    summary.dependencies[0].artifact.value shouldBe "lib"
                }

                then("it includes subproject paths") {
                    summary.subprojects shouldBe listOf(":lib")
                }

                then("it extracts symbols") {
                    summary.symbols.any { it.name.value == "Service" } shouldBe true
                }
            }
        }

        given("writeProjectReportToDir") {

            `when`("writing report with root dir") {
                val rootDir = tempDir()
                val srcDir = File(rootDir, "src/main/kotlin/com/example")
                srcDir.mkdirs()
                srcDir.resolve("App.kt").writeText("package com.example\nclass App")
                rootDir.resolve("build.gradle.kts").writeText("")

                val summary = SymbolExtractor.extractStandaloneProjectSummary(rootDir, ":")

                ReportWriter
                    .writeProjectReportToDir(rootDir, summary, ".srcx")

                then("it creates the report file") {
                    File(rootDir, ".srcx/root/context.md").shouldExist()
                    File(rootDir, ".srcx/root/context.md").readText() shouldContain "App"
                }
            }
        }

        given("runParallelMapped") {

            `when`("mapping items in parallel") {
                val items = listOf(1, 2, 3, 4)
                val results =
                    ReportWriter
                        .runParallelMapped(items) { it * 2 }

                then("it returns mapped results") {
                    results shouldBe listOf(2, 4, 6, 8)
                }
            }

            `when`("mapping empty list") {
                val results =
                    ReportWriter
                        .runParallelMapped(emptyList<Int>()) { it * 2 }

                then("it returns empty list") {
                    results.shouldBeEmpty()
                }
            }
        }

        given("generateIncludedBuildReportsFromData") {

            `when`("generating from pre-computed data") {
                val buildDir = tempDir()
                val srcDir = File(buildDir, "src/main/kotlin/com/example")
                srcDir.mkdirs()
                srcDir.resolve("Lib.kt").writeText("package com.example\nclass Lib")
                buildDir.resolve("build.gradle.kts").writeText("")

                val info =
                    IncludedBuildInfo(
                        name = "mylib",
                        dir = buildDir,
                        relPath = "../mylib",
                        projects = listOf(":" to buildDir),
                    )

                ReportWriter
                    .generateIncludedBuildReportsFromData(listOf(info), ".srcx")

                then("it creates context.md for the included build") {
                    File(buildDir, ".srcx/context.md").shouldExist()
                    File(buildDir, ".srcx/context.md").readText() shouldContain "# mylib"
                }

                then("it creates per-project reports") {
                    File(buildDir, ".srcx/root/context.md").shouldExist()
                    File(buildDir, ".srcx/root/context.md").readText() shouldContain "Lib"
                }
            }
        }

        given("IncludedBuildInfo serializable") {

            `when`("created with data") {
                val info =
                    IncludedBuildInfo(
                        name = "test",
                        dir = File("/tmp"),
                        relPath = "../test",
                        projects = listOf(":" to File("/tmp")),
                    )

                then("it holds the data") {
                    info.name shouldBe "test"
                    info.relPath shouldBe "../test"
                    info.projects.size shouldBe 1
                }
            }
        }

        given("IncludedBuildRenderer output") {

            `when`("writing reports to output directory") {
                val buildDir = tempDir()
                val outputDir = File(buildDir, ".srcx/codec")
                outputDir.mkdirs()

                val rootSrcDir = File(buildDir, "src/main/kotlin/com/example")
                rootSrcDir.mkdirs()
                rootSrcDir.resolve("Codec.kt").writeText(
                    """
                    package com.example
                    class Codec
                    """.trimIndent(),
                )
                buildDir.resolve("build.gradle.kts").writeText("")
                buildDir.resolve("settings.gradle.kts").writeText("rootProject.name = \"codec\"")

                val summary = SymbolExtractor.extractStandaloneProjectSummary(buildDir, ":")

                val renderer =
                    zone.clanker.gradle.srcx.report
                        .IncludedBuildRenderer("codec", listOf(summary))
                File(outputDir, "context.md").writeText(renderer.render())

                val reportRenderer =
                    zone.clanker.gradle.srcx.report
                        .ProjectReportRenderer(summary)
                val reportDir = File(outputDir, "root")
                reportDir.mkdirs()
                File(reportDir, "context.md").writeText(reportRenderer.render())

                then("context.md is created") {
                    File(outputDir, "context.md").shouldExist()
                    File(outputDir, "context.md").readText() shouldContain "# codec"
                }

                then("root/context.md is created") {
                    File(reportDir, "context.md").shouldExist()
                    File(reportDir, "context.md").readText() shouldContain "Codec"
                }
            }
        }
    })
