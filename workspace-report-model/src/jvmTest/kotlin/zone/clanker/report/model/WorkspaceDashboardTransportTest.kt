package zone.clanker.report.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class WorkspaceDashboardTransportTest :
    BehaviorSpec({
        given("a complete typed workspace dashboard") {
            val finding =
                FindingSnapshot(
                    id = "finding:a",
                    severity = FindingSeverity.WARNING,
                    message = "A boundary needs review",
                    suggestion = "Review the dependency direction.",
                    symbolIds = listOf("symbol:a"),
                )
            val findingItem =
                WorkspaceDashboardFinding(
                    id = "dashboard-finding:project:a:finding:a",
                    buildId = "build:a",
                    projectId = "project:a",
                    sourceSetNames = listOf("main"),
                    finding = finding,
                )
            val dashboard =
                WorkspaceDashboardShard(
                    workspaceId = "workspace:a",
                    projectCount = 1,
                    symbolCount = 1,
                    dependencyCount = 2,
                    builds =
                        listOf(
                            WorkspaceBuildDashboard(
                                buildId = "build:a",
                                color = "hsl(10 58% 66%)",
                                projectCount = 1,
                                symbolCount = 1,
                                findingCounts = listOf(AtlasCount("WARNING", "Warning", 1)),
                                sourceSetCounts = listOf(AtlasCount("main", "main", 1)),
                            ),
                        ),
                    projects =
                        listOf(
                            WorkspaceProjectDashboard(
                                projectId = "project:a",
                                buildId = "build:a",
                                fileCount = 1,
                                symbolCount = 1,
                                sourceSetCount = 1,
                                sourceSetNames = listOf("main"),
                                findingIds = listOf(findingItem.id),
                            ),
                        ),
                    findings = listOf(findingItem),
                    hubs =
                        listOf(
                            WorkspaceDashboardHub(
                                id = "dashboard-hub:workspace:hub:a",
                                hub = HubSnapshot("hub:a", "Hub", 2, "domain"),
                            ),
                        ),
                    coverage = WorkspaceDashboardCoverage(1, 1, 1, 1),
                )

            `when`("the shared JSON boundary is crossed") {
                then("the JVM and Wasm-facing dashboard remains reversible") {
                    WorkspaceSiteJson.decodeDashboard(WorkspaceSiteJson.encodeDashboard(dashboard)) shouldBe dashboard
                }
            }

            `when`("a build finding total disagrees with the finding catalog") {
                then("construction rejects the untruthful dashboard") {
                    shouldThrow<IllegalArgumentException> {
                        dashboard.copy(
                            builds =
                                listOf(
                                    dashboard.builds.single().copy(
                                        findingCounts = listOf(AtlasCount("INFO", "Info", 1)),
                                    ),
                                ),
                        )
                    }
                }
            }

            `when`("a project claims a finding from another scope") {
                then("construction rejects the broken ownership") {
                    shouldThrow<IllegalArgumentException> {
                        dashboard.copy(
                            projects =
                                listOf(
                                    dashboard.projects.single().copy(findingIds = listOf("finding:missing")),
                                ),
                        )
                    }
                }
            }

            `when`("a finding build disagrees with its project owner") {
                then("construction rejects the cross-build scope") {
                    shouldThrow<IllegalArgumentException> {
                        dashboard.copy(
                            builds =
                                listOf(
                                    dashboard.builds.single(),
                                    WorkspaceBuildDashboard(
                                        buildId = "build:b",
                                        color = "hsl(20 58% 66%)",
                                        projectCount = 0,
                                        symbolCount = 0,
                                        findingCounts = emptyList(),
                                        sourceSetCounts = emptyList(),
                                    ),
                                ),
                            findings = listOf(findingItem.copy(buildId = "build:b")),
                        )
                    }
                }
            }
        }
    })
