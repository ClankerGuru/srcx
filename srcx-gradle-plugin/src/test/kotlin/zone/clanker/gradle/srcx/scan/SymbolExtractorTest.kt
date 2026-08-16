package zone.clanker.gradle.srcx.scan

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import zone.clanker.gradle.srcx.model.SourceSetName
import java.io.File

class SymbolExtractorTest :
    BehaviorSpec({
        fun tempProject(): File =
            File.createTempFile("project-scan", "").apply {
                delete()
                mkdirs()
                deleteOnExit()
                resolve("build.gradle.kts").writeText("")
            }

        given("raw project extraction") {
            `when`("source files are created in a non-sorted order") {
                val projectDir = tempProject()
                val main = projectDir.resolve("src/main/kotlin/sample").apply { mkdirs() }
                main.resolve("Zulu.kt").writeText("package sample\nclass Zulu")
                val alphaSource =
                    """
                    package sample
                    // Exact UTF-8 source evidence: café, 雪, 🚀, and </script>.
                    class Alpha {
                        fun use(): Zulu = Zulu()
                    }
                    """.trimIndent() + "\n"
                main.resolve("Alpha.kt").writeText(
                    text = alphaSource,
                    charset = Charsets.UTF_8,
                )
                val test = projectDir.resolve("src/test/java/sample").apply { mkdirs() }
                test.resolve("AlphaTest.java").writeText("package sample; class AlphaTest {}")

                val scan =
                    SymbolExtractor.extractProjectScanFromData(
                        ProjectExtractionRequest(
                            projectDir = projectDir,
                            projectPath = ":app",
                            subprojectPaths = listOf(":app:child"),
                            dependencies = emptyList(),
                            build = "workspace",
                        ),
                    )

                then("the scan retains stable project ownership and source provenance") {
                    scan.build shouldBe "workspace"
                    scan.projectPath.value shouldBe ":app"
                    scan.files.map { it.sourceSet.value to it.projectRelativeFile } shouldContainExactly
                        listOf(
                            "main" to "src/main/kotlin/sample/Alpha.kt",
                            "main" to "src/main/kotlin/sample/Zulu.kt",
                            "test" to "src/test/java/sample/AlphaTest.java",
                        )
                }

                then("each file retains declarations and references from one fact extraction") {
                    val alpha = scan.files.first()
                    alpha.declarations.map { it.qualifiedName } shouldContainExactly
                        listOf("sample.Alpha", "sample.Alpha.use")
                    alpha.references.any { it.sourceQualifiedName == "sample.Alpha.use" } shouldBe true
                }

                then("each file retains its complete exact Unicode source text") {
                    scan.files.first().sourceText shouldBe alphaSource
                }

                then("repeated scans retain deterministic file and fact ordering") {
                    val repeated =
                        SymbolExtractor.extractProjectScanFromData(
                            ProjectExtractionRequest(
                                projectDir = projectDir,
                                projectPath = ":app",
                                subprojectPaths = listOf(":app:child"),
                                dependencies = emptyList(),
                                build = "workspace",
                            ),
                        )
                    repeated.files shouldBe scan.files
                }

                then("the legacy summary is projected from raw declarations") {
                    scan.summary.symbols.map { it.name.value } shouldContainExactly
                        listOf("Alpha", "use", "Zulu", "AlphaTest")
                    scan.summary.symbols.map { it.filePath.value } shouldContainExactly
                        listOf("sample/Alpha.kt", "sample/Alpha.kt", "sample/Zulu.kt", "sample/AlphaTest.java")
                    scan.summary.sourceSets.map { it.name.value } shouldContainExactly listOf("main", "test")
                    scan.summary.subprojects shouldContainExactly listOf(":app:child")
                }

                then("the existing summary API delegates compatibly") {
                    val summary =
                        SymbolExtractor.extractProjectSummaryFromData(
                            projectDir,
                            ":app",
                            listOf(":app:child"),
                            emptyList(),
                        )
                    summary shouldBe scan.summary
                }

                then("the existing standalone summary API delegates compatibly") {
                    val standaloneScan = SymbolExtractor.extractStandaloneProjectScan(projectDir, ":app")
                    SymbolExtractor.extractStandaloneProjectSummary(projectDir, ":app") shouldBe standaloneScan.summary
                }
            }
        }

        given("a precomputed custom source layout") {
            val projectDir = tempProject()
            val production = projectDir.resolve("production").apply { mkdirs() }
            production.resolve("CustomApi.kt").writeText("package sample\nclass CustomApi")
            val generated = projectDir.resolve("build/generated").apply { mkdirs() }
            generated.resolve("GeneratedApi.kt").writeText("package sample\nclass GeneratedApi")
            val layout =
                ProjectSourceLayout(
                    sourceSets =
                        listOf(
                            ConfiguredSourceSet(
                                name = SourceSetName("commonMain"),
                                directories = listOf(production, generated),
                            ),
                        ),
                    excludedDirectories = listOf(projectDir.resolve("build")),
                )

            `when`("execution uses the captured layout") {
                val scan =
                    SymbolExtractor.extractProjectScanFromData(
                        ProjectExtractionRequest(
                            projectDir = projectDir,
                            projectPath = ":custom",
                            subprojectPaths = emptyList(),
                            dependencies = emptyList(),
                            sourceLayout = layout,
                        ),
                    )

                then("custom files retain exact project and source-set paths") {
                    scan.files.map { file -> file.sourceSet.value to file.projectRelativeFile } shouldContainExactly
                        listOf("commonMain" to "production/CustomApi.kt")
                    scan.summary.sourceDirs shouldContainExactly listOf("production")
                    scan.summary.symbols.map { symbol -> symbol.name.value } shouldContainExactly listOf("CustomApi")
                }
            }
        }
    })
