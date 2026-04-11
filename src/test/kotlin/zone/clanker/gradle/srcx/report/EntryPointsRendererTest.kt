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

class EntryPointsRendererTest :
    BehaviorSpec({

        given("an EntryPointsRenderer") {

            `when`("rendering with app entry points") {
                val entryPoints =
                    listOf(
                        EntryPointsRenderer.EntryPoint("AppController", "com.example.app", "handleRequest"),
                    )
                val renderer = EntryPointsRenderer(emptyList(), entryPoints)
                val output = renderer.render()

                then("it shows app entry points") {
                    output shouldContain "## App Entry Points"
                    output shouldContain "| `AppController` | com.example.app | handleRequest |"
                }
            }

            `when`("rendering with test classes") {
                val summaries =
                    listOf(
                        ProjectSummary(
                            projectPath = ProjectPath(":app"),
                            symbols = emptyList(),
                            dependencies = emptyList(),
                            buildFile = "build.gradle.kts",
                            sourceDirs = emptyList(),
                            subprojects = emptyList(),
                            sourceSets =
                                listOf(
                                    SourceSetSummary(
                                        SourceSetName("test"),
                                        listOf(
                                            SymbolEntry(
                                                SymbolName("AppTest"),
                                                SymbolKind.CLASS,
                                                PackageName("com.example"),
                                                FilePath("AppTest.kt"),
                                                1,
                                            ),
                                        ),
                                        listOf("src/test/kotlin"),
                                    ),
                                ),
                        ),
                    )
                val renderer = EntryPointsRenderer(summaries)
                val output = renderer.render()

                then("it shows test entry points") {
                    output shouldContain "## Test Entry Points"
                    output shouldContain "| `AppTest` | com.example |"
                }
            }

            `when`("rendering with test doubles") {
                val summaries =
                    listOf(
                        ProjectSummary(
                            projectPath = ProjectPath(":app"),
                            symbols = emptyList(),
                            dependencies = emptyList(),
                            buildFile = "build.gradle.kts",
                            sourceDirs = emptyList(),
                            subprojects = emptyList(),
                            sourceSets =
                                listOf(
                                    SourceSetSummary(
                                        SourceSetName("test"),
                                        listOf(
                                            SymbolEntry(
                                                SymbolName("MockRepository"),
                                                SymbolKind.CLASS,
                                                PackageName("com.example"),
                                                FilePath("MockRepository.kt"),
                                                1,
                                            ),
                                            SymbolEntry(
                                                SymbolName("FakeService"),
                                                SymbolKind.CLASS,
                                                PackageName("com.example"),
                                                FilePath("FakeService.kt"),
                                                1,
                                            ),
                                        ),
                                        listOf("src/test/kotlin"),
                                    ),
                                ),
                        ),
                    )
                val renderer = EntryPointsRenderer(summaries)
                val output = renderer.render()

                then("it shows test doubles") {
                    output shouldContain "## Test Doubles"
                    output shouldContain "| `MockRepository` | com.example | Mock |"
                    output shouldContain "| `FakeService` | com.example | Fake |"
                }
            }

            `when`("rendering with no data") {
                val renderer = EntryPointsRenderer(emptyList())
                val output = renderer.render()

                then("it shows no-data messages") {
                    output shouldContain "No app entry points detected."
                    output shouldContain "No test classes found."
                    output shouldContain "No test doubles found."
                }
            }
        }
    })
