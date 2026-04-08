package zone.clanker.gradle.srcx

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.file.shouldExist
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File

class IncludedBuildReportTest :
    BehaviorSpec({

        fun tempDir(): File =
            File.createTempFile("srcx-ibr", "").apply {
                delete()
                mkdirs()
                deleteOnExit()
            }

        val plugin = Srcx.SettingsPlugin()

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

                val rootSummary = plugin.extractStandaloneProjectSummary(buildDir, ":", buildDir)
                val coreSummary = plugin.extractStandaloneProjectSummary(coreDir, ":core", buildDir)

                then("root project discovers subprojects") {
                    rootSummary.subprojects shouldBe listOf(":core")
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

                val summary = plugin.extractStandaloneProjectSummary(buildDir, ":", buildDir)

                val renderer =
                    zone.clanker.gradle.srcx.report
                        .IncludedBuildRenderer("codec", listOf(summary))
                File(outputDir, "context.md").writeText(renderer.render())

                val reportRenderer =
                    zone.clanker.gradle.srcx.report
                        .ProjectReportRenderer(summary)
                val reportDir = File(outputDir, "root")
                reportDir.mkdirs()
                File(reportDir, "symbols.md").writeText(reportRenderer.render())

                then("context.md is created") {
                    File(outputDir, "context.md").shouldExist()
                    File(outputDir, "context.md").readText() shouldContain "# codec"
                }

                then("root/symbols.md is created") {
                    File(reportDir, "symbols.md").shouldExist()
                    File(reportDir, "symbols.md").readText() shouldContain "Codec"
                }
            }
        }

        given("collectIncludedBuildSummaries") {

            `when`("collecting from multiple builds") {
                val buildA = tempDir()
                buildA.resolve("settings.gradle.kts").writeText("rootProject.name = \"a\"")
                buildA.resolve("build.gradle.kts").writeText("")
                val aSrc = File(buildA, "src/main/kotlin/com/a")
                aSrc.mkdirs()
                aSrc.resolve("A.kt").writeText("package com.a\nclass A")

                val buildB = tempDir()
                buildB.resolve("settings.gradle.kts").writeText("rootProject.name = \"b\"")
                buildB.resolve("build.gradle.kts").writeText("")
                val bSrc = File(buildB, "src/main/kotlin/com/b")
                bSrc.mkdirs()
                bSrc.resolve("B.kt").writeText("package com.b\nclass B\nclass B2")

                val builds = listOf("a" to buildA, "b" to buildB)
                val result = plugin.collectIncludedBuildSummaries(builds)

                then("it returns summaries for both builds") {
                    result.size shouldBe 2
                    result["a"]!!.size shouldBe 1
                    result["b"]!!.size shouldBe 1
                }

                then("each build has correct symbol counts") {
                    result["a"]!![0].symbols.size shouldBe 1
                    result["b"]!![0].symbols.size shouldBe 2
                }
            }
        }

        given("generateIncludedBuildReports") {

            `when`("generating reports for builds") {
                val rootDir = tempDir()
                val buildDir = tempDir()
                buildDir.resolve("settings.gradle.kts").writeText(
                    """
                    rootProject.name = "mylib"
                    include(":sub")
                    """.trimIndent(),
                )
                buildDir.resolve("build.gradle.kts").writeText("")
                val rootSrc = File(buildDir, "src/main/kotlin/com/lib")
                rootSrc.mkdirs()
                rootSrc.resolve("Lib.kt").writeText("package com.lib\nclass Lib")

                val subDir = File(buildDir, "sub")
                subDir.mkdirs()
                subDir.resolve("build.gradle.kts").writeText("")
                val subSrc = File(subDir, "src/main/kotlin/com/lib/sub")
                subSrc.mkdirs()
                subSrc.resolve("Sub.kt").writeText("package com.lib.sub\nclass Sub\nfun help() = 1")

                val extension = Srcx.SettingsExtension()
                val builds = listOf("mylib" to buildDir)
                plugin.generateIncludedBuildReports(builds, extension)

                then("build dashboard is created in the build's own directory") {
                    File(buildDir, ".srcx/context.md").shouldExist()
                    File(buildDir, ".srcx/context.md").readText() shouldContain "# mylib"
                }

                then("root project report is created in the build's own directory") {
                    File(buildDir, ".srcx/root/symbols.md").shouldExist()
                    File(buildDir, ".srcx/root/symbols.md").readText() shouldContain "Lib"
                }

                then("subproject report is created in the build's own directory") {
                    File(buildDir, ".srcx/sub/symbols.md").shouldExist()
                    val content = File(buildDir, ".srcx/sub/symbols.md").readText()
                    content shouldContain "Sub"
                    content shouldContain "help"
                }
            }

            `when`("generating with no builds") {
                val rootDir = tempDir()
                val extension = Srcx.SettingsExtension()
                plugin.generateIncludedBuildReports(emptyList(), extension)

                then("no output is created") {
                    File(rootDir, ".srcx").exists() shouldBe false
                }
            }
        }
    })
