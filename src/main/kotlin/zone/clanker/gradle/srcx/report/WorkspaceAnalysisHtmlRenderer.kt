package zone.clanker.gradle.srcx.report

import zone.clanker.gradle.srcx.model.Finding
import zone.clanker.gradle.srcx.model.FindingSeverity
import zone.clanker.gradle.srcx.model.HubClass
import zone.clanker.gradle.srcx.model.ProjectSummary
import zone.clanker.gradle.srcx.model.WorkspaceReport

/** Renders finding severity, scoped findings, and production dependency hubs. */
internal class WorkspaceAnalysisHtmlRenderer(
    private val resources: WorkspaceHtmlResourceRenderer,
) {
    fun render(report: WorkspaceReport): Map<String, String> =
        mapOf(
            "severityHtml" to renderSeverity(report),
            "hubBarsHtml" to renderProductionHubs(report),
            "productionHubCount" to productionHubSummary(report),
            "findingsHtml" to renderFindings(report),
        )

    private fun renderSeverity(report: WorkspaceReport): String =
        buildString {
            val findings = projectFindings(report)
            appendLine("<dl class=\"srcx-dashboard__coverage-grid\">")
            FindingSeverity.entries.forEach { severity ->
                val count = findings.count { finding -> finding.severity == severity }
                append("<div><dt>")
                append(severity.name.lowercase().replaceFirstChar { it.uppercase() })
                append("</dt><dd>")
                append(count)
                appendLine("</dd></div>")
            }
            appendLine("<div><dt>Total</dt><dd>${findings.size}</dd></div>")
            appendLine("</dl>")
        }

    private fun projectFindings(report: WorkspaceReport): List<Finding> =
        report.allProjects.flatMap { project -> project.analysis?.findings.orEmpty() }

    private fun renderProductionHubs(report: WorkspaceReport): String {
        val hubs = productionHubs(report)
        if (hubs.isEmpty()) {
            return renderEmpty("No production hubs", "No production dependency hubs were observed.", "0")
        }
        val maximum = hubs.maxOf { it.dependentCount }.coerceAtLeast(1)
        return hubs.joinToString("\n") { hub -> renderHub(hub, hub.dependentCount * PERCENT / maximum) }
    }

    private fun productionHubSummary(report: WorkspaceReport): String {
        val count = productionHubs(report).size
        return "$count ${if (count == 1) "hub" else "hubs"}"
    }

    private fun productionHubs(report: WorkspaceReport): List<HubClass> =
        report.productionHubs
            .distinctBy { hub -> Triple(hub.name, hub.filePath, hub.line) }
            .sortedWith(compareByDescending<HubClass> { it.dependentCount }.thenBy { it.name })

    private fun renderHub(
        hub: HubClass,
        share: Int,
    ): String {
        val role = hub.role.ifBlank { "unclassified" }
        val location = if (hub.filePath.isBlank()) "Location unavailable" else "${hub.filePath}:${hub.line}"
        val dependents = hub.dependents.sortedWith(compareBy({ it.name }, { it.filePath }, { it.line }))
        return buildString {
            appendLine("<details class=\"srcx-disclosure srcx-dashboard__hub-detail\">")
            appendLine("<summary>")
            appendLine("<span class=\"srcx-dashboard__hub-row\">")
            append("<span class=\"srcx-dashboard__hub-name\">")
            append(hub.name.escapeWorkspaceHtml())
            appendLine("</span>")
            appendLine("<span class=\"srcx-dashboard__hub-track\">")
            append("<span class=\"srcx-dashboard__hub-fill srcx-tone--primary\"")
            appendLine(" style=\"--hub-share: $share%\"></span>")
            appendLine("</span><span class=\"srcx-dashboard__hub-count\">${hub.dependentCount}</span></span></summary>")
            appendLine("<div class=\"srcx-disclosure__body\">")
            append("<p><strong>Role:</strong> ${role.escapeWorkspaceHtml()}</p>")
            appendLine("<p><strong>Source:</strong> <code>${location.escapeWorkspaceHtml()}</code></p>")
            if (dependents.isEmpty()) {
                appendLine("<p class=\"srcx-muted\">No dependent details were supplied.</p>")
            } else {
                appendLine("<ul class=\"srcx-dashboard__note-list\">")
                dependents.forEach { dependent ->
                    append("<li><strong>${dependent.name.escapeWorkspaceHtml()}</strong><small>")
                    append(dependent.filePath.escapeWorkspaceHtml())
                    append(":${dependent.line}</small></li>")
                }
                appendLine("</ul>")
            }
            appendLine("</div></details>")
        }
    }

    private fun findingScopes(report: WorkspaceReport): List<FindingScope> {
        val rootScopes =
            report.rootProjects.map { project -> FindingScope(report.name, project) }
        val includedScopes =
            report.includedBuilds
                .sortedWith(compareBy({ it.name }, { it.relativePath }))
                .flatMap { build -> build.projects.map { project -> FindingScope(build.name, project) } }
        return (rootScopes + includedScopes)
            .filter { it.findings.isNotEmpty() }
            .sortedWith(compareBy({ it.buildName }, { it.projectPath }))
    }

    private fun renderFindings(report: WorkspaceReport): String {
        val scopes = findingScopes(report)
        if (scopes.isEmpty()) {
            return renderEmpty("No scoped findings", "No project analysis supplied a finding.", "0")
        }
        return "<div class=\"srcx-dashboard__finding-list\">" +
            scopes.joinToString("\n") { scope -> renderFindingScope(scope) } +
            "</div>"
    }

    private fun renderFindingScope(scope: FindingScope): String {
        val sourceSets = scope.sourceSets.ifEmpty { listOf("Not supplied") }.joinToString(", ")
        val findings = scope.findings.sortedWith(compareBy({ it.severity }, { it.message }, { it.suggestion }))
        return buildString {
            appendLine("<details class=\"srcx-disclosure srcx-dashboard__finding-scope\">")
            appendLine("<summary class=\"srcx-dashboard__scope-head\">")
            append("<span class=\"srcx-dashboard__scope-title\"><small>")
            append(scope.buildName.escapeWorkspaceHtml())
            append("</small><strong>")
            append(scope.projectPath.escapeWorkspaceHtml())
            appendLine("</strong></span>")
            append("<span class=\"srcx-dashboard__scope-meta\"><strong>${findings.size} ")
            append(if (findings.size == 1) "finding" else "findings")
            append("</strong><small>")
            append(sourceSets.escapeWorkspaceHtml())
            appendLine("</small></span></summary>")
            appendLine("<div class=\"srcx-disclosure__body srcx-dashboard__finding-rows\">")
            findings.forEach { finding -> appendLine(renderFinding(finding)) }
            appendLine("</div></details>")
        }
    }

    private fun renderFinding(finding: Finding): String {
        val severity =
            finding.severity.name
                .lowercase()
                .replaceFirstChar { it.uppercase() }
        val tone =
            when (finding.severity) {
                FindingSeverity.FORBIDDEN -> "error"
                FindingSeverity.WARNING -> "warning"
                FindingSeverity.INFO -> "info"
            }
        return buildString {
            appendLine("<article class=\"srcx-dashboard__finding-row srcx-tone--$tone\">")
            append("<span class=\"srcx-dashboard__finding-severity\">")
            append(severity)
            appendLine("</span><div>")
            append("<strong>${finding.message.escapeWorkspaceHtml()}</strong>")
            append("<p>")
            append(finding.suggestion.escapeWorkspaceHtml())
            appendLine("</p></div></article>")
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

    private data class FindingScope(
        val buildName: String,
        val project: ProjectSummary,
    ) {
        val projectPath: String get() = project.projectPath.value
        val sourceSets: List<String>
            get() =
                project.sourceSets
                    .map { it.name.value }
                    .distinct()
                    .sorted()
        val findings: List<Finding> get() = project.analysis?.findings.orEmpty()
    }

    private companion object {
        const val PERCENT = 100
    }
}
