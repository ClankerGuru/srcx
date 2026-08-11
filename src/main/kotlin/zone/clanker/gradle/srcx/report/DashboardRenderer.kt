package zone.clanker.gradle.srcx.report

import zone.clanker.gradle.srcx.model.AnalysisSummary
import zone.clanker.gradle.srcx.model.FindingSeverity
import zone.clanker.gradle.srcx.model.ProjectSummary

/**
 * Renders the overview dashboard as Markdown (context.md).
 *
 * Contains the high-level overview: project structure, package groups,
 * dependencies, build dependency graph (Mermaid), included build summaries,
 * and links to split detail files (hub-classes, entry-points, anti-patterns, etc.).
 */
@Suppress("LongParameterList")
internal class DashboardRenderer(
    private val rootName: String,
    private val summaries: List<ProjectSummary>,
    private val includedBuilds: List<IncludedBuildRef>,
    private val includedBuildSummaries: Map<String, List<ProjectSummary>> = emptyMap(),
    private val buildEdges: List<BuildEdge> = emptyList(),
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
            appendSplitFileLinks()
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
            } +
                includedBuildSummaries.values.sumOf { projects ->
                    projects.sumOf { s ->
                        s.analysis?.findings?.count {
                            it.severity == FindingSeverity.WARNING || it.severity == FindingSeverity.FORBIDDEN
                        } ?: 0
                    }
                }
        val subprojects = summaries.flatMap { it.subprojects }.distinct()

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
            val link = "../${ref.relativePath}/.srcx/context.md"
            appendLine("| ${ref.name} | $projectCount | $symbolCount | $warningCount | [view]($link) |")
        }
        appendLine()
    }

    private fun StringBuilder.appendSplitFileLinks() {
        appendLine("## Details")
        appendLine()
        appendLine("- [Hub Classes](hub-classes.md)")
        appendLine("- [Entry Points](entry-points.md)")
        appendLine("- [Anti-Patterns](anti-patterns.md)")
        appendLine("- [Interfaces](interfaces.md)")
        if (buildEdges.isNotEmpty() || crossBuildAnalysis != null) {
            appendLine("- [Cross-Build References](cross-build.md)")
        }
        appendLine()
    }

    companion object {
        fun projectReportPath(projectPath: String): String {
            val sanitized = projectPath.replace(":", "/").trimStart('/')
            return if (sanitized.isEmpty()) "root/context.md" else "$sanitized/context.md"
        }

        private fun nodeId(name: String): String =
            name.replace(Regex("[^a-zA-Z0-9]"), "_")
    }
}
