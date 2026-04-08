package zone.clanker.gradle.srcx.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Tests for [ProjectSummary] data class and [ProjectPath] value class.
 */
class ProjectSummaryTest :
    BehaviorSpec({

        given("a ProjectSummary") {

            `when`("created with symbols and dependencies") {
                val symbols =
                    listOf(
                        SymbolEntry(
                            SymbolName("App"),
                            SymbolKind.CLASS,
                            PackageName("com.example"),
                            FilePath("App.kt"),
                            1,
                        ),
                        SymbolEntry(
                            SymbolName("run"),
                            SymbolKind.FUNCTION,
                            PackageName("com.example"),
                            FilePath("App.kt"),
                            5,
                        ),
                    )
                val deps =
                    listOf(
                        DependencyEntry(
                            ArtifactGroup("org.jetbrains.kotlin"),
                            ArtifactName("kotlin-stdlib"),
                            ArtifactVersion("2.1.20"),
                            "implementation",
                        ),
                    )
                val summary =
                    ProjectSummary(
                        projectPath = ProjectPath(":app"),
                        symbols = symbols,
                        dependencies = deps,
                        buildFile = "build.gradle.kts",
                        sourceDirs = listOf("src/main/kotlin"),
                        subprojects = listOf(":app:sub"),
                    )

                then("all fields are accessible") {
                    summary.projectPath.value shouldBe ":app"
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
                        projectPath = ProjectPath(":"),
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
                    ProjectSummary(
                        ProjectPath(":lib"),
                        emptyList(),
                        emptyList(),
                        "build.gradle.kts",
                        emptyList(),
                        emptyList(),
                    )
                val summary2 =
                    ProjectSummary(
                        ProjectPath(":lib"),
                        emptyList(),
                        emptyList(),
                        "build.gradle.kts",
                        emptyList(),
                        emptyList(),
                    )

                then("they are equal") {
                    summary1 shouldBe summary2
                }
            }
        }

        given("ProjectPath value class") {

            `when`("created with valid input") {
                then("it stores the value") {
                    ProjectPath(":app").value shouldBe ":app"
                }
            }

            `when`("created with root path") {
                then("it stores the value") {
                    ProjectPath(":").value shouldBe ":"
                }
            }

            `when`("toString is called") {
                then("it returns the raw value") {
                    ProjectPath(":app").toString() shouldBe ":app"
                }
            }

            `when`("created with input not starting with colon") {
                then("it throws IllegalArgumentException") {
                    val ex =
                        shouldThrow<IllegalArgumentException> {
                            ProjectPath("app")
                        }
                    ex.message shouldContain "ProjectPath must start with ':'"
                }
            }

            `when`("created with empty input") {
                then("it throws IllegalArgumentException") {
                    shouldThrow<IllegalArgumentException> {
                        ProjectPath("")
                    }
                }
            }
        }
    })
