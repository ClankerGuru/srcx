package zone.clanker.gradle.srcx.report

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.string.shouldContain
import zone.clanker.gradle.srcx.model.AnalysisSummary
import zone.clanker.gradle.srcx.model.HubClass

class CrossBuildRendererTest :
    BehaviorSpec({

        given("a CrossBuildRenderer") {

            `when`("rendering with no data") {
                val renderer = CrossBuildRenderer(emptyList(), null)
                val output = renderer.render()

                then("it shows the no-data message") {
                    output shouldContain "# Cross-Build References"
                    output shouldContain "No cross-build references detected."
                }
            }

            `when`("rendering with build edges") {
                val edges =
                    listOf(
                        DashboardRenderer.BuildEdge("app", "lib"),
                        DashboardRenderer.BuildEdge("app", "core"),
                    )
                val renderer = CrossBuildRenderer(edges, null)
                val output = renderer.render()

                then("it shows build dependencies grouped by source") {
                    output shouldContain "## Build Dependencies"
                    output shouldContain "### app"
                    output shouldContain "- depends on **lib**"
                    output shouldContain "- depends on **core**"
                }
            }

            `when`("rendering with cross-build hubs") {
                val analysis =
                    AnalysisSummary(
                        findings = emptyList(),
                        hubs =
                            listOf(
                                HubClass("Config", 5, "model", "model/Config.kt", 3),
                            ),
                        cycles = emptyList(),
                    )
                val renderer = CrossBuildRenderer(emptyList(), analysis)
                val output = renderer.render()

                then("it shows shared hub classes table") {
                    output shouldContain "## Shared Hub Classes"
                    output shouldContain "| `Config` | model/Config.kt:3 | 5 | model |"
                }
            }

            `when`("rendering with cross-build cycles") {
                val analysis =
                    AnalysisSummary(
                        findings = emptyList(),
                        hubs = emptyList(),
                        cycles = listOf(listOf("A", "B", "A")),
                    )
                val renderer = CrossBuildRenderer(emptyList(), analysis)
                val output = renderer.render()

                then("it shows cycles") {
                    output shouldContain "## Cross-Build Cycles"
                    output shouldContain "- A -> B -> A"
                }
            }
        }
    })
