package zone.clanker.gradle.srcx.report

import zone.clanker.gradle.srcx.model.FindingSeverity
import zone.clanker.gradle.srcx.model.ProjectSummary
import zone.clanker.gradle.srcx.model.WorkspaceReport

/** Renders the build atlas, symbol ownership, and source coverage. */
@Suppress("LargeClass", "TooManyFunctions")
internal class WorkspaceStructureHtmlRenderer(
    private val resources: WorkspaceHtmlResourceRenderer,
) {
    fun render(
        report: WorkspaceReport,
        @Suppress("UNUSED_PARAMETER")
        findingEvidence: WorkspaceArchitectureFindingEvidenceMapRenderer,
    ): Map<String, String> {
        val scopes = buildScopes(report)
        return mapOf(
            "buildsHtml" to renderBuilds(report, scopes),
            "symbolOwnershipHtml" to renderSymbolOwnership(scopes),
            "coverageHtml" to renderCoverage(report),
        )
    }

    private fun buildScopes(report: WorkspaceReport): List<BuildScope> =
        listOf(BuildScope(report.name, "Root build", ".", report.rootProjects, isRoot = true)) +
            report.includedBuilds
                .sortedWith(compareBy({ it.name }, { it.relativePath }))
                .map { build ->
                    BuildScope(build.name, "Included build", build.relativePath, build.projects, isRoot = false)
                }

    private fun renderBuilds(
        report: WorkspaceReport,
        scopes: List<BuildScope>,
    ): String =
        buildString {
            appendLine("<div class=\"srcx-dashboard__build-atlas\" data-srcx-build-atlas>")
            appendLine(renderBuildComparison(scopes))
            appendLine(renderBuildEdges(report))
            appendLine("</div>")
        }

    private fun renderBuildComparison(scopes: List<BuildScope>): String {
        val sourceSetNames = scopes.flatMap { it.sourceSetCounts.keys }.distinct().sorted()
        val maximums =
            BuildMetricMaximums(
                projects = scopes.maxOfOrNull { it.projectCount } ?: 0,
                symbols = scopes.maxOfOrNull { it.symbolCount } ?: 0,
                findings = scopes.maxOfOrNull { it.findingCount } ?: 0,
                sourceSetRecords = scopes.maxOfOrNull { it.sourceSetCount } ?: 0,
            )
        return buildString {
            appendLine(
                "<figure class=\"srcx-dashboard__build-comparison\" data-srcx-build-comparison " +
                    "aria-labelledby=\"build-comparison-title\">",
            )
            appendLine("<figcaption class=\"srcx-dashboard__build-comparison-head\">")
            appendLine("<div><span>Unified build matrix</span>")
            appendLine("<h3 id=\"build-comparison-title\">Build comparison</h3>")
            appendLine(
                "<p id=\"build-comparison-scale\">Each bar compares builds only within its metric column; the " +
                    "largest value fills that column, and the Column maximums cards print those exact largest " +
                    "values. One source-set record is one analyzed Gradle project/source-set summary, such as " +
                    "<code>:app / main</code>; it is not a file, dependency, or relationship record. Every printed " +
                    "value is exact. Root and included builds are workspace members, not dependency edges.</p></div>",
            )
            appendLine(renderBuildScale(maximums))
            appendLine("</figcaption>")
            appendLine("<div class=\"srcx-dashboard__build-matrix-wrap\">")
            appendLine(
                "<div class=\"srcx-dashboard__build-matrix\" role=\"table\" " +
                    "aria-label=\"Build metrics comparison\" aria-describedby=\"build-comparison-scale\">",
            )
            appendLine("<div class=\"srcx-dashboard__build-matrix-header\" role=\"row\">")
            appendLine("<span role=\"columnheader\">Build</span>")
            appendLine("<span role=\"columnheader\">Projects</span>")
            appendLine("<span role=\"columnheader\">Symbols</span>")
            appendLine("<span role=\"columnheader\">Findings / severity mix</span>")
            appendLine("<span role=\"columnheader\">Source-set records / mix</span>")
            appendLine("</div>")
            scopes.forEach { scope ->
                appendLine(renderBuildComparisonRow(scope, maximums, sourceSetNames))
            }
            appendLine("</div></div></figure>")
        }
    }

    private fun renderBuildScale(maximums: BuildMetricMaximums): String =
        buildString {
            appendLine("<div class=\"srcx-dashboard__build-scale-block\"><span>Column maximums</span>")
            appendLine("<dl class=\"srcx-dashboard__build-scale\" aria-label=\"Largest value in each metric column\">")
            appendLine("<div><dt>Projects</dt><dd>${maximums.projects}</dd></div>")
            appendLine("<div><dt>Symbols</dt><dd>${maximums.symbols}</dd></div>")
            appendLine("<div><dt>Findings</dt><dd>${maximums.findings}</dd></div>")
            appendLine("<div><dt>Source-set records</dt><dd>${maximums.sourceSetRecords}</dd></div>")
            appendLine("</dl></div>")
        }

    private fun renderBuildComparisonRow(
        scope: BuildScope,
        maximums: BuildMetricMaximums,
        sourceSetNames: List<String>,
    ): String =
        buildString {
            val escapedName = scope.name.escapeWorkspaceHtml()
            val kind = if (scope.isRoot) "root" else "included"
            append("<div class=\"srcx-dashboard__build-matrix-row\" role=\"row\" data-srcx-build-row=\"")
            append(escapedName)
            append("\" data-srcx-build-kind=\"$kind\" style=\"--srcx-build-color: ")
            append(workspaceBuildColor(scope.name))
            appendLine("\">")
            appendLine("<div class=\"srcx-dashboard__build-identity\" role=\"rowheader\">")
            appendLine("<i aria-hidden=\"true\"></i><span><strong>$escapedName</strong>")
            append("<small>${scope.kind} <span aria-hidden=\"true\">&middot;</span> <code>")
            append(scope.relativePath.escapeWorkspaceHtml())
            appendLine("</code></small></span></div>")
            appendLine(
                renderBuildMetricCell(
                    BuildMetricCell(
                        buildName = scope.name,
                        metric = "projects",
                        value = scope.projectCount,
                        maximum = maximums.projects,
                        unit = MetricUnit("project", "projects"),
                    ),
                ),
            )
            appendLine(
                renderBuildMetricCell(
                    BuildMetricCell(
                        buildName = scope.name,
                        metric = "symbols",
                        value = scope.symbolCount,
                        maximum = maximums.symbols,
                        unit = MetricUnit("symbol", "symbols"),
                    ),
                ),
            )
            appendLine(renderFindingMetricCell(scope, maximums.findings))
            appendLine(renderSourceSetMetricCell(scope, maximums.sourceSetRecords, sourceSetNames))
            appendLine("</div>")
        }

    private fun renderBuildMetricCell(cell: BuildMetricCell): String {
        val unit = cell.unit.label(cell.value)
        return buildString {
            append("<div class=\"srcx-dashboard__build-metric\" role=\"cell\" data-srcx-build-metric=\"")
            appendLine("${cell.metric}\">")
            append("<span class=\"srcx-dashboard__build-metric-value\"><strong>${cell.value}</strong>")
            appendLine("<small>$unit</small></span>")
            append("<span class=\"srcx-dashboard__build-metric-track\" role=\"img\" aria-label=\"")
            append(cell.buildName.escapeWorkspaceHtml())
            append(": ${cell.value} $unit; column maximum ${cell.maximum}\">")
            append("<span class=\"srcx-dashboard__build-metric-fill\" ")
            appendLine("style=\"--srcx-build-share: ${percentage(cell.value, cell.maximum)}%\"></span></span>")
            appendLine("</div>")
        }
    }

    private fun renderFindingMetricCell(
        scope: BuildScope,
        maximum: Int,
    ): String {
        val segments =
            FINDING_SEGMENTS.map { segment ->
                CompositionSegment(
                    label = segment.label,
                    tone = segment.tone,
                    count = scope.findingCounts.getValue(segment.severity),
                )
            }
        return renderComposedMetricCell(
            cell =
                BuildMetricCell(
                    buildName = scope.name,
                    metric = "findings",
                    value = scope.findingCount,
                    maximum = maximum,
                    unit = MetricUnit("finding", "findings"),
                ),
            segments = segments,
            emptyLabel = "No findings",
        )
    }

    private fun renderSourceSetMetricCell(
        scope: BuildScope,
        maximum: Int,
        sourceSetNames: List<String>,
    ): String {
        val segments =
            sourceSetNames.mapIndexedNotNull { index, name ->
                val count = scope.sourceSetCounts[name] ?: 0
                if (count == 0) null else CompositionSegment(name, TONES[index % TONES.size], count = count)
            }
        return renderComposedMetricCell(
            cell =
                BuildMetricCell(
                    buildName = scope.name,
                    metric = "source-set-records",
                    value = scope.sourceSetCount,
                    maximum = maximum,
                    unit = MetricUnit("source-set record", "source-set records"),
                ),
            segments = segments,
            emptyLabel = "No source-set records",
        )
    }

    private fun renderComposedMetricCell(
        cell: BuildMetricCell,
        segments: List<CompositionSegment>,
        emptyLabel: String,
    ): String {
        val unit = cell.unit.label(cell.value)
        val composition =
            segments
                .joinToString(", ") { segment -> "${segment.label}: ${segment.count}" }
                .ifEmpty { emptyLabel }
        return buildString {
            append("<div class=\"srcx-dashboard__build-metric srcx-dashboard__build-metric--composed\" ")
            appendLine("role=\"cell\" data-srcx-build-metric=\"${cell.metric}\">")
            append("<span class=\"srcx-dashboard__build-metric-value\"><strong>${cell.value}</strong>")
            appendLine("<small>$unit</small></span>")
            append("<span class=\"srcx-dashboard__build-metric-track\" role=\"img\" aria-label=\"")
            append(cell.buildName.escapeWorkspaceHtml())
            append(": ${cell.value} $unit; ")
            append(composition.escapeWorkspaceHtml())
            append("; column maximum ${cell.maximum}\">")
            append("<span class=\"srcx-dashboard__build-metric-fill srcx-dashboard__build-metric-fill--stacked\" ")
            appendLine("style=\"--srcx-build-share: ${percentage(cell.value, cell.maximum)}%\">")
            segments.forEach { segment ->
                append("<i class=\"srcx-dashboard__build-metric-segment srcx-tone--${segment.tone}\" ")
                append("data-srcx-build-segment=\"${segment.label.escapeWorkspaceHtml()}\" ")
                appendLine("style=\"--srcx-segment-count: ${segment.count}\"></i>")
            }
            appendLine("</span></span>")
            if (segments.isEmpty()) {
                appendLine("<small class=\"srcx-dashboard__build-composition-empty\">$emptyLabel</small>")
            } else {
                appendLine("<span class=\"srcx-dashboard__build-composition\" aria-hidden=\"true\">")
                segments.forEach { segment ->
                    append("<span><i class=\"srcx-dashboard__swatch srcx-tone--${segment.tone}\"></i>")
                    append(segment.label.escapeWorkspaceHtml())
                    appendLine(" <b>${segment.count}</b></span>")
                }
                appendLine("</span>")
            }
            appendLine("</div>")
        }
    }

    private fun renderBuildEdges(report: WorkspaceReport): String =
        buildString {
            appendLine("<section class=\"srcx-dashboard__build-edge-panel\" aria-labelledby=\"build-edge-title\">")
            appendLine("<header class=\"srcx-dashboard__build-atlas-head\">")
            appendLine("<span>Directed evidence</span><h3 id=\"build-edge-title\">Build dependencies</h3>")
            appendLine(
                "<p>Arrow direction: consumer build &rarr; active build it depends on. Inclusion above records " +
                    "membership only and does not create an arrow.</p>",
            )
            appendLine("</header>")
            if (report.buildEdges.isEmpty()) {
                appendLine(renderEmptyBuildEdges())
            } else {
                appendLine("<div class=\"srcx-dashboard__build-edge-routes\">")
                report.buildEdges.sortedWith(compareBy({ it.from }, { it.to })).forEach { edge ->
                    appendLine(renderBuildEdgeRoute(edge.from, edge.to))
                }
                appendLine("</div>")
            }
            appendLine("</section>")
        }

    private fun renderBuildEdgeRoute(
        consumer: String,
        target: String,
    ): String =
        buildString {
            val escapedConsumer = consumer.escapeWorkspaceHtml()
            val escapedTarget = target.escapeWorkspaceHtml()
            append("<article class=\"srcx-dashboard__build-edge-route\" data-srcx-build-route role=\"img\" ")
            appendLine("aria-label=\"$escapedConsumer depends on $escapedTarget\">")
            appendLine(
                "<span class=\"srcx-dashboard__build-edge-node\"><small>Consumer build</small>" +
                    "<strong>$escapedConsumer</strong></span>",
            )
            append("<span class=\"srcx-dashboard__build-edge-arrow\" aria-hidden=\"true\">")
            appendLine("<small>depends on</small>")
            appendLine("<svg viewBox=\"0 0 120 28\" focusable=\"false\">")
            appendLine("<path d=\"M4 14 H104\"></path><path d=\"M94 5 L114 14 L94 23 Z\"></path>")
            appendLine("</svg></span>")
            appendLine(
                "<span class=\"srcx-dashboard__build-edge-node\"><small>Target build</small>" +
                    "<strong>$escapedTarget</strong></span>",
            )
            appendLine("</article>")
        }

    private fun renderEmptyBuildEdges(): String =
        """
        <article class="srcx-dashboard__build-edge-evidence" data-srcx-build-edge-empty>
            <svg viewBox="0 0 180 92" aria-hidden="true" focusable="false">
                <rect x="8" y="18" width="58" height="56"></rect>
                <rect x="114" y="18" width="58" height="56"></rect>
                <circle cx="90" cy="46" r="24"></circle>
                <text x="90" y="54">0</text>
            </svg>
            <div>
                <strong>No observed active-build dependency records</strong>
                <p>SRCX records an edge only when resolved non-import source evidence crosses build scopes or an
                    artifact dependency matches another active build.</p>
                <p>Absence does not prove independence.</p>
            </div>
        </article>
        """.trimIndent()

    private fun renderSymbolOwnership(scopes: List<BuildScope>): String {
        val owned =
            scopes
                .filter { it.symbolCount > 0 }
                .sortedWith(compareByDescending<BuildScope> { it.symbolCount }.thenBy { it.name })
        if (owned.isEmpty()) return renderEmpty("No symbols", "No build owns an extracted symbol yet.", "0")
        val total = owned.sumOf { it.symbolCount }
        return buildString {
            appendLine(
                "<div class=\"srcx-dashboard__symbol-bar\" role=\"img\" " +
                    "aria-label=\"Symbol ownership by build\">",
            )
            owned.forEach { scope ->
                val percentage = ownershipPercentage(scope.symbolCount, total)
                val buildColor = workspaceBuildColor(scope.name)
                append("<span class=\"srcx-dashboard__symbol-segment srcx-dashboard__symbol-segment--dynamic ")
                append("\" style=\"--symbol-count: ${scope.symbolCount}; --srcx-build-color: $buildColor\" ")
                append("aria-label=\"${scope.name.escapeWorkspaceHtml()}: ${symbolCountLabel(scope.symbolCount)}, ")
                appendLine("$percentage percent\"></span>")
            }
            appendLine("</div><div class=\"srcx-dashboard__chart-legend\">")
            owned.forEach { scope ->
                val percentage = ownershipPercentage(scope.symbolCount, total)
                val buildColor = workspaceBuildColor(scope.name)
                append("<span><i class=\"srcx-dashboard__swatch\" ")
                append("style=\"--srcx-build-color: $buildColor\"></i>")
                append("<b>${scope.name.escapeWorkspaceHtml()}</b>")
                appendLine("<small>${symbolCountLabel(scope.symbolCount)} / $percentage%</small></span>")
            }
            appendLine("</div>")
        }
    }

    private fun ownershipPercentage(
        count: Int,
        total: Int,
    ): String {
        val tenths = (count.toLong() * PERCENT_TENTHS + total / 2) / total
        return "${tenths / PERCENT_DECIMAL_BASE}.${tenths % PERCENT_DECIMAL_BASE}"
    }

    private fun symbolCountLabel(count: Int): String = "$count ${if (count == 1) "symbol" else "symbols"}"

    private fun renderCoverage(report: WorkspaceReport): String {
        val packages =
            report.allProjects
                .flatMap { it.symbols }
                .map { it.packageName.value }
                .distinct()
                .size
        val analyzedProjects = report.allProjects.count { it.analysis != null }
        val projectsWithSymbols = report.allProjects.count { it.symbols.isNotEmpty() }
        return buildString {
            appendLine("<dl class=\"srcx-dashboard__coverage-grid\">")
            appendLine("<div><dt>With symbols</dt><dd>$projectsWithSymbols</dd></div>")
            appendLine("<div><dt>Source sets</dt><dd>${report.sourceSetCount}</dd></div>")
            appendLine("<div><dt>Packages</dt><dd>$packages</dd></div>")
            appendLine("<div><dt>Project analyses</dt><dd>$analyzedProjects</dd></div>")
            appendLine("</dl>")
        }
    }

    private fun renderEmpty(
        title: String,
        body: String,
        mark: String,
    ): String =
        resources.component(
            "empty-state",
            mapOf("title" to title, "body" to body, "mark" to mark, "tone" to "neutral", "classes" to ""),
        )

    private data class BuildScope(
        val name: String,
        val kind: String,
        val relativePath: String,
        val projects: List<ProjectSummary>,
        val isRoot: Boolean,
    ) {
        val projectCount: Int get() = projects.size
        val symbolCount: Int get() = projects.sumOf { it.symbols.size }
        val sourceSetCount: Int get() = projects.sumOf { it.sourceSets.size }
        val findingCount: Int get() = findingCounts.values.sum()
        val findingCounts: Map<FindingSeverity, Int>
            get() = FindingSeverity.entries.associateWith(::findingCount)
        val sourceSetCounts: Map<String, Int>
            get() =
                projects
                    .flatMap { it.sourceSets }
                    .groupingBy { it.name.value }
                    .eachCount()
                    .toSortedMap()

        private fun findingCount(severity: FindingSeverity): Int =
            projects.sumOf { project -> project.analysis?.findings?.count { it.severity == severity } ?: 0 }
    }

    private data class BuildMetricMaximums(
        val projects: Int,
        val symbols: Int,
        val findings: Int,
        val sourceSetRecords: Int,
    )

    private data class BuildMetricCell(
        val buildName: String,
        val metric: String,
        val value: Int,
        val maximum: Int,
        val unit: MetricUnit,
    )

    private data class MetricUnit(
        val singular: String,
        val plural: String,
    ) {
        fun label(value: Int): String = if (value == 1) singular else plural
    }

    private data class FindingSegment(
        val severity: FindingSeverity,
        val label: String,
        val tone: String,
    )

    private data class CompositionSegment(
        val label: String,
        val tone: String,
        val count: Int,
    )

    private fun percentage(
        value: Int,
        maximum: Int,
    ): String {
        if (maximum == 0) return "0"
        val tenths = (value.toLong() * PERCENT_TENTHS + maximum / 2) / maximum
        return "${tenths / PERCENT_DECIMAL_BASE}.${tenths % PERCENT_DECIMAL_BASE}"
    }

    private companion object {
        const val PERCENT_TENTHS = 1_000L
        const val PERCENT_DECIMAL_BASE = 10L
        val TONES = listOf("primary", "secondary", "accent", "tertiary", "error")
        val FINDING_SEGMENTS =
            listOf(
                FindingSegment(FindingSeverity.FORBIDDEN, "Forbidden", "error"),
                FindingSegment(FindingSeverity.WARNING, "Warning", "tertiary"),
                FindingSegment(FindingSeverity.INFO, "Info", "accent"),
            )
    }
}
