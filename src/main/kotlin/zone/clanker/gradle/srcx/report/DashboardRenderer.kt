package zone.clanker.gradle.srcx.report

import zone.clanker.gradle.srcx.model.AnalysisSummary
import zone.clanker.gradle.srcx.model.FindingSeverity
import zone.clanker.gradle.srcx.model.HubClass
import zone.clanker.gradle.srcx.model.ProjectSummary
import zone.clanker.gradle.srcx.model.SourceSetSummary
import zone.clanker.gradle.srcx.model.SymbolKind

/**
 * Renders a comprehensive dashboard as Markdown.
 *
 * The dashboard is the single output file that serves as both a human-readable
 * overview and LLM-ready context. It contains: project structure, package groups,
 * dependencies, build dependency graph (Mermaid), per-project symbols with roles,
 * included build summaries with links, and a problems section.
 */
@Suppress("LongParameterList")
internal class DashboardRenderer(
    private val rootName: String,
    private val summaries: List<ProjectSummary>,
    private val includedBuilds: List<IncludedBuildRef>,
    private val includedBuildSummaries: Map<String, List<ProjectSummary>> = emptyMap(),
    private val buildEdges: List<BuildEdge> = emptyList(),
    private val classDiagram: String = "",
    private val crossBuildAnalysis: AnalysisSummary? = null,
) {
    data class IncludedBuildRef(
        val name: String,
        val relativePath: String,
    )

    data class BuildEdge(
        val from: String,
        val to: String,
    )

    fun render(): String =
        buildString {
            appendLine("# $rootName")
            appendLine()
            appendOverview()
            appendProjects()
            appendBuildGraph()
            appendIncludedBuilds()
            appendSymbols()
            appendCrossBuildHubs()
            appendClassGraph()
            appendProblems()
        }

    private fun StringBuilder.appendOverview() {
        val totalSymbols =
            summaries.sumOf { it.symbols.size } +
                includedBuildSummaries.values.sumOf { projects -> projects.sumOf { it.symbols.size } }
        val totalWarnings =
            summaries.sumOf { s ->
                s.analysis?.findings?.count {
                    it.severity == FindingSeverity.WARNING || it.severity == FindingSeverity.FORBIDDEN
                } ?: 0
            }
        val subprojects = summaries.flatMap { it.subprojects }.distinct()
        val packages = summaries.flatMap { s -> s.symbols.map { it.packageName.value } }.distinct().sorted()

        appendLine("## Overview")
        appendLine()
        val projectLabel = if (summaries.size == 1) "project" else "projects"
        appendLine("- $totalSymbols symbols across ${summaries.size} $projectLabel")
        if (totalWarnings > 0) {
            val warningLabel = if (totalWarnings == 1) "warning" else "warnings"
            appendLine("- $totalWarnings $warningLabel")
        }
        if (includedBuilds.isNotEmpty()) {
            val buildLabel = if (includedBuilds.size == 1) "included build" else "included builds"
            appendLine("- ${includedBuilds.size} $buildLabel")
        }
        if (subprojects.isNotEmpty()) {
            appendLine("- subprojects: ${subprojects.joinToString(", ")}")
        }
        if (packages.isNotEmpty()) {
            appendLine("- packages: ${packages.joinToString(", ")}")
        }
        appendLine()
    }

    private fun StringBuilder.appendProjects() {
        if (summaries.size <= 1 && summaries.firstOrNull()?.symbols?.isEmpty() == true) return

        appendLine("## Projects")
        appendLine()
        appendLine("| Project | Symbols | Source Sets | Dependencies | Warnings |")
        appendLine("|---------|---------|------------|-------------|----------|")
        for (s in summaries) {
            if (s.symbols.isEmpty() && s.dependencies.isEmpty() && s.sourceSets.isEmpty()) continue
            val sets = s.sourceSets.joinToString(", ") { it.name.value }.ifEmpty { "-" }
            val warnings =
                s.analysis?.findings?.count {
                    it.severity == FindingSeverity.WARNING || it.severity == FindingSeverity.FORBIDDEN
                } ?: 0
            appendLine("| ${s.projectPath} | ${s.symbols.size} | $sets | ${s.dependencies.size} | $warnings |")
        }
        appendLine()
    }

    private fun StringBuilder.appendBuildGraph() {
        if (buildEdges.isEmpty()) return
        appendLine("## Build Dependencies")
        appendLine()
        appendLine("```mermaid")
        appendLine("flowchart TD")
        val nodeIds = mutableSetOf<String>()
        for (edge in buildEdges) {
            val fromId = nodeId(edge.from)
            val toId = nodeId(edge.to)
            nodeIds.add(fromId)
            nodeIds.add(toId)
            appendLine("    $fromId --> $toId")
        }
        appendLine("```")
        appendLine()
    }

    private fun StringBuilder.appendIncludedBuilds() {
        if (includedBuilds.isEmpty()) return
        appendLine("## Included Builds")
        appendLine()
        appendLine("| Build | Projects | Symbols | Warnings | Context |")
        appendLine("|-------|----------|---------|----------|---------|")
        for (ref in includedBuilds) {
            val buildSummaries = includedBuildSummaries[ref.name]
            val projectCount = buildSummaries?.size ?: 0
            val symbolCount = buildSummaries?.sumOf { it.symbols.size } ?: 0
            val warningCount =
                buildSummaries?.sumOf { s ->
                    s.analysis?.findings?.count {
                        it.severity == FindingSeverity.WARNING || it.severity == FindingSeverity.FORBIDDEN
                    } ?: 0
                } ?: 0
            val link = "${ref.relativePath}/.srcx/context.md"
            appendLine("| ${ref.name} | $projectCount | $symbolCount | $warningCount | [view]($link) |")
        }
        appendLine()
    }

    private fun StringBuilder.appendSymbols() {
        for (summary in summaries) {
            if (summary.symbols.isEmpty()) continue
            appendProjectSymbols(summary)
        }
    }

    private fun StringBuilder.appendProjectSymbols(summary: ProjectSummary) {
        appendLine("## ${summary.projectPath}")
        appendLine()

        val hubs = summary.analysis?.hubs?.associate { it.name to it } ?: emptyMap()

        for (ss in summary.sourceSets) {
            if (ss.symbols.isEmpty()) continue
            appendSourceSetSymbols(ss, hubs)
        }

        appendProjectDependencies(summary)
    }

    private fun StringBuilder.appendSourceSetSymbols(
        ss: SourceSetSummary,
        hubs: Map<String, HubClass>,
    ) {
        appendLine("### ${ss.name}")
        appendLine()
        for (s in ss.symbols) {
            if (s.kind != SymbolKind.CLASS) continue
            val hub = hubs[s.name.value]
            val roleTag = if (hub != null && hub.role.isNotEmpty()) " [${hub.role}]" else ""
            val depTag =
                if (hub != null && hub.dependentCount > 0) {
                    val depLabel = if (hub.dependentCount == 1) "dependent" else "dependents"
                    " (${hub.dependentCount} $depLabel)"
                } else {
                    ""
                }
            appendLine("- class `${s.name}`$roleTag$depTag — ${s.packageName}, ${s.filePath}:${s.lineNumber}")
        }
        val functions = ss.symbols.filter { it.kind == SymbolKind.FUNCTION }
        if (functions.isNotEmpty()) {
            appendLine("- ${functions.size} functions")
        }
        val properties = ss.symbols.filter { it.kind == SymbolKind.PROPERTY }
        if (properties.isNotEmpty()) {
            appendLine("- ${properties.size} properties")
        }
        appendLine()
    }

    private fun StringBuilder.appendProjectDependencies(summary: ProjectSummary) {
        if (summary.dependencies.isNotEmpty()) {
            appendLine("### dependencies")
            appendLine()
            val byScope = summary.dependencies.groupBy { it.scope }
            for ((scope, deps) in byScope) {
                val artifacts = deps.joinToString(", ") { "${it.group}:${it.artifact}:${it.version}" }
                appendLine("- $scope: $artifacts")
            }
            appendLine()
        }
    }

    private fun StringBuilder.appendCrossBuildHubs() {
        val hubs = crossBuildAnalysis?.hubs ?: return
        if (hubs.isEmpty()) return
        appendLine("## Hot Classes (cross-build)")
        appendLine()
        appendLine("| Class | File | Dependents | Role |")
        appendLine("|-------|------|------------|------|")
        for (hub in hubs) {
            appendLine(
                "| `${hub.name}` | ${hub.filePath}:${hub.line} " +
                    "| ${hub.dependentCount} | ${hub.role} |",
            )
        }
        appendLine()
        for (hub in hubs.filter { it.dependentCount >= HUB_DETAIL_THRESHOLD }) {
            appendLine("### ${hub.name}")
            appendLine()
            for (dep in hub.dependents) {
                appendLine("- ${dep.name} — ${dep.filePath}:${dep.line}")
            }
            appendLine()
        }
    }

    private fun StringBuilder.appendClassGraph() {
        if (classDiagram.isBlank()) return
        appendLine("## Class Dependencies")
        appendLine()
        appendLine(classDiagram)
        appendLine()
    }

    private fun StringBuilder.appendProblems() {
        val allFindings =
            summaries.flatMap { s ->
                val path = s.projectPath.value
                s.analysis?.findings?.map { f -> Triple(path, f.severity, f) } ?: emptyList()
            }

        val buildFindings =
            includedBuildSummaries.flatMap { (name, projects) ->
                projects.flatMap { s ->
                    s.analysis?.findings?.map { f -> Triple(name, f.severity, f) } ?: emptyList()
                }
            }

        val combined = (allFindings + buildFindings).distinctBy { it.third.message }
        val forbidden = combined.filter { it.second == FindingSeverity.FORBIDDEN }
        val warnings = combined.filter { it.second == FindingSeverity.WARNING }
        val notes = combined.filter { it.second == FindingSeverity.INFO }

        if (forbidden.isEmpty() && warnings.isEmpty() && notes.isEmpty()) return

        appendLine("## Problems")
        appendLine()
        if (forbidden.isNotEmpty()) {
            appendLine("### Forbidden")
            appendLine()
            for ((source, severity, finding) in forbidden) {
                appendLine("- ${severity.icon} **$source** — ${finding.message}")
            }
            appendLine()
        }
        if (warnings.isNotEmpty()) {
            appendLine("### Warnings")
            appendLine()
            for ((source, severity, finding) in warnings) {
                appendLine("- ${severity.icon} **$source** — ${finding.message}")
            }
            appendLine()
        }
        if (notes.isNotEmpty()) {
            appendLine("### Notes")
            appendLine()
            for ((source, severity, finding) in notes) {
                appendLine("- ${severity.icon} **$source** — ${finding.message}")
            }
            appendLine()
        }
    }

    companion object {
        private const val HUB_DETAIL_THRESHOLD = 3

        fun projectReportPath(projectPath: String): String {
            val sanitized = projectPath.replace(":", "/").trimStart('/')
            return if (sanitized.isEmpty()) "root/context.md" else "$sanitized/context.md"
        }

        private fun nodeId(name: String): String =
            name.replace(Regex("[^a-zA-Z0-9]"), "_")
    }
}
