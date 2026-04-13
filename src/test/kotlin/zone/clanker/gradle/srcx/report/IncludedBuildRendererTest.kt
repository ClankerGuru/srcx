package zone.clanker.gradle.srcx.report

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import zone.clanker.gradle.srcx.model.AnalysisSummary
import zone.clanker.gradle.srcx.model.ArtifactGroup
import zone.clanker.gradle.srcx.model.ArtifactName
import zone.clanker.gradle.srcx.model.ArtifactVersion
import zone.clanker.gradle.srcx.model.DependencyEntry
import zone.clanker.gradle.srcx.model.FilePath
import zone.clanker.gradle.srcx.model.Finding
import zone.clanker.gradle.srcx.model.FindingSeverity
import zone.clanker.gradle.srcx.model.HubClass
import zone.clanker.gradle.srcx.model.HubDependentRef
import zone.clanker.gradle.srcx.model.PackageName
import zone.clanker.gradle.srcx.model.ProjectPath
import zone.clanker.gradle.srcx.model.ProjectSummary
import zone.clanker.gradle.srcx.model.SourceSetName
import zone.clanker.gradle.srcx.model.SourceSetSummary
import zone.clanker.gradle.srcx.model.SymbolEntry
import zone.clanker.gradle.srcx.model.SymbolKind
import zone.clanker.gradle.srcx.model.SymbolName

class IncludedBuildRendererTest :
    BehaviorSpec({

        given("an IncludedBuildRenderer") {

            `when`("rendering with full context") {
                val summaries =
                    listOf(
                        ProjectSummary(
                            projectPath = ProjectPath(":"),
                            symbols =
                                listOf(
                                    SymbolEntry(
                                        SymbolName("Codec"), SymbolKind.CLASS,
                                        PackageName("com.example"), FilePath("Codec.kt"), 1,
                                    ),
                                    SymbolEntry(
                                        SymbolName("encode"), SymbolKind.FUNCTION,
                                        PackageName("com.example"), FilePath("Codec.kt"), 5,
                                    ),
                                ),
                            dependencies =
                                listOf(
                                    DependencyEntry(
                                        ArtifactGroup("com.foo"), ArtifactName("bar"),
                                        ArtifactVersion("1.0"), "implementation",
                                    ),
                                ),
                            buildFile = "build.gradle.kts",
                            sourceDirs = listOf("src/main/kotlin"),
                            subprojects = emptyList(),
                            sourceSets =
                                listOf(
                                    SourceSetSummary(
                                        SourceSetName("main"),
                                        listOf(
                                            SymbolEntry(
                                                SymbolName("Codec"), SymbolKind.CLASS,
                                                PackageName("com.example"), FilePath("Codec.kt"), 1,
                                            ),
                                            SymbolEntry(
                                                SymbolName("encode"), SymbolKind.FUNCTION,
                                                PackageName("com.example"), FilePath("Codec.kt"), 5,
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
                                                "`Codec` may be doing too much",
                                                "Split into smaller classes",
                                            ),
                                        ),
                                    hubs =
                                        listOf(
                                            HubClass(
                                                "Codec", 2, "service",
                                                "com/example/Codec.kt", 1,
                                                listOf(
                                                    HubDependentRef("A", "com/example/A.kt", 3),
                                                    HubDependentRef("B", "com/example/B.kt", 7),
                                                ),
                                            ),
                                        ),
                                    cycles = emptyList(),
                                ),
                        ),
                    )
                val output = IncludedBuildRenderer("codec", summaries).render()

                then("it contains the overview") {
                    output shouldContain "## Overview"
                    output shouldContain "2 symbols across 1 project(s)"
                    output shouldContain "1 warning(s)"
                    output shouldContain "com.example"
                }

                then("it contains symbols with hub annotations") {
                    output shouldContain "## main"
                    output shouldContain "class `Codec` [service] (2 dependents)"
                    output shouldContain "1 functions"
                }

                then("it contains hub class tree") {
                    output shouldContain "## Hub Classes"
                    output shouldContain "**Codec** [service] — com/example/Codec.kt:1"
                    output shouldContain "  - A — com/example/A.kt:3"
                    output shouldContain "  - B — com/example/B.kt:7"
                }

                then("it contains dependencies") {
                    output shouldContain "## Dependencies"
                    output shouldContain "implementation: com.foo:bar:1.0"
                }

                then("it contains problems") {
                    output shouldContain "## Problems"
                    output shouldContain "**⚠\uFE0F** `Codec` may be doing too much"
                }

                then("it does not show projects table for single project") {
                    output shouldNotContain "## Projects"
                }
            }

            `when`("rendering with multiple projects") {
                val summaries =
                    listOf(
                        ProjectSummary(
                            ProjectPath(":app"), emptyList(), emptyList(),
                            "build.gradle.kts", emptyList(),
                            listOf(":lib"),
                        ),
                        ProjectSummary(
                            ProjectPath(":lib"),
                            listOf(
                                SymbolEntry(
                                    SymbolName("Lib"), SymbolKind.CLASS,
                                    PackageName("com.example"), FilePath("Lib.kt"), 1,
                                ),
                            ),
                            emptyList(), "build.gradle.kts", emptyList(), emptyList(),
                            sourceSets =
                                listOf(
                                    SourceSetSummary(
                                        SourceSetName("main"),
                                        listOf(
                                            SymbolEntry(
                                                SymbolName("Lib"), SymbolKind.CLASS,
                                                PackageName("com.example"),
                                                FilePath("Lib.kt"), 1,
                                            ),
                                        ),
                                        listOf("src/main/kotlin"),
                                    ),
                                ),
                        ),
                    )
                val output = IncludedBuildRenderer("multi", summaries).render()

                then("it shows projects table") {
                    output shouldContain "## Projects"
                    output shouldContain "| :app |"
                    output shouldContain "| :lib |"
                }

                then("it renders symbols without hub annotations") {
                    output shouldContain "class `Lib`"
                    output shouldNotContain "dependents"
                }
            }

            `when`("rendering with no projects") {
                val output = IncludedBuildRenderer("empty-build", emptyList()).render()

                then("it shows overview with zero symbols") {
                    output shouldContain "0 symbols"
                }

                then("it does not show empty sections") {
                    output shouldNotContain "## Hub Classes"
                    output shouldNotContain "## Dependencies"
                    output shouldNotContain "## Problems"
                }
            }
        }
    })
