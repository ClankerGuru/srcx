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
    fun render(
        report: WorkspaceReport,
        findingEvidence: WorkspaceArchitectureFindingEvidenceMapRenderer,
    ): Map<String, String> =
        mapOf(
            "severityHtml" to renderSeverity(report),
            "hubBarsHtml" to renderProductionHubs(report),
            "productionHubCount" to productionHubSummary(report),
            "findingsHtml" to renderFindings(report, findingEvidence),
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

    private fun findingScopes(report: WorkspaceReport): List<FindingScopeRenderer> {
        val rootScopes =
            report.rootProjects.map { project -> FindingScopeRenderer(report.name, project) }
        val includedScopes =
            report.includedBuilds
                .sortedWith(compareBy({ it.name }, { it.relativePath }))
                .flatMap { build -> build.projects.map { project -> FindingScopeRenderer(build.name, project) } }
        return (rootScopes + includedScopes)
            .filter { it.findings.isNotEmpty() }
            .sortedWith(compareBy({ it.buildName }, { it.projectPath }))
    }

    private fun renderFindings(
        report: WorkspaceReport,
        findingEvidence: WorkspaceArchitectureFindingEvidenceMapRenderer,
    ): String {
        val scopes = findingScopes(report)
        if (scopes.isEmpty()) {
            return renderEmpty("No scoped findings", "No project analysis supplied a finding.", "0")
        }
        return buildString {
            appendLine(FindingControlsHtmlRenderer(scopes).render())
            appendLine("<div id=\"srcx-finding-list\" class=\"srcx-dashboard__finding-list\">")
            scopes.forEach { scope -> appendLine(renderFindingScope(scope, findingEvidence)) }
            appendLine("</div>")
        }
    }

    private fun renderFindingScope(
        scope: FindingScopeRenderer,
        findingEvidence: WorkspaceArchitectureFindingEvidenceMapRenderer,
    ): String {
        val sourceSets = scope.sourceSets.ifEmpty { listOf("Not supplied") }.joinToString(", ")
        val findings = scope.findings.sortedWith(compareBy({ it.severity }, { it.message }, { it.suggestion }))
        return buildString {
            append("<details class=\"srcx-disclosure srcx-dashboard__finding-scope\" data-srcx-finding-scope ")
            append("data-srcx-finding-build=\"${scope.buildName.escapeWorkspaceHtml()}\" ")
            appendLine("data-srcx-finding-project=\"${scope.projectPath.escapeWorkspaceHtml()}\">")
            appendLine("<summary class=\"srcx-dashboard__scope-head\">")
            append("<span class=\"srcx-dashboard__scope-title\"><small>")
            append(scope.buildName.escapeWorkspaceHtml())
            append("</small><strong>")
            append(workspaceProjectDisplayName(scope.buildName, scope.projectPath).escapeWorkspaceHtml())
            appendLine("</strong></span>")
            append("<span class=\"srcx-dashboard__scope-meta\"><strong>${findings.size} ")
            append(if (findings.size == 1) "finding" else "findings")
            append("</strong><small>")
            append(sourceSets.escapeWorkspaceHtml())
            appendLine("</small></span></summary>")
            appendLine("<div class=\"srcx-disclosure__body srcx-dashboard__finding-rows\">")
            findings.forEach { finding -> appendLine(renderFinding(scope, finding, findingEvidence)) }
            appendLine("</div></details>")
        }
    }

    private fun renderFinding(
        scope: FindingScopeRenderer,
        finding: Finding,
        findingEvidence: WorkspaceArchitectureFindingEvidenceMapRenderer,
    ): String {
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
        val evidence =
            findingEvidence[WorkspaceArchitectureFindingKeyRenderer(scope.buildName, scope.projectPath, finding)]
        return buildString {
            append("<article class=\"srcx-dashboard__finding-row srcx-tone--$tone\" data-srcx-finding-row ")
            append("data-srcx-finding-severity=\"${finding.severity.name}\" ")
            append("data-srcx-finding-build=\"${scope.buildName.escapeWorkspaceHtml()}\" ")
            append("data-srcx-finding-project=\"${scope.projectPath.escapeWorkspaceHtml()}\" ")
            evidence?.let { item -> append("data-srcx-finding-id=\"${item.id.escapeWorkspaceHtml()}\" ") }
            appendLine(">")
            append("<span class=\"srcx-dashboard__finding-severity\">")
            append(severity)
            appendLine("</span><div>")
            append("<strong>${finding.message.escapeWorkspaceHtml()}</strong>")
            append("<p>")
            append(finding.suggestion.escapeWorkspaceHtml())
            appendLine("</p>")
            finding.filePath?.let { filePath ->
                val location = filePath + finding.line?.let { line -> ":$line" }.orEmpty()
                appendLine("<code>${location.escapeWorkspaceHtml()}</code>")
            }
            evidence?.linkKind?.let { linkKind ->
                append("<button type=\"button\" data-srcx-open-finding ")
                append("data-srcx-finding-id=\"${evidence.id.escapeWorkspaceHtml()}\">")
                appendLine("${linkKind.buttonLabel}</button>")
            } ?: run {
                appendLine("<small>${finding.architectureEvidenceUnavailableMessage()}</small>")
            }
            appendLine("</div></article>")
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

    private companion object {
        const val PERCENT = 100
    }
}

private data class FindingScopeRenderer(
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

private class FindingControlsHtmlRenderer(
    private val scopes: List<FindingScopeRenderer>,
) {
    private val findingCount: Int = scopes.sumOf { it.findings.size }

    fun render(): String =
        buildString {
            appendLine("<details class=\"srcx-dashboard__finding-controls\" data-srcx-finding-controls>")
            appendLine("<summary class=\"srcx-dashboard__finding-filter-toggle\">")
            appendLine("<span><strong>Filter findings</strong><small>Build / project / severity</small></span>")
            append("<output data-srcx-finding-filter-status role=\"status\" aria-live=\"polite\" ")
            append("aria-atomic=\"true\">$findingCount ")
            append(if (findingCount == 1) "finding" else "findings")
            appendLine(" shown</output></summary>")
            appendLine("<div class=\"srcx-dashboard__finding-filter-body\">")
            appendLine(renderGroup("build", "Build", "All builds", buildOptions()))
            appendLine(renderGroup("project", "Project", "All projects", projectOptions()))
            appendLine(renderGroup("severity", "Severity", "All severities", severityOptions()))
            appendLine("<div class=\"srcx-dashboard__finding-filter-summary\">")
            appendLine("<button type=\"button\" data-srcx-finding-filter-reset disabled>Reset filters</button>")
            appendLine("<small>Select one printed label from each row.</small>")
            appendLine("</div></div></details>")
        }

    private fun buildOptions(): List<FindingFilterOptionRenderer> =
        scopes
            .groupBy { it.buildName }
            .map { (buildName, buildScopes) ->
                FindingFilterOptionRenderer(
                    value = buildName,
                    label = buildName,
                    count = buildScopes.sumOf { it.findings.size },
                )
            }.sortedBy { it.label }

    private fun projectOptions(): List<FindingFilterOptionRenderer> =
        scopes
            .distinctBy { scope -> scope.buildName to scope.projectPath }
            .sortedWith(compareBy({ it.buildName }, { it.projectPath }))
            .map { scope ->
                FindingFilterOptionRenderer(
                    value = scope.projectPath,
                    label = if (scope.projectPath == ":") ": (root project)" else scope.projectPath,
                    count = scope.findings.size,
                    context = scope.buildName,
                    buildName = scope.buildName,
                )
            }

    private fun severityOptions(): List<FindingFilterOptionRenderer> =
        FindingSeverity.entries.map { severity ->
            FindingFilterOptionRenderer(
                value = severity.name,
                label = severity.name.lowercase().replaceFirstChar { it.uppercase() },
                count = scopes.sumOf { scope -> scope.findings.count { it.severity == severity } },
            )
        }

    private fun renderGroup(
        filter: String,
        label: String,
        allLabel: String,
        options: List<FindingFilterOptionRenderer>,
    ): String =
        buildString {
            append("<fieldset class=\"srcx-dashboard__finding-filter-group\" data-srcx-finding-filter-group=\"")
            appendLine("$filter\">")
            appendLine("<legend>$label</legend>")
            append("<div class=\"srcx-dashboard__finding-filter-rail\" role=\"toolbar\" ")
            append("aria-label=\"Filter findings by ${label.lowercase()}\" aria-orientation=\"horizontal\" ")
            appendLine("data-srcx-finding-filter-rail data-srcx-roving-group>")
            appendLine(
                renderButton(
                    filter,
                    FindingFilterOptionRenderer("all", allLabel, findingCount),
                    selected = true,
                ),
            )
            options.forEach { option -> appendLine(renderButton(filter, option, selected = false)) }
            appendLine("</div></fieldset>")
        }

    private fun renderButton(
        filter: String,
        option: FindingFilterOptionRenderer,
        selected: Boolean,
    ): String =
        buildString {
            val findingLabel = if (option.count == 1) "finding" else "findings"
            append("<button type=\"button\" data-srcx-finding-filter=\"$filter\" ")
            append("data-srcx-finding-filter-value=\"${option.value.escapeWorkspaceHtml()}\" ")
            append("data-srcx-finding-filter-count=\"${option.count}\" data-srcx-roving-item ")
            option.buildName?.let { buildName ->
                append("data-srcx-finding-filter-build=\"${buildName.escapeWorkspaceHtml()}\" ")
            }
            append("aria-controls=\"srcx-finding-list\" aria-pressed=\"$selected\" ")
            append("aria-label=\"${option.accessibleLabel.escapeWorkspaceHtml()}, ${option.count} $findingLabel\" ")
            append("tabindex=\"${if (selected) 0 else -1}\">")
            append("<span>${option.label.escapeWorkspaceHtml()}</span>")
            option.context?.let { context -> append("<small>${context.escapeWorkspaceHtml()}</small>") }
            appendLine("<b>${option.count}</b></button>")
        }
}

private data class FindingFilterOptionRenderer(
    val value: String,
    val label: String,
    val count: Int,
    val context: String? = null,
    val buildName: String? = null,
) {
    val accessibleLabel: String get() = context?.let { "$label, build $it" } ?: label
}
