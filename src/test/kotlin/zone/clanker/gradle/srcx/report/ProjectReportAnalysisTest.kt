package zone.clanker.gradle.srcx.report

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import zone.clanker.gradle.srcx.model.AnalysisSummary
import zone.clanker.gradle.srcx.model.FilePath
import zone.clanker.gradle.srcx.model.Finding
import zone.clanker.gradle.srcx.model.FindingSeverity
import zone.clanker.gradle.srcx.model.HubClass
import zone.clanker.gradle.srcx.model.PackageName
import zone.clanker.gradle.srcx.model.ProjectPath
import zone.clanker.gradle.srcx.model.ProjectSummary
import zone.clanker.gradle.srcx.model.SourceSetName
import zone.clanker.gradle.srcx.model.SourceSetSummary
import zone.clanker.gradle.srcx.model.SymbolEntry
import zone.clanker.gradle.srcx.model.SymbolKind
import zone.clanker.gradle.srcx.model.SymbolName

class ProjectReportAnalysisTest :
    BehaviorSpec({

        given("ProjectReportRenderer with analysis") {

            `when`("rendering with warnings and hubs") {
                val summary =
                    ProjectSummary(
                        projectPath = ProjectPath(":app"),
                        symbols =
                            listOf(
                                SymbolEntry(
                                    SymbolName("App"), SymbolKind.CLASS,
                                    PackageName("com.example"), FilePath("App.kt"), 1,
                                ),
                            ),
                        dependencies = emptyList(),
                        buildFile = "build.gradle.kts",
                        sourceDirs = listOf("src/main/kotlin"),
                        subprojects = emptyList(),
                        sourceSets =
                            listOf(
                                SourceSetSummary(
                                    SourceSetName("main"),
                                    listOf(
                                        SymbolEntry(
                                            SymbolName("App"), SymbolKind.CLASS,
                                            PackageName("com.example"), FilePath("App.kt"), 1,
                                        ),
                                    ),
                                    listOf("src/main/kotlin"),
                                ),
                            ),
                        analysis =
                            AnalysisSummary(
                                findings =
                                    listOf(
                                        Finding(
                                            FindingSeverity.WARNING,
                                            "`FooHelper` is a helper class",
                                            "Move methods closer",
                                        ),
                                        Finding(
                                            FindingSeverity.INFO,
                                            "`Bar` has no test",
                                            "Add BarTest",
                                        ),
                                    ),
                                hubs =
                                    listOf(
                                        HubClass("Core", 5, "service"),
                                        HubClass("Repo", 3, "repository"),
                                    ),
                                cycles = listOf(listOf("A", "B", "A")),
                            ),
                    )

                val output = ProjectReportRenderer(summary).render()

                then("it contains hub classes section") {
                    output shouldContain "## Hub Classes"
                    output shouldContain "| Core (service) | 5 |"
                    output shouldContain "| Repo (repository) | 3 |"
                }

                then("it contains findings section") {
                    output shouldContain "## Findings"
                    output shouldContain "**WARNING** `FooHelper` is a helper class"
                    output shouldContain "Move methods closer"
                    output shouldContain "**INFO** `Bar` has no test"
                }

                then("it contains cycles section") {
                    output shouldContain "## Circular Dependencies"
                    output shouldContain "A -> B -> A"
                }
            }

            `when`("rendering with no analysis") {
                val summary =
                    ProjectSummary(
                        projectPath = ProjectPath(":clean"),
                        symbols = emptyList(),
                        dependencies = emptyList(),
                        buildFile = "build.gradle.kts",
                        sourceDirs = emptyList(),
                        subprojects = emptyList(),
                    )

                val output = ProjectReportRenderer(summary).render()

                then("it does not contain analysis sections") {
                    output shouldNotContain "Hub Classes"
                    output shouldNotContain "Warnings"
                    output shouldNotContain "Circular Dependencies"
                }
            }

            `when`("rendering with empty analysis") {
                val summary =
                    ProjectSummary(
                        projectPath = ProjectPath(":empty"),
                        symbols = emptyList(),
                        dependencies = emptyList(),
                        buildFile = "build.gradle.kts",
                        sourceDirs = emptyList(),
                        subprojects = emptyList(),
                        analysis = AnalysisSummary(emptyList(), emptyList(), emptyList()),
                    )

                val output = ProjectReportRenderer(summary).render()

                then("it does not show empty sections") {
                    output shouldNotContain "Hub Classes"
                    output shouldNotContain "Warnings"
                    output shouldNotContain "Circular Dependencies"
                }
            }
        }
    })
