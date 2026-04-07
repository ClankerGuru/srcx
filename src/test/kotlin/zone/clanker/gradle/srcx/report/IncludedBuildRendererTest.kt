package zone.clanker.gradle.srcx.report

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.string.shouldContain
import zone.clanker.gradle.srcx.model.FilePath
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

            `when`("rendering with project summaries") {
                val summaries =
                    listOf(
                        ProjectSummary(
                            projectPath = ProjectPath(":"),
                            symbols =
                                listOf(
                                    SymbolEntry(
                                        SymbolName("Codec"),
                                        SymbolKind.CLASS,
                                        PackageName("com.example"),
                                        FilePath("Codec.kt"),
                                        1,
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
                                                SymbolName("Codec"),
                                                SymbolKind.CLASS,
                                                PackageName("com.example"),
                                                FilePath("Codec.kt"),
                                                1,
                                            ),
                                        ),
                                        listOf("src/main/kotlin"),
                                    ),
                                ),
                        ),
                    )
                val renderer = IncludedBuildRenderer("codec", summaries)
                val output = renderer.render()

                then("it contains the build name as header") {
                    output shouldContain "# codec"
                }

                then("it contains the projects table with source sets") {
                    output shouldContain "| Project | Symbols | Source Sets | Report |"
                    output shouldContain "| : | 1 | main | [view](root/symbols.md) |"
                }
            }

            `when`("rendering with no projects") {
                val renderer = IncludedBuildRenderer("empty-build", emptyList())
                val output = renderer.render()

                then("it shows no projects message") {
                    output shouldContain "No projects found."
                }
            }
        }
    })
