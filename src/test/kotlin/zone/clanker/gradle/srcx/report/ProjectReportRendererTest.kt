package zone.clanker.gradle.srcx.report

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import zone.clanker.gradle.srcx.model.DependencyEntry
import zone.clanker.gradle.srcx.model.ProjectSummary
import zone.clanker.gradle.srcx.model.SymbolEntry
import zone.clanker.gradle.srcx.model.SymbolKind

/**
 * Tests for [ProjectReportRenderer] markdown generation.
 */
class ProjectReportRendererTest :
    BehaviorSpec({

        given("a ProjectReportRenderer") {

            `when`("rendering a project with symbols and dependencies") {
                val summary =
                    ProjectSummary(
                        projectPath = ":app",
                        symbols =
                            listOf(
                                SymbolEntry("App", SymbolKind.CLASS, "com.example", "App.kt", 1),
                                SymbolEntry("run", SymbolKind.FUNCTION, "com.example", "App.kt", 5),
                                SymbolEntry("version", SymbolKind.PROPERTY, "com.example", "App.kt", 2),
                            ),
                        dependencies =
                            listOf(
                                DependencyEntry("org.jetbrains.kotlin", "kotlin-stdlib", "2.1.20", "implementation"),
                                DependencyEntry("com.foo", "bar", "1.0", "api"),
                            ),
                        buildFile = "build.gradle.kts",
                        sourceDirs = listOf("src/main/kotlin", "src/main/java"),
                        subprojects = listOf(":app:sub1", ":app:sub2"),
                    )
                val renderer = ProjectReportRenderer(summary)
                val output = renderer.render()

                then("it contains the project header") {
                    output shouldContain "# :app"
                }

                then("it contains the symbols table") {
                    output shouldContain "| Kind | Name | Package | File | Line |"
                    output shouldContain "| CLASS | App | com.example | App.kt | 1 |"
                    output shouldContain "| FUNCTION | run | com.example | App.kt | 5 |"
                    output shouldContain "| PROPERTY | version | com.example | App.kt | 2 |"
                }

                then("it contains the dependencies table") {
                    output shouldContain "| Scope | Artifact | Version |"
                    output shouldContain "| implementation | org.jetbrains.kotlin:kotlin-stdlib | 2.1.20 |"
                    output shouldContain "| api | com.foo:bar | 1.0 |"
                }

                then("it contains the build section") {
                    output shouldContain "- Build file: build.gradle.kts"
                    output shouldContain "- Source dirs: src/main/kotlin, src/main/java"
                    output shouldContain "- Subprojects: :app:sub1, :app:sub2"
                }
            }

            `when`("rendering a project with no symbols") {
                val summary =
                    ProjectSummary(
                        projectPath = ":",
                        symbols = emptyList(),
                        dependencies = emptyList(),
                        buildFile = "build.gradle.kts",
                        sourceDirs = emptyList(),
                        subprojects = emptyList(),
                    )
                val renderer = ProjectReportRenderer(summary)
                val output = renderer.render()

                then("it shows no symbols message") {
                    output shouldContain "No symbols extracted."
                }

                then("it shows no dependencies message") {
                    output shouldContain "No dependencies found."
                }

                then("it does not show subprojects line") {
                    output shouldNotContain "Subprojects:"
                }

                then("it shows empty source dirs") {
                    output shouldContain "- Source dirs: none"
                }
            }

            `when`("rendering a project with build.gradle") {
                val summary =
                    ProjectSummary(
                        projectPath = ":legacy",
                        symbols = emptyList(),
                        dependencies = emptyList(),
                        buildFile = "build.gradle",
                        sourceDirs = emptyList(),
                        subprojects = emptyList(),
                    )
                val renderer = ProjectReportRenderer(summary)
                val output = renderer.render()

                then("it shows the correct build file") {
                    output shouldContain "- Build file: build.gradle"
                }
            }
        }
    })
