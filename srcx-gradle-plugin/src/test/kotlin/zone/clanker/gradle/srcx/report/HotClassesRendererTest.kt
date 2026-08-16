package zone.clanker.gradle.srcx.report

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import zone.clanker.gradle.srcx.model.HubClass
import zone.clanker.gradle.srcx.model.HubDependentRef

class HotClassesRendererTest :
    BehaviorSpec({

        given("a HotClassesRenderer") {

            `when`("rendering with no hubs") {
                val renderer = HotClassesRenderer(emptyList())
                val output = renderer.render()

                then("it shows the header and no-data message") {
                    output shouldContain "# Hub Classes"
                    output shouldContain "Classes with the most inbound dependencies across the codebase."
                    output shouldContain "No hub classes detected."
                }
            }

            `when`("rendering with hub classes") {
                val hubs =
                    listOf(
                        HubClass(
                            name = "ChangeConfig",
                            dependentCount = 7,
                            role = "other",
                            filePath = "model/ChangeConfig.kt",
                            line = 5,
                            dependents =
                                listOf(
                                    HubDependentRef("ChangeReader", "workflow/ChangeReader.kt", 8),
                                    HubDependentRef("ApplyTask", "task/ApplyTask.kt", 18),
                                    HubDependentRef("ProposeTask", "task/ProposeTask.kt", 12),
                                ),
                        ),
                        HubClass(
                            name = "Util",
                            dependentCount = 2,
                            role = "",
                            filePath = "util/Util.kt",
                            line = 1,
                            dependents =
                                listOf(
                                    HubDependentRef("A", "A.kt", 1),
                                    HubDependentRef("B", "B.kt", 1),
                                ),
                        ),
                    )
                val renderer = HotClassesRenderer(hubs)
                val output = renderer.render()

                then("it contains the hub table") {
                    output shouldContain "| Class | File | Dependents | Role |"
                    output shouldContain "| `ChangeConfig` | model/ChangeConfig.kt:5 | 7 | other |"
                    output shouldContain "| `Util` | util/Util.kt:1 | 2 |  |"
                }

                then("it shows tree detail section for hubs with 3+ dependents") {
                    output shouldContain "## ChangeConfig"
                    output shouldContain "model/ChangeConfig.kt:5 \u2014 7 dependents"
                    output shouldContain "\u251C\u2500\u2500 workflow/ChangeReader.kt:8"
                    output shouldContain "\u251C\u2500\u2500 task/ApplyTask.kt:18"
                    output shouldContain "\u2514\u2500\u2500 task/ProposeTask.kt:12"
                }

                then("it does not show detail section for hubs below threshold") {
                    output shouldNotContain "## Util"
                }
            }
        }
    })
