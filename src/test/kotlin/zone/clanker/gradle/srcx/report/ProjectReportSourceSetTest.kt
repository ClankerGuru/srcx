package zone.clanker.gradle.srcx.report

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import zone.clanker.gradle.srcx.model.FilePath
import zone.clanker.gradle.srcx.model.PackageName
import zone.clanker.gradle.srcx.model.ProjectPath
import zone.clanker.gradle.srcx.model.ProjectSummary
import zone.clanker.gradle.srcx.model.SourceSetName
import zone.clanker.gradle.srcx.model.SourceSetSummary
import zone.clanker.gradle.srcx.model.SymbolEntry
import zone.clanker.gradle.srcx.model.SymbolKind
import zone.clanker.gradle.srcx.model.SymbolName

class ProjectReportSourceSetTest :
    BehaviorSpec({

        given("ProjectReportRenderer with source sets") {

            `when`("rendering a project with main and test source sets") {
                val mainSymbols =
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
                            3,
                        ),
                    )
                val testSymbols =
                    listOf(
                        SymbolEntry(
                            SymbolName("AppTest"),
                            SymbolKind.CLASS,
                            PackageName("com.example"),
                            FilePath("AppTest.kt"),
                            1,
                        ),
                    )

                val summary =
                    ProjectSummary(
                        projectPath = ProjectPath(":app"),
                        symbols = mainSymbols + testSymbols,
                        dependencies = emptyList(),
                        buildFile = "build.gradle.kts",
                        sourceDirs = listOf("src/main/kotlin", "src/test/kotlin"),
                        subprojects = emptyList(),
                        sourceSets =
                            listOf(
                                SourceSetSummary(
                                    SourceSetName("main"),
                                    mainSymbols,
                                    listOf("src/main/kotlin"),
                                ),
                                SourceSetSummary(
                                    SourceSetName("test"),
                                    testSymbols,
                                    listOf("src/test/kotlin"),
                                ),
                            ),
                    )

                val output = ProjectReportRenderer(summary).render()

                then("it uses source set headers instead of Symbols") {
                    output shouldContain "## main"
                    output shouldContain "## test"
                    output shouldNotContain "## Symbols"
                }

                then("main section contains main symbols") {
                    output shouldContain "| CLASS | App | com.example | App.kt | 1 |"
                    output shouldContain "| FUNCTION | run | com.example | App.kt | 3 |"
                }

                then("test section contains test symbols") {
                    output shouldContain "| CLASS | AppTest | com.example | AppTest.kt | 1 |"
                }
            }

            `when`("rendering with an empty source set") {
                val summary =
                    ProjectSummary(
                        projectPath = ProjectPath(":empty"),
                        symbols = emptyList(),
                        dependencies = emptyList(),
                        buildFile = "build.gradle.kts",
                        sourceDirs = emptyList(),
                        subprojects = emptyList(),
                        sourceSets =
                            listOf(
                                SourceSetSummary(
                                    SourceSetName("main"),
                                    emptyList(),
                                    emptyList(),
                                ),
                            ),
                    )

                val output = ProjectReportRenderer(summary).render()

                then("it shows no symbols message under the source set") {
                    output shouldContain "## main"
                    output shouldContain "No symbols extracted."
                }
            }

            `when`("rendering with no source sets (backward compat)") {
                val summary =
                    ProjectSummary(
                        projectPath = ProjectPath(":legacy"),
                        symbols =
                            listOf(
                                SymbolEntry(
                                    SymbolName("Legacy"),
                                    SymbolKind.CLASS,
                                    PackageName("com.example"),
                                    FilePath("Legacy.kt"),
                                    1,
                                ),
                            ),
                        dependencies = emptyList(),
                        buildFile = "build.gradle.kts",
                        sourceDirs = listOf("src/main/kotlin"),
                        subprojects = emptyList(),
                    )

                val output = ProjectReportRenderer(summary).render()

                then("it falls back to flat Symbols section") {
                    output shouldContain "## Symbols"
                    output shouldContain "| CLASS | Legacy | com.example | Legacy.kt | 1 |"
                }
            }
        }
    })
