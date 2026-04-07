package zone.clanker.gradle.srcx.report

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import zone.clanker.gradle.srcx.model.DependencyEntry
import zone.clanker.gradle.srcx.model.ProjectSummary
import zone.clanker.gradle.srcx.model.SymbolEntry
import zone.clanker.gradle.srcx.model.SymbolKind

/**
 * Tests for [DashboardRenderer] markdown generation.
 */
class DashboardRendererTest :
    BehaviorSpec({

        given("a DashboardRenderer") {

            `when`("rendering with project summaries") {
                val summaries =
                    listOf(
                        ProjectSummary(
                            projectPath = ":app",
                            symbols =
                                listOf(
                                    SymbolEntry("App", SymbolKind.CLASS, "com.example", "App.kt", 1),
                                    SymbolEntry("run", SymbolKind.FUNCTION, "com.example", "App.kt", 5),
                                ),
                            dependencies =
                                listOf(
                                    DependencyEntry("com.foo", "bar", "1.0", "implementation"),
                                ),
                            buildFile = "build.gradle.kts",
                            sourceDirs = listOf("src/main/kotlin"),
                            subprojects = emptyList(),
                        ),
                        ProjectSummary(
                            projectPath = ":lib",
                            symbols =
                                listOf(
                                    SymbolEntry("Lib", SymbolKind.CLASS, "com.example", "Lib.kt", 1),
                                ),
                            dependencies = emptyList(),
                            buildFile = "build.gradle.kts",
                            sourceDirs = listOf("src/main/kotlin"),
                            subprojects = emptyList(),
                        ),
                    )
                val renderer = DashboardRenderer(summaries, emptyList())
                val output = renderer.render()

                then("it contains the header") {
                    output shouldContain "# Source Dashboard"
                }

                then("it contains the projects table") {
                    output shouldContain "| Project | Symbols | Dependencies | Report |"
                    output shouldContain "| :app | 2 | 1 | [view](app/symbols.md) |"
                    output shouldContain "| :lib | 1 | 0 | [view](lib/symbols.md) |"
                }
            }

            `when`("rendering with included builds") {
                val summaries =
                    listOf(
                        ProjectSummary(":", emptyList(), emptyList(), "build.gradle.kts", emptyList(), emptyList()),
                    )
                val renderer = DashboardRenderer(summaries, listOf("gort", "wrkx"))
                val output = renderer.render()

                then("it contains the included builds section") {
                    output shouldContain "## Included Builds"
                    output shouldContain "| gort | [view](gort/index.md) |"
                    output shouldContain "| wrkx | [view](wrkx/index.md) |"
                }
            }

            `when`("rendering with no included builds") {
                val renderer = DashboardRenderer(emptyList(), emptyList())
                val output = renderer.render()

                then("it does not contain included builds section") {
                    output shouldNotContain "## Included Builds"
                }
            }

            `when`("rendering with no projects") {
                val renderer = DashboardRenderer(emptyList(), emptyList())
                val output = renderer.render()

                then("it shows no projects message") {
                    output shouldContain "No projects found."
                }
            }
        }

        given("projectReportPath companion function") {

            `when`("given root project path") {
                then("it returns root/symbols.md") {
                    DashboardRenderer.projectReportPath(":") shouldBe "root/symbols.md"
                }
            }

            `when`("given a subproject path") {
                then("it returns the sanitized path") {
                    DashboardRenderer.projectReportPath(":app") shouldBe "app/symbols.md"
                }
            }

            `when`("given a nested subproject path") {
                then("it returns the full nested path") {
                    DashboardRenderer.projectReportPath(":lib:core") shouldBe "lib/core/symbols.md"
                }
            }
        }
    })
