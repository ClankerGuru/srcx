package zone.clanker.gradle.srcx.report

import zone.clanker.gradle.srcx.model.FindingSeverity
import zone.clanker.gradle.srcx.model.HubClass
import zone.clanker.gradle.srcx.model.ProjectSummary
import zone.clanker.gradle.srcx.model.SymbolKind

/**
 * Renders the context page for a single included build.
 *
 * Produces a full context document: overview, symbols with hub annotations,
 * hub class trees, dependencies, and findings.
 *
 * @property buildName the name of the included build
 * @property summaries the project summaries within this build
 */
internal class IncludedBuildRenderer(
    private val buildName: String,
    private val summaries: List<ProjectSummary>,
) {
    fun render(): String =
        buildString {
            appendLine("# $buildName")
            appendLine()
            appendOverview()
            appendProjectsTable()
            appendSymbols()
            appendHubs()
            appendDependencies()
            appendProblems()
        }

    private fun StringBuilder.appendOverview() {
        val totalSymbols = summaries.sumOf { it.symbols.size }
        val totalWarnings =
            summaries.sumOf { s ->
                s.analysis?.findings?.count { it.severity == FindingSeverity.WARNING } ?: 0
            }
        val packages =
            summaries
                .flatMap { s -> s.symbols.map { it.packageName.value } }
                .distinct()
                .sorted()

        appendLine("## Overview")
        appendLine()
        appendLine("- $totalSymbols symbols across ${summaries.size} project(s)")
        if (totalWarnings > 0) appendLine("- $totalWarnings warning(s)")
        if (packages.isNotEmpty()) {
            appendLine("- packages: ${packages.joinToString(", ")}")
        }
        appendLine()
    }

    private fun StringBuilder.appendProjectsTable() {
        if (summaries.isEmpty()) return
        if (summaries.size == 1 && summaries[0].subprojects.isEmpty()) return

        appendLine("## Projects")
        appendLine()
        appendLine("| Project | Symbols | Source Sets | Report |")
        appendLine("|---------|---------|------------|--------|")
        for (s in summaries) {
            val report = DashboardRenderer.projectReportPath(s.projectPath.value)
            val sets = s.sourceSets.joinToString(", ") { it.name.value }.ifEmpty { "-" }
            appendLine("| ${s.projectPath} | ${s.symbols.size} | $sets | [view]($report) |")
        }
        appendLine()
    }

    private fun StringBuilder.appendSymbols() {
        for (summary in summaries) {
            if (summary.symbols.isEmpty()) continue
            val hubs =
                summary.analysis?.hubs?.associate { it.name to it } ?: emptyMap()
            for (ss in summary.sourceSets) {
                if (ss.symbols.isEmpty()) continue
                appendSourceSet(ss, hubs)
            }
        }
    }

    private fun StringBuilder.appendSourceSet(
        ss: zone.clanker.gradle.srcx.model.SourceSetSummary,
        hubs: Map<String, HubClass>,
    ) {
        appendLine("## ${ss.name}")
        appendLine()
        for (s in ss.symbols) {
            if (s.kind != SymbolKind.CLASS) continue
            val hub = hubs[s.name.value]
            val roleTag =
                if (hub != null && hub.role.isNotEmpty()) " [${hub.role}]" else ""
            val depTag =
                if (hub != null && hub.dependentCount > 0) {
                    " (${hub.dependentCount} dependents)"
                } else {
                    ""
                }
            appendLine(
                "- class `${s.name}`$roleTag$depTag — ${s.packageName}, ${s.filePath}:${s.lineNumber}",
            )
        }
        val functions = ss.symbols.count { it.kind == SymbolKind.FUNCTION }
        if (functions > 0) appendLine("- $functions functions")
        val properties = ss.symbols.count { it.kind == SymbolKind.PROPERTY }
        if (properties > 0) appendLine("- $properties properties")
        appendLine()
    }

    private fun StringBuilder.appendHubs() {
        val allHubs =
            summaries.flatMap { it.analysis?.hubs ?: emptyList() }
        if (allHubs.isEmpty()) return

        appendLine("## Hub Classes")
        appendLine()
        for (hub in allHubs) {
            appendHubTree(hub)
        }
    }

    private fun StringBuilder.appendHubTree(hub: HubClass) {
        val roleTag = if (hub.role.isNotEmpty()) " [${hub.role}]" else ""
        val loc =
            if (hub.filePath.isNotEmpty()) " — ${hub.filePath}:${hub.line}" else ""
        if (hub.dependentCount >= ProjectReportRenderer.SUPER_NODE_THRESHOLD) {
            appendLine(
                "- **${hub.name}**$roleTag$loc — super node (${hub.dependentCount} dependents)",
            )
        } else {
            appendLine("- **${hub.name}**$roleTag$loc (${hub.dependentCount} dependents)")
            for (dep in hub.dependents) {
                appendLine("  - ${dep.name} — ${dep.filePath}:${dep.line}")
            }
        }
        appendLine()
    }

    private fun StringBuilder.appendDependencies() {
        val allDeps =
            summaries.flatMap { it.dependencies }
        if (allDeps.isEmpty()) return

        appendLine("## Dependencies")
        appendLine()
        val byScope = allDeps.groupBy { it.scope }
        for ((scope, deps) in byScope) {
            val artifacts =
                deps.joinToString(", ") { "${it.group}:${it.artifact}:${it.version}" }
            appendLine("- $scope: $artifacts")
        }
        appendLine()
    }

    private fun StringBuilder.appendProblems() {
        val allFindings =
            summaries.flatMap { s ->
                s.analysis?.findings ?: emptyList()
            }
        val warnings = allFindings.filter { it.severity == FindingSeverity.WARNING }
        val notes = allFindings.filter { it.severity == FindingSeverity.INFO }
        val allCycles =
            summaries.flatMap { s -> s.analysis?.cycles ?: emptyList() }

        if (warnings.isEmpty() && notes.isEmpty() && allCycles.isEmpty()) return

        appendLine("## Problems")
        appendLine()
        if (warnings.isNotEmpty()) {
            for (f in warnings) {
                appendLine("- **WARNING** ${f.message}")
                appendLine("  - ${f.suggestion}")
            }
        }
        if (notes.isNotEmpty()) {
            for (f in notes) {
                appendLine("- **INFO** ${f.message}")
                appendLine("  - ${f.suggestion}")
            }
        }
        if (allCycles.isNotEmpty()) {
            appendLine()
            appendLine("### Circular Dependencies")
            appendLine()
            for (cycle in allCycles) {
                appendLine("- ${cycle.joinToString(" -> ")}")
            }
        }
        appendLine()
    }
}
