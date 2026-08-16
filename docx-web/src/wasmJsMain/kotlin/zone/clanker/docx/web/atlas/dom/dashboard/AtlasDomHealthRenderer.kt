package zone.clanker.docx.web.atlas.dom.dashboard

import org.w3c.dom.HTMLDivElement
import org.w3c.dom.HTMLElement
import zone.clanker.docx.web.atlas.dom.clearContent
import zone.clanker.docx.web.atlas.dom.htmlElement
import zone.clanker.docx.web.atlas.dom.requiredElement
import zone.clanker.docx.web.atlas.dom.requiredHtmlElement
import zone.clanker.report.model.BuildSnapshot
import zone.clanker.report.model.FindingSeverity
import zone.clanker.report.model.WorkspaceBuildDashboard
import zone.clanker.report.model.WorkspaceDashboardHub
import zone.clanker.report.model.WorkspaceDashboardShard
import zone.clanker.report.model.WorkspaceSummaryShard

internal class AtlasDomHealthRenderer {
    fun render(
        root: HTMLDivElement,
        summary: WorkspaceSummaryShard,
        dashboard: WorkspaceDashboardShard,
    ) {
        val buildsById = summary.builds.associateBy(BuildSnapshot::id)
        renderSymbolOwnership(root, buildsById, dashboard)
        renderSeverity(root, dashboard)
        renderHubs(root, dashboard.hubs)
        renderCoverage(root, dashboard)
    }

    private fun renderSymbolOwnership(
        root: HTMLDivElement,
        buildsById: Map<String, BuildSnapshot>,
        dashboard: WorkspaceDashboardShard,
    ) {
        val symbolBar = root.requiredHtmlElement("[data-docx-symbol-bar]")
        val legend = root.requiredHtmlElement("[data-docx-symbol-legend]")
        symbolBar.clearContent()
        legend.clearContent()
        dashboard.builds
            .sortedByDescending(WorkspaceBuildDashboard::symbolCount)
            .forEach { build ->
                val name = requireNotNull(buildsById[build.buildId]).name
                val percent = dashboardPercentageNumber(build.symbolCount, dashboard.symbolCount)
                symbolBar.appendChild(symbolSegment(build, name, percent))
                legend.appendChild(symbolLegendItem(build, name, percent))
            }
    }

    private fun symbolSegment(
        build: WorkspaceBuildDashboard,
        name: String,
        percent: String,
    ): HTMLElement =
        htmlElement(
            "span",
            "srcx-dashboard__symbol-segment srcx-dashboard__symbol-segment--dynamic",
        ).also { segment ->
            segment.style.setProperty("--symbol-count", build.symbolCount.toString())
            segment.style.setProperty("--srcx-build-color", build.color)
            segment.setAttribute("aria-label", "$name: ${build.symbolCount} symbols, $percent percent")
        }

    private fun symbolLegendItem(
        build: WorkspaceBuildDashboard,
        name: String,
        percent: String,
    ): HTMLElement =
        htmlElement("span").also { item ->
            item.appendChild(
                htmlElement("i", "srcx-dashboard__swatch").also { swatch ->
                    swatch.style.setProperty("--srcx-build-color", build.color)
                },
            )
            item.appendChild(htmlElement("b", text = name))
            item.appendChild(htmlElement("small", text = "${build.symbolCount} symbols / $percent%"))
        }

    private fun renderSeverity(
        root: HTMLDivElement,
        dashboard: WorkspaceDashboardShard,
    ) {
        val counts =
            FindingSeverity.entries.associateWith { severity ->
                dashboard.findings.count { item -> item.finding.severity == severity }
            }
        val facts =
            FindingSeverity.entries.map { severity -> severity.label to requireNotNull(counts[severity]) } +
                ("Total" to dashboard.findings.size)
        root.requiredHtmlElement("[data-docx-severity-grid]").renderDashboardFacts(facts)
    }

    private fun renderCoverage(
        root: HTMLDivElement,
        dashboard: WorkspaceDashboardShard,
    ) {
        root.requiredHtmlElement("[data-docx-coverage-grid]").renderDashboardFacts(
            listOf(
                "With symbols" to dashboard.coverage.projectsWithSymbols,
                "Source sets" to dashboard.coverage.sourceSetCount,
                "Packages" to dashboard.coverage.packageCount,
                "Project analyses" to dashboard.coverage.projectAnalysisCount,
            ),
        )
    }

    private fun renderHubs(
        root: HTMLDivElement,
        hubs: List<WorkspaceDashboardHub>,
    ) {
        root.requiredElement("[data-docx-hub-summary]").textContent =
            "${hubs.size} ${hubs.size.dashboardPlural("hub")} / expand"
        val bars = root.requiredHtmlElement("[data-docx-hub-bars]")
        bars.clearContent()
        val maximum = hubs.maxOfOrNull { item -> item.hub.dependentCount } ?: 0
        hubs
            .sortedWith(
                compareByDescending<WorkspaceDashboardHub> { item -> item.hub.dependentCount }
                    .thenBy { item -> item.id },
            ).forEach { item -> bars.appendChild(hubDetail(item, maximum)) }
        if (hubs.isEmpty()) {
            bars.appendChild(dashboardEmptyState("No production hubs found.", "Test-only hubs are excluded."))
        }
    }

    private fun hubDetail(
        item: WorkspaceDashboardHub,
        maximum: Int,
    ): HTMLElement =
        htmlElement("details", "srcx-disclosure srcx-dashboard__hub-detail").also { detail ->
            detail.appendChild(hubSummary(item, maximum))
            detail.appendChild(hubBody(item))
        }

    private fun hubSummary(
        item: WorkspaceDashboardHub,
        maximum: Int,
    ): HTMLElement =
        htmlElement("summary").also { summary ->
            summary.appendChild(
                htmlElement("span", "srcx-dashboard__hub-row").also { row ->
                    row.appendChild(htmlElement("span", "srcx-dashboard__hub-name", item.hub.name))
                    row.appendChild(
                        htmlElement("span", "srcx-dashboard__hub-track").also { track ->
                            track.appendChild(hubFill(item, maximum))
                        },
                    )
                    row.appendChild(
                        htmlElement(
                            "span",
                            "srcx-dashboard__hub-count",
                            item.hub.dependentCount.toString(),
                        ),
                    )
                },
            )
        }

    private fun hubFill(
        item: WorkspaceDashboardHub,
        maximum: Int,
    ): HTMLElement =
        htmlElement("span", "srcx-dashboard__hub-fill srcx-tone--primary").also { fill ->
            fill.style.setProperty("--hub-share", dashboardPercentage(item.hub.dependentCount, maximum))
        }

    private fun hubBody(item: WorkspaceDashboardHub): HTMLElement =
        htmlElement("div", "srcx-disclosure__body").also { body ->
            body.appendChild(dashboardStrongParagraph("Role:", item.hub.role))
            body.appendChild(
                dashboardStrongCodeParagraph(
                    "Source:",
                    dashboardSourceLocation(item.hub.filePath, item.hub.line),
                ),
            )
            body.appendChild(hubDependents(item))
        }

    private fun hubDependents(item: WorkspaceDashboardHub): HTMLElement =
        htmlElement("ul", "srcx-dashboard__note-list").also { list ->
            item.hub.dependents.forEach { dependent ->
                list.appendChild(
                    htmlElement("li").also { row ->
                        row.appendChild(htmlElement("strong", text = dependent.name))
                        row.appendChild(
                            htmlElement(
                                "small",
                                text = dashboardSourceLocation(dependent.filePath, dependent.line),
                            ),
                        )
                    },
                )
            }
        }
}
