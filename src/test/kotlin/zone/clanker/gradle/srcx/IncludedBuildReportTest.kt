package zone.clanker.gradle.srcx

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.file.shouldExist
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import zone.clanker.gradle.srcx.scan.SymbolExtractor
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
