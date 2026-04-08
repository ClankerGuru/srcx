package zone.clanker.gradle.srcx.report

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import zone.clanker.gradle.srcx.model.ArtifactGroup
import zone.clanker.gradle.srcx.model.ArtifactName
import zone.clanker.gradle.srcx.model.ArtifactVersion
import zone.clanker.gradle.srcx.model.DependencyEntry
import zone.clanker.gradle.srcx.model.FilePath
import zone.clanker.gradle.srcx.model.PackageName
import zone.clanker.gradle.srcx.model.ProjectPath
import zone.clanker.gradle.srcx.model.ProjectSummary
import zone.clanker.gradle.srcx.model.SymbolEntry
import zone.clanker.gradle.srcx.model.SymbolKind
import zone.clanker.gradle.srcx.model.SymbolName

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
                            projectPath = ProjectPath(":app"),
                            symbols =
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
                                ),
                            dependencies =
                                listOf(
                                    DependencyEntry(
                                        ArtifactGroup("com.foo"),
                                        ArtifactName("bar"),
                                        ArtifactVersion("1.0"),
                                        "implementation",
                                    ),
                                ),
                            buildFile = "build.gradle.kts",
                            sourceDirs = listOf("src/main/kotlin"),
                            subprojects = emptyList(),
                        ),
                        ProjectSummary(
                            projectPath = ProjectPath(":lib"),
                            symbols =
                                listOf(
                                    SymbolEntry(
                                        SymbolName("Lib"),
                                        SymbolKind.CLASS,
                                        PackageName("com.example"),
                                        FilePath("Lib.kt"),
                                        1,
                                    ),
                                ),
                            dependencies = emptyList(),
                            buildFile = "build.gradle.kts",
                            sourceDirs = listOf("src/main/kotlin"),
                            subprojects = emptyList(),
                        ),
                    )
                val renderer = DashboardRenderer("test-workspace", summaries, emptyList())
                val output = renderer.render()

                then("it contains the header") {
                    output shouldContain "# test-workspace"
                }

                then("it contains the projects table") {
                    output shouldContain "| Project | Symbols | Source Sets | Dependencies | Warnings |"
                    output shouldContain "| :app | 2 | - | 1 | 0 |"
                    output shouldContain "| :lib | 1 | - | 0 | 0 |"
                }
            }

            `when`("rendering with included builds") {
                val summaries =
                    listOf(
                        ProjectSummary(
                            ProjectPath(":"),
                            emptyList(),
                            emptyList(),
                            "build.gradle.kts",
                            emptyList(),
                            emptyList(),
                        ),
                    )
                val refs =
                    listOf(
                        DashboardRenderer.IncludedBuildRef("gort", "../gort"),
                        DashboardRenderer.IncludedBuildRef("wrkx", "../wrkx"),
                    )
                val renderer = DashboardRenderer("test-workspace", summaries, refs)
                val output = renderer.render()

                then("it contains the included builds section") {
                    output shouldContain "## Included Builds"
                    output shouldContain "| gort |"
                    output shouldContain "[view](../gort/.srcx/context.md)"
                    output shouldContain "| wrkx |"
                    output shouldContain "[view](../wrkx/.srcx/context.md)"
                }
            }

            `when`("rendering with no included builds") {
                val renderer = DashboardRenderer("test-workspace", emptyList(), emptyList())
                val output = renderer.render()

                then("it does not contain included builds section") {
                    output shouldNotContain "## Included Builds"
                }
            }

            `when`("rendering with no projects") {
                val renderer = DashboardRenderer("test-workspace", emptyList(), emptyList())
                val output = renderer.render()

                then("it shows the header and overview") {
                    output shouldContain "# test-workspace"
                    output shouldContain "## Overview"
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
