package zone.clanker.gradle.srcx.report

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import zone.clanker.gradle.srcx.model.AnalysisSummary
import zone.clanker.gradle.srcx.model.Finding
import zone.clanker.gradle.srcx.model.FindingSeverity
import zone.clanker.gradle.srcx.model.ProjectPath
import zone.clanker.gradle.srcx.model.ProjectSummary

class AntiPatternsRendererTest :
    BehaviorSpec({

        given("an AntiPatternsRenderer") {

            `when`("rendering with no findings") {
                val renderer = AntiPatternsRenderer(emptyList())
                val output = renderer.render()

                then("it shows no-data message") {
                    output shouldContain "# Anti-Patterns"
                    output shouldContain "No anti-patterns detected."
                }
            }

            `when`("rendering with findings from projects") {
                val summaries =
                    listOf(
                        ProjectSummary(
                            projectPath = ProjectPath(":app"),
                            symbols = emptyList(),
                            dependencies = emptyList(),
                            buildFile = "build.gradle.kts",
                            sourceDirs = emptyList(),
                            subprojects = emptyList(),
                            analysis =
                                AnalysisSummary(
                                    findings =
                                        listOf(
                                            Finding(
                                                FindingSeverity.FORBIDDEN,
                                                "Package uses forbidden name",
                                                "Rename the package",
                                            ),
                                            Finding(
                                                FindingSeverity.WARNING,
                                                "`AppHelper` is a helper class",
                                                "Move methods closer",
                                            ),
                                            Finding(
                                                FindingSeverity.INFO,
                                                "`App` has no test",
                                                "Add AppTest",
                                            ),
                                        ),
                                    hubs = emptyList(),
                                    cycles = emptyList(),
                                ),
                        ),
                    )
                val renderer = AntiPatternsRenderer(summaries)
                val output = renderer.render()

                then("it groups findings by severity") {
                    output shouldContain "## Forbidden"
                    output shouldContain "Package uses forbidden name"
                    output shouldContain "  - Rename the package"
                    output shouldContain "## Warnings"
                    output shouldContain "`AppHelper` is a helper class"
                    output shouldContain "## Notes"
                    output shouldContain "`App` has no test"
                }
            }

            `when`("rendering with findings from included builds") {
                val includedBuildSummaries =
                    mapOf(
                        "lib" to
                            listOf(
                                ProjectSummary(
                                    projectPath = ProjectPath(":"),
                                    symbols = emptyList(),
                                    dependencies = emptyList(),
                                    buildFile = "build.gradle.kts",
                                    sourceDirs = emptyList(),
                                    subprojects = emptyList(),
                                    analysis =
                                        AnalysisSummary(
                                            findings =
                                                listOf(
                                                    Finding(
                                                        FindingSeverity.WARNING,
                                                        "Circular dependency: A -> B -> A",
                                                        "Break the cycle",
                                                    ),
                                                ),
                                            hubs = emptyList(),
                                            cycles = emptyList(),
                                        ),
                                ),
                            ),
                    )
                val renderer = AntiPatternsRenderer(emptyList(), includedBuildSummaries)
                val output = renderer.render()

                then("it includes findings from included builds") {
                    output shouldContain "**lib**"
                    output shouldContain "Circular dependency: A -> B -> A"
                }
            }

            `when`("rendering deduplicates by message") {
                val finding =
                    Finding(FindingSeverity.WARNING, "duplicate message", "fix it")
                val summaries =
                    listOf(
                        ProjectSummary(
                            projectPath = ProjectPath(":a"),
                            symbols = emptyList(),
                            dependencies = emptyList(),
                            buildFile = "build.gradle.kts",
                            sourceDirs = emptyList(),
                            subprojects = emptyList(),
                            analysis =
                                AnalysisSummary(
                                    findings = listOf(finding),
                                    hubs = emptyList(),
                                    cycles = emptyList(),
                                ),
                        ),
                        ProjectSummary(
                            projectPath = ProjectPath(":b"),
                            symbols = emptyList(),
                            dependencies = emptyList(),
                            buildFile = "build.gradle.kts",
                            sourceDirs = emptyList(),
                            subprojects = emptyList(),
                            analysis =
                                AnalysisSummary(
                                    findings = listOf(finding),
                                    hubs = emptyList(),
                                    cycles = emptyList(),
                                ),
                        ),
                    )
                val renderer = AntiPatternsRenderer(summaries)
                val output = renderer.render()

                then("it shows the duplicate message only once") {
                    val occurrences = output.split("duplicate message").size - 1
                    occurrences shouldBe 1
                }
            }
        }
    })
