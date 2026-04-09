package zone.clanker.gradle.srcx.model

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import zone.clanker.gradle.srcx.model.HubDependentRef

class AnalysisSummaryTest :
    BehaviorSpec({

        given("Finding") {
            `when`("created with WARNING severity") {
                val finding = Finding(FindingSeverity.WARNING, "bad code", "fix it")

                then("it holds the data") {
                    finding.severity shouldBe FindingSeverity.WARNING
                    finding.message shouldBe "bad code"
                    finding.suggestion shouldBe "fix it"
                }
            }
        }

        given("HubClass") {
            `when`("created with a role") {
                val hub = HubClass("Core", 5, "service")

                then("it holds the data") {
                    hub.name shouldBe "Core"
                    hub.dependentCount shouldBe 5
                    hub.role shouldBe "service"
                }
            }

            `when`("created with empty role") {
                val hub = HubClass("Foo", 1, "")

                then("role is empty") {
                    hub.role shouldBe ""
                }
            }

            `when`("created with dependent details") {
                val hub =
                    HubClass(
                        "Core", 2, "service",
                        "com/example/Core.kt", 10,
                        listOf(
                            HubDependentRef("A", "com/example/A.kt", 5),
                            HubDependentRef("B", "com/example/B.kt", 8),
                        ),
                    )

                then("it holds dependent details") {
                    hub.dependents.size shouldBe 2
                    hub.dependents[0].name shouldBe "A"
                    hub.dependents[0].filePath shouldBe "com/example/A.kt"
                    hub.dependents[0].line shouldBe 5
                    hub.filePath shouldBe "com/example/Core.kt"
                    hub.line shouldBe 10
                }
            }
        }

        given("AnalysisSummary") {
            `when`("created with data") {
                val summary =
                    AnalysisSummary(
                        findings = listOf(Finding(FindingSeverity.INFO, "note", "suggestion")),
                        hubs = listOf(HubClass("A", 3, "repository")),
                        cycles = listOf(listOf("X", "Y", "X")),
                    )

                then("it holds findings, hubs, and cycles") {
                    summary.findings.size shouldBe 1
                    summary.hubs.size shouldBe 1
                    summary.cycles.size shouldBe 1
                }
            }
        }
    })
