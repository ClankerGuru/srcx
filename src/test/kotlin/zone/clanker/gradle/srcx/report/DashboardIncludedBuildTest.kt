package zone.clanker.gradle.srcx.report

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.string.shouldContain
import zone.clanker.gradle.srcx.model.FilePath
import zone.clanker.gradle.srcx.model.PackageName
import zone.clanker.gradle.srcx.model.ProjectPath
import zone.clanker.gradle.srcx.model.ProjectSummary
import zone.clanker.gradle.srcx.model.SymbolEntry
import zone.clanker.gradle.srcx.model.SymbolKind
import zone.clanker.gradle.srcx.model.SymbolName

class DashboardIncludedBuildTest :
    BehaviorSpec({

        given("DashboardRenderer with included build summaries") {

            `when`("rendering with build summaries") {
                val codecSummaries =
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
                                        SymbolName("StringCodec"), SymbolKind.CLASS,
                                        PackageName("com.example"), FilePath("Codec.kt"), 5,
                                    ),
                                ),
                            dependencies = emptyList(),
                            buildFile = "build.gradle.kts",
                            sourceDirs = listOf("src/main/kotlin"),
                            subprojects = emptyList(),
                        ),
                    )
                val httpCoreSummaries =
                    listOf(
                        ProjectSummary(
                            projectPath = ProjectPath(":"),
                            symbols =
                                (1..5).map {
                                    SymbolEntry(
                                        SymbolName("Sym$it"), SymbolKind.CLASS,
                                        PackageName("com.example"), FilePath("Sym.kt"), it,
                                    )
                                },
                            dependencies = emptyList(),
                            buildFile = "build.gradle.kts",
                            sourceDirs = listOf("src/main/kotlin"),
                            subprojects = emptyList(),
                        ),
                    )

                val refs =
                    listOf(
                        DashboardRenderer.IncludedBuildRef("codec", "../libs/codec"),
                        DashboardRenderer.IncludedBuildRef("http-core", "../libs/http-core"),
                    )
                val renderer =
                    DashboardRenderer(
                        rootName = "test-workspace",
                        summaries = emptyList(),
                        includedBuilds = refs,
                        includedBuildSummaries =
                            mapOf(
                                "codec" to codecSummaries,
                                "http-core" to httpCoreSummaries,
                            ),
                    )
                val output = renderer.render()

                then("it shows project and symbol counts per build") {
                    output shouldContain "| codec | 1 | 2 |"
                    output shouldContain "| http-core | 1 | 5 |"
                }

                then("it links to the build's own .srcx directory") {
                    output shouldContain "[view](../libs/codec/.srcx/context.md)"
                    output shouldContain "[view](../libs/http-core/.srcx/context.md)"
                }

                then("it has the correct table headers") {
                    output shouldContain "| Build | Projects | Symbols | Warnings | Context |"
                }
            }
        }
    })
