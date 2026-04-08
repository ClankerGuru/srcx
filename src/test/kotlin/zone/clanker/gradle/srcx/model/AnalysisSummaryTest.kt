package zone.clanker.gradle.srcx.model

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

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
                    hub.dependents shouldBe 5
                    hub.role shouldBe "service"
                }
            }

            `when`("created with empty role") {
                val hub = HubClass("Foo", 1, "")

                then("role is empty") {
                    hub.role shouldBe ""
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
