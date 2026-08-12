package zone.clanker.gradle.srcx.report

import zone.clanker.gradle.srcx.model.ProjectSummary
import zone.clanker.gradle.srcx.model.WorkspaceReport

/** Renders workspace structure, ownership, build metrics, and typed model coverage. */
internal class WorkspaceStructureHtmlRenderer(
    private val resources: WorkspaceHtmlResourceRenderer,
) {
    fun render(report: WorkspaceReport): Map<String, String> {
        val scopes = buildScopes(report)
        return mapOf(
            "buildTableHtml" to renderBuildTable(scopes),
            "buildEdgesHtml" to renderBuildEdges(report),
            "symbolOwnershipHtml" to renderSymbolOwnership(scopes),
            "coverageHtml" to renderCoverage(report),
            "modelCoverageHtml" to renderModelCoverage(report),
            "provenanceHtml" to renderProvenance(report),
        )
    }

    private fun buildScopes(report: WorkspaceReport): List<BuildScope> =
        listOf(BuildScope(report.name, "Root build", ".", report.rootProjects)) +
            report.includedBuilds
                .sortedWith(compareBy({ it.name }, { it.relativePath }))
                .map { build -> BuildScope(build.name, "Included build", build.relativePath, build.projects) }

    private fun renderBuildTable(scopes: List<BuildScope>): String =
        buildString {
            appendLine("<div class=\"srcx-table-wrap\"><table class=\"srcx-table srcx-dashboard__build-table\">")
            appendLine("<caption>Build comparison</caption>")
            appendLine("<thead><tr>")
            appendLine("<th>Build / path</th><th>Projects</th><th>Symbols</th>")
            appendLine("<th>Dependencies</th><th>Findings</th><th>Source sets</th>")
            appendLine("</tr></thead><tbody>")
            scopes.forEach { scope -> appendLine(renderBuildRow(scope)) }
            appendLine("</tbody></table></div>")
        }

    private fun renderBuildRow(scope: BuildScope): String {
        val sourceSets = scope.sourceSetNames.ifEmpty { listOf("None") }.joinToString(", ")
        return buildString {
            append("<tr><td class=\"srcx-dashboard__build-scope\">")
            append("<strong>${scope.name.escapeWorkspaceHtml()}</strong>")
            append("<span>${scope.kind.escapeWorkspaceHtml()} / ")
            append("<code>${scope.relativePath.escapeWorkspaceHtml()}</code></span></td>")
            append("<td class=\"srcx-table__number\">${scope.projectCount}</td>")
            append("<td class=\"srcx-table__number\">${scope.symbolCount}</td>")
            append("<td class=\"srcx-table__number\">${scope.dependencyCount}</td>")
            append("<td class=\"srcx-table__number\">${scope.findingCount}</td>")
            append("<td>${sourceSets.escapeWorkspaceHtml()}</td></tr>")
        }
    }

    private fun renderBuildEdges(report: WorkspaceReport): String =
        buildString {
            if (report.buildEdges.isEmpty()) {
                append("<span class=\"srcx-dashboard__build-edge-empty\">None observed</span>")
            } else {
                report.buildEdges.sortedWith(compareBy({ it.from }, { it.to })).forEach { edge ->
                    append("<span class=\"srcx-tag srcx-tone--accent\">")
                    append(edge.from.escapeWorkspaceHtml())
                    append(" &rarr; ")
                    append(edge.to.escapeWorkspaceHtml())
                    appendLine("</span>")
                }
            }
        }

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
            owned.forEachIndexed { index, scope ->
                val percentage = ownershipPercentage(scope.symbolCount, total)
                val tone = TONES[index % TONES.size]
                append("<span class=\"srcx-dashboard__symbol-segment srcx-dashboard__symbol-segment--dynamic ")
                append("srcx-tone--$tone\" style=\"--symbol-count: ${scope.symbolCount}\" ")
                append("aria-label=\"${scope.name.escapeWorkspaceHtml()}: ${symbolCountLabel(scope.symbolCount)}, ")
                appendLine("$percentage percent\"></span>")
            }
            appendLine("</div><div class=\"srcx-dashboard__chart-legend\">")
            owned.forEachIndexed { index, scope ->
                val percentage = ownershipPercentage(scope.symbolCount, total)
                val tone = TONES[index % TONES.size]
                append("<span><i class=\"srcx-dashboard__swatch srcx-tone--$tone\"></i>")
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

    private fun renderModelCoverage(report: WorkspaceReport): String {
        val rows =
            listOf(
                Triple("name", 1, "Present"),
                Triple("rootProjects", report.rootProjects.size, "Typed project summaries"),
                Triple("includedBuilds", report.includedBuilds.size, "Typed build summaries"),
                Triple("buildEdges", report.buildEdges.size, "Directional relationships"),
                Triple("aggregateAnalysis", if (report.aggregateAnalysis == null) 0 else 1, "Optional aggregate"),
                Triple("entryPoints", report.entryPoints.size, "Classified entries"),
                Triple("interfaces", report.interfaces.size, "Candidate abstractions"),
                Triple("workspaceIndex", report.indexedSymbolCount, "Cumulative source index"),
                Triple("importantSymbols", report.importantSymbolCount, "Policy-selected declarations"),
            )
        return buildString {
            appendLine("<div class=\"srcx-table-wrap\"><table class=\"srcx-table\">")
            appendLine("<caption>WorkspaceReport field coverage</caption>")
            appendLine("<thead><tr><th>Field</th><th>Records</th><th>Provenance</th></tr></thead><tbody>")
            rows.forEach { (field, count, provenance) ->
                append("<tr><td><code>$field</code></td>")
                append("<td class=\"srcx-table__number\">$count</td>")
                appendLine("<td>$provenance</td></tr>")
            }
            appendLine("</tbody></table></div>")
        }
    }

    private fun renderProvenance(report: WorkspaceReport): String =
        buildString {
            appendLine("<div class=\"srcx-callout srcx-tone--secondary\">")
            appendLine("<strong class=\"srcx-callout__title\">Direct model provenance</strong>")
            appendLine("<p class=\"srcx-callout__body\">")
            append("Every metric, scope, finding, hub, edge, entry point, interface, index, ")
            appendLine("and important symbol for")
            append("<strong>${report.name.escapeWorkspaceHtml()}</strong> comes from the WorkspaceReport passed to ")
            appendLine("the renderer. Dynamic text is escaped and collections are ordered canonically before ")
            appendLine("presentation.</p></div>")
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
    ) {
        val projectCount: Int get() = projects.size
        val symbolCount: Int get() = projects.sumOf { it.symbols.size }
        val dependencyCount: Int get() = projects.sumOf { it.dependencies.size }
        val findingCount: Int get() = projects.sumOf { it.analysis?.findings?.size ?: 0 }
        val sourceSetNames: List<String>
            get() =
                projects
                    .flatMap { it.sourceSets }
                    .map { it.name.value }
                    .distinct()
                    .sorted()
    }

    private companion object {
        const val PERCENT_TENTHS = 1_000L
        const val PERCENT_DECIMAL_BASE = 10L
        val TONES = listOf("primary", "secondary", "accent", "tertiary", "error")
    }
}
