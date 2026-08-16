package zone.clanker.docx.web.atlas.dom.dashboard

import kotlinx.browser.document
import org.w3c.dom.Element
import org.w3c.dom.HTMLDivElement
import org.w3c.dom.HTMLElement
import zone.clanker.docx.web.atlas.dom.clearContent
import zone.clanker.docx.web.atlas.dom.htmlElement
import zone.clanker.docx.web.atlas.dom.requiredElement
import zone.clanker.docx.web.atlas.dom.requiredHtmlElement
import zone.clanker.report.model.AtlasCount
import zone.clanker.report.model.BuildKind
import zone.clanker.report.model.BuildSnapshot
import zone.clanker.report.model.FindingSeverity
import zone.clanker.report.model.WorkspaceBuildDashboard
import zone.clanker.report.model.WorkspaceDashboardShard
import zone.clanker.report.model.WorkspaceSummaryShard

internal class AtlasDomBuildsRenderer {
    fun render(
        root: HTMLDivElement,
        summary: WorkspaceSummaryShard,
        dashboard: WorkspaceDashboardShard,
    ) {
        val maximums = dashboard.buildMaximums()
        root.requiredElement("[data-docx-build-max-projects]").textContent = maximums.projects.toString()
        root.requiredElement("[data-docx-build-max-symbols]").textContent = maximums.symbols.toString()
        root.requiredElement("[data-docx-build-max-findings]").textContent = maximums.findings.toString()
        root.requiredElement("[data-docx-build-max-source-sets]").textContent = maximums.sourceSets.toString()

        val buildsById = summary.builds.associateBy(BuildSnapshot::id)
        val matrix = root.requiredHtmlElement("[data-docx-build-matrix]")
        matrix.clearContent()
        matrix.appendChild(buildMatrixHeader())
        dashboard.builds.orderedForDisplay(buildsById).forEach { build ->
            matrix.appendChild(
                buildRow(
                    snapshot = requireNotNull(buildsById[build.buildId]),
                    dashboard = build,
                    maximums = maximums,
                ),
            )
        }

        val routes = root.requiredHtmlElement("[data-docx-build-edge-routes]")
        routes.clearContent()
        summary.buildEdges.forEach { edge ->
            val source = requireNotNull(buildsById[edge.sourceBuildId])
            val target = requireNotNull(buildsById[edge.targetBuildId])
            routes.appendChild(buildRoute(source, target))
        }
        if (summary.buildEdges.isEmpty()) {
            routes.appendChild(
                dashboardEmptyState(
                    "No observed build dependencies.",
                    "Workspace membership does not imply an edge.",
                ),
            )
        }
    }

    private fun buildMatrixHeader(): HTMLElement =
        htmlElement("div", "srcx-dashboard__build-matrix-header").also { row ->
            row.setAttribute("role", "row")
            listOf("Build", "Projects", "Symbols", "Findings / severity mix", "Source-set records / mix")
                .forEach { label ->
                    row.appendChild(htmlElement("span", text = label).withAttribute("role", "columnheader"))
                }
        }

    private fun buildRow(
        snapshot: BuildSnapshot,
        dashboard: WorkspaceBuildDashboard,
        maximums: BuildMaximums,
    ): HTMLElement =
        htmlElement("div", "srcx-dashboard__build-matrix-row").also { row ->
            row.setAttribute("role", "row")
            row.setAttribute("data-srcx-build-row", snapshot.name)
            row.setAttribute("data-srcx-build-kind", snapshot.kind.name.lowercase())
            row.style.setProperty("--srcx-build-color", dashboard.color)
            row.appendChild(buildIdentity(snapshot))
            row.appendChild(simpleBuildMetric(snapshot.name, "projects", dashboard.projectCount, maximums.projects))
            row.appendChild(simpleBuildMetric(snapshot.name, "symbols", dashboard.symbolCount, maximums.symbols))
            row.appendChild(
                composedBuildMetric(
                    ComposedBuildMetric(
                        buildName = snapshot.name,
                        key = "findings",
                        noun = "findings",
                        counts = dashboard.orderedFindingCounts(),
                        maximum = maximums.findings,
                        tone = ::findingTone,
                    ),
                ),
            )
            row.appendChild(
                composedBuildMetric(
                    ComposedBuildMetric(
                        buildName = snapshot.name,
                        key = "source-set-records",
                        noun = "source-set records",
                        counts = dashboard.sourceSetCounts.map { count -> count.toBuildMetricCount() },
                        maximum = maximums.sourceSets,
                        tone = ::sourceSetTone,
                    ),
                ),
            )
        }

    private fun buildIdentity(snapshot: BuildSnapshot): HTMLElement =
        htmlElement("div", "srcx-dashboard__build-identity").also { identity ->
            identity.setAttribute("role", "rowheader")
            identity.appendChild(htmlElement("i").withAttribute("aria-hidden", "true"))
            identity.appendChild(
                htmlElement("span").also { copy ->
                    copy.appendChild(htmlElement("strong", text = snapshot.name))
                    copy.appendChild(
                        htmlElement("small").also { detail ->
                            detail.appendChild(document.createTextNode("${snapshot.kind.label} · "))
                            detail.appendChild(htmlElement("code", text = snapshot.relativePath))
                        },
                    )
                },
            )
        }

    private fun simpleBuildMetric(
        buildName: String,
        key: String,
        count: Int,
        maximum: Int,
    ): HTMLElement =
        buildMetricShell(key).also { metric ->
            metric.appendChild(metricValue(count, key))
            metric.appendChild(metricTrack(buildName, count, key, maximum))
        }

    private fun composedBuildMetric(spec: ComposedBuildMetric): HTMLElement {
        val total = spec.counts.sumOf(BuildMetricCount::count)
        return buildMetricShell(spec.key, composed = true).also { metric ->
            metric.appendChild(metricValue(total, spec.noun))
            val track = metricTrack(spec.buildName, total, spec.noun, spec.maximum, spec.counts)
            val fill = track.querySelector(".srcx-dashboard__build-metric-fill") as HTMLElement
            fill.classList.add("srcx-dashboard__build-metric-fill--stacked")
            spec.counts.forEach { count ->
                fill.appendChild(
                    htmlElement(
                        "i",
                        "srcx-dashboard__build-metric-segment ${spec.tone(count.label)}",
                    ).also { segment ->
                        segment.setAttribute("data-srcx-build-segment", count.label)
                        segment.style.setProperty("--srcx-segment-count", count.count.toString())
                    },
                )
            }
            metric.appendChild(track)
            metric.appendChild(
                htmlElement("span", "srcx-dashboard__build-composition").also { composition ->
                    composition.setAttribute("aria-hidden", "true")
                    spec.counts.forEach { count ->
                        composition.appendChild(
                            htmlElement("span").also { item ->
                                item.appendChild(
                                    htmlElement("i", "srcx-dashboard__swatch ${spec.tone(count.label)}"),
                                )
                                item.appendChild(document.createTextNode(count.label + " "))
                                item.appendChild(htmlElement("b", text = count.count.toString()))
                            },
                        )
                    }
                },
            )
        }
    }

    private fun buildMetricShell(
        key: String,
        composed: Boolean = false,
    ): HTMLElement =
        htmlElement(
            "div",
            "srcx-dashboard__build-metric${if (composed) " srcx-dashboard__build-metric--composed" else ""}",
        ).also { metric ->
            metric.setAttribute("role", "cell")
            metric.setAttribute("data-srcx-build-metric", key)
        }

    private fun metricValue(
        count: Int,
        noun: String,
    ): HTMLElement =
        htmlElement("span", "srcx-dashboard__build-metric-value").also { value ->
            value.appendChild(htmlElement("strong", text = count.toString()))
            value.appendChild(htmlElement("small", text = noun))
        }

    private fun metricTrack(
        buildName: String,
        count: Int,
        noun: String,
        maximum: Int,
        composition: List<BuildMetricCount> = emptyList(),
    ): HTMLElement =
        htmlElement("span", "srcx-dashboard__build-metric-track").also { track ->
            track.setAttribute("role", "img")
            val detail = composition.joinToString { item -> "${item.label}: ${item.count}" }
            track.setAttribute(
                "aria-label",
                "$buildName: $count $noun${if (detail.isEmpty()) "" else "; $detail"}; column maximum $maximum",
            )
            track.appendChild(
                htmlElement("span", "srcx-dashboard__build-metric-fill").also { fill ->
                    fill.style.setProperty("--srcx-build-share", dashboardPercentage(count, maximum))
                },
            )
        }

    private fun buildRoute(
        source: BuildSnapshot,
        target: BuildSnapshot,
    ): HTMLElement =
        htmlElement("article", "srcx-dashboard__build-edge-route").also { route ->
            route.setAttribute("data-srcx-build-route", "")
            route.setAttribute("role", "img")
            route.setAttribute("aria-label", "${source.name} depends on ${target.name}")
            route.appendChild(buildRouteNode("Consumer build", source.name))
            route.appendChild(
                htmlElement("span", "srcx-dashboard__build-edge-arrow").also { arrow ->
                    arrow.setAttribute("aria-hidden", "true")
                    arrow.appendChild(htmlElement("small", text = "depends on"))
                    arrow.appendChild(buildArrow())
                },
            )
            route.appendChild(buildRouteNode("Target build", target.name))
        }

    private fun buildRouteNode(
        label: String,
        name: String,
    ): HTMLElement =
        htmlElement("span", "srcx-dashboard__build-edge-node").also { node ->
            node.appendChild(htmlElement("small", text = label))
            node.appendChild(htmlElement("strong", text = name))
        }

    private fun buildArrow(): Element =
        svgElement("svg").also { svg ->
            svg.setAttribute("viewBox", "0 0 120 28")
            svg.setAttribute("focusable", "false")
            svg.appendChild(svgElement("path").withAttribute("d", "M4 14 H104"))
            svg.appendChild(svgElement("path").withAttribute("d", "M94 5 L114 14 L94 23 Z"))
        }
}

private data class BuildMaximums(
    val projects: Int,
    val symbols: Int,
    val findings: Int,
    val sourceSets: Int,
)

private data class ComposedBuildMetric(
    val buildName: String,
    val key: String,
    val noun: String,
    val counts: List<BuildMetricCount>,
    val maximum: Int,
    val tone: (String) -> String,
)

private data class BuildMetricCount(
    val label: String,
    val count: Int,
)

private fun WorkspaceDashboardShard.buildMaximums(): BuildMaximums =
    BuildMaximums(
        projects = builds.maxOfOrNull(WorkspaceBuildDashboard::projectCount) ?: 0,
        symbols = builds.maxOfOrNull(WorkspaceBuildDashboard::symbolCount) ?: 0,
        findings = builds.maxOfOrNull { build -> build.findingCounts.sumOf(AtlasCount::count) } ?: 0,
        sourceSets = builds.maxOfOrNull { build -> build.sourceSetCounts.sumOf(AtlasCount::count) } ?: 0,
    )

private fun List<WorkspaceBuildDashboard>.orderedForDisplay(
    buildsById: Map<String, BuildSnapshot>,
): List<WorkspaceBuildDashboard> =
    sortedWith(
        compareBy<WorkspaceBuildDashboard>(
            { build -> if (requireNotNull(buildsById[build.buildId]).kind == BuildKind.ROOT) 0 else 1 },
            { build -> requireNotNull(buildsById[build.buildId]).name },
            { build -> requireNotNull(buildsById[build.buildId]).relativePath },
        ),
    )

private fun WorkspaceBuildDashboard.orderedFindingCounts(): List<BuildMetricCount> {
    val countsByKey = findingCounts.associateBy(AtlasCount::key)
    return FindingSeverity.entries.map { severity ->
        BuildMetricCount(
            label = severity.label,
            count = countsByKey[severity.name]?.count ?: 0,
        )
    }
}

private fun AtlasCount.toBuildMetricCount(): BuildMetricCount = BuildMetricCount(label, count)

private fun HTMLElement.withAttribute(
    name: String,
    value: String,
): HTMLElement = also { element -> element.setAttribute(name, value) }

private fun Element.withAttribute(
    name: String,
    value: String,
): Element = also { element -> element.setAttribute(name, value) }

private fun svgElement(tag: String): Element = document.createElementNS(SVG_NAMESPACE, tag)

private fun findingTone(label: String): String =
    when (label.uppercase()) {
        "FORBIDDEN" -> "srcx-tone--error"
        "WARNING" -> "srcx-tone--tertiary"
        else -> "srcx-tone--accent"
    }

private fun sourceSetTone(label: String): String =
    sourceSetTones[(label.hashCode() and Int.MAX_VALUE) % sourceSetTones.size]

private val sourceSetTones =
    listOf(
        "srcx-tone--primary",
        "srcx-tone--secondary",
        "srcx-tone--tertiary",
        "srcx-tone--error",
        "srcx-tone--accent",
    )

private const val SVG_NAMESPACE = "http://www.w3.org/2000/svg"
