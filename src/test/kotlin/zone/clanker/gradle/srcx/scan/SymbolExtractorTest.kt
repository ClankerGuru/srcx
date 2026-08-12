package zone.clanker.gradle.srcx.scan

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
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
                main.resolve("Alpha.kt").writeText(
                    """
                    package sample
                    class Alpha {
                        fun use(): Zulu = Zulu()
                    }
                    """.trimIndent(),
                )
                val test = projectDir.resolve("src/test/java/sample").apply { mkdirs() }
                test.resolve("AlphaTest.java").writeText("package sample; class AlphaTest {}")

                val scan =
                    SymbolExtractor.extractProjectScanFromData(
                        projectDir = projectDir,
                        projectPath = ":app",
                        subprojectPaths = listOf(":app:child"),
                        dependencies = emptyList(),
                        build = "workspace",
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

                then("repeated scans retain deterministic file and fact ordering") {
                    val repeated =
                        SymbolExtractor.extractProjectScanFromData(
                            projectDir = projectDir,
                            projectPath = ":app",
                            subprojectPaths = listOf(":app:child"),
                            dependencies = emptyList(),
                            build = "workspace",
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
    })
