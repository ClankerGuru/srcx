package zone.clanker.gradle.srcx.model

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

/**
 * Tests for [ProjectSummary] data class behavior.
 */
class ProjectSummaryTest :
    BehaviorSpec({

        given("a ProjectSummary") {

            `when`("created with symbols and dependencies") {
                val symbols =
                    listOf(
                        SymbolEntry("App", SymbolKind.CLASS, "com.example", "App.kt", 1),
                        SymbolEntry("run", SymbolKind.FUNCTION, "com.example", "App.kt", 5),
                    )
                val deps =
                    listOf(
                        DependencyEntry("org.jetbrains.kotlin", "kotlin-stdlib", "2.1.20", "implementation"),
                    )
                val summary =
                    ProjectSummary(
                        projectPath = ":app",
                        symbols = symbols,
                        dependencies = deps,
                        buildFile = "build.gradle.kts",
                        sourceDirs = listOf("src/main/kotlin"),
                        subprojects = listOf(":app:sub"),
                    )

                then("all fields are accessible") {
                    summary.projectPath shouldBe ":app"
                    summary.symbols.size shouldBe 2
                    summary.dependencies.size shouldBe 1
                    summary.buildFile shouldBe "build.gradle.kts"
                    summary.sourceDirs shouldBe listOf("src/main/kotlin")
                    summary.subprojects shouldBe listOf(":app:sub")
                }
            }

            `when`("created with empty lists") {
                val summary =
                    ProjectSummary(
                        projectPath = ":",
                        symbols = emptyList(),
                        dependencies = emptyList(),
                        buildFile = "none",
                        sourceDirs = emptyList(),
                        subprojects = emptyList(),
                    )

                then("lists are empty") {
                    summary.symbols.shouldBeEmpty()
                    summary.dependencies.shouldBeEmpty()
                    summary.sourceDirs.shouldBeEmpty()
                    summary.subprojects.shouldBeEmpty()
                }
            }

            `when`("two summaries have the same values") {
                val summary1 =
                    ProjectSummary(":lib", emptyList(), emptyList(), "build.gradle.kts", emptyList(), emptyList())
                val summary2 =
                    ProjectSummary(":lib", emptyList(), emptyList(), "build.gradle.kts", emptyList(), emptyList())

                then("they are equal") {
                    summary1 shouldBe summary2
                }
            }
        }
    })
