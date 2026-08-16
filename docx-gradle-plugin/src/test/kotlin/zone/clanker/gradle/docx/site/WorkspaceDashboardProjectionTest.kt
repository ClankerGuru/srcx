package zone.clanker.gradle.docx.site

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import zone.clanker.gradle.docx.docxTestPlan
import zone.clanker.gradle.docx.workspaceFixture
import zone.clanker.report.model.FindingSeverity
import zone.clanker.report.model.FindingSnapshot
import zone.clanker.report.model.HubSnapshot
import zone.clanker.report.model.ProjectAnalysisSnapshot
import zone.clanker.report.model.ProjectDependencySnapshot

class WorkspaceDashboardProjectionTest :
    BehaviorSpec({
        given("a projected workspace with project analysis") {
            val finding =
                FindingSnapshot(
                    id = "finding:a",
                    severity = FindingSeverity.WARNING,
                    message = "Consumer crosses a boundary",
                    suggestion = "Review the dependency direction.",
                    fileId = "file:a",
                    filePath = "src/main/kotlin/Consumer.kt",
                    line = 1,
                    symbolIds = listOf("symbol:a"),
                )
            val hub =
                HubSnapshot(
                    id = "hub:a",
                    name = "Consumer",
                    dependentCount = 2,
                    role = "domain",
                    filePath = "src/main/kotlin/Consumer.kt",
                    line = 1,
                    sourceFileId = "file:a",
                )
            val snapshot =
                workspaceFixture().copy(
                    projectDependencies =
                        listOf(
                            ProjectDependencySnapshot(
                                id = "dependency:a",
                                projectId = "project:a",
                                group = "example",
                                artifact = "library",
                                version = "1.0",
                                scope = "implementation",
                            ),
                        ),
                    projectAnalyses =
                        listOf(
                            ProjectAnalysisSnapshot(
                                id = "analysis:a",
                                projectId = "project:a",
                                findings = listOf(finding),
                                hubs = listOf(hub),
                            ),
                        ),
                )

            `when`("the complete static-dashboard transport is projected") {
                val site = DocxSiteProjection.apply(snapshot, docxTestPlan())
                val dashboard = WorkspaceDashboardProjection.apply(snapshot, site)

                then("its legacy frame, build, health, and finding facts remain exact") {
                    dashboard.workspaceId shouldBe "workspace:a"
                    dashboard.projectCount shouldBe 1
                    dashboard.symbolCount shouldBe 2
                    dashboard.dependencyCount shouldBe 1
                    dashboard.builds
                        .single()
                        .findingCounts
                        .map { count -> count.key to count.count } shouldContainExactly
                        listOf("WARNING" to 1)
                    dashboard.builds
                        .single()
                        .sourceSetCounts
                        .map { count -> count.key to count.count } shouldContainExactly
                        listOf("main" to 1)
                    dashboard.findings.single().finding shouldBe finding
                    dashboard.hubs.single().hub shouldBe hub
                    dashboard.coverage.projectsWithSymbols shouldBe 1
                    dashboard.coverage.sourceSetCount shouldBe 1
                    dashboard.coverage.packageCount shouldBe 1
                    dashboard.coverage.projectAnalysisCount shouldBe 1
                }
            }
        }

        given("an aggregate production-hub analysis") {
            val projectHub = HubSnapshot("hub:project", "ProjectHub", 1, "project")
            val aggregateHub = HubSnapshot("hub:aggregate", "AggregateHub", 3, "workspace")
            val testHub = HubSnapshot("hub:test", "TestHub", 2, "test", isTest = true)
            val snapshot =
                workspaceFixture().copy(
                    projectAnalyses =
                        listOf(
                            ProjectAnalysisSnapshot(
                                id = "analysis:a",
                                projectId = "project:a",
                                hubs = listOf(projectHub),
                            ),
                        ),
                    aggregateAnalysisPresent = true,
                    aggregateHubs = listOf(aggregateHub, testHub),
                )

            `when`("the Health section chooses its canonical hub source") {
                val site = DocxSiteProjection.apply(snapshot, docxTestPlan())
                val dashboard = WorkspaceDashboardProjection.apply(snapshot, site)

                then("aggregate production hubs replace project and test hubs just as the legacy report does") {
                    dashboard.hubs.map { item -> item.hub.id } shouldContainExactly listOf("hub:aggregate")
                    dashboard.hubs.single().projectId shouldBe null
                }
            }
        }
    })
