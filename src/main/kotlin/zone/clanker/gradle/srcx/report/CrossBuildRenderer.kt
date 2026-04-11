package zone.clanker.gradle.srcx.report

import zone.clanker.gradle.srcx.model.AnalysisSummary

/**
 * Renders the cross-build.md file showing cross-build references grouped by build pair.
 *
 * Shows a tree per hub class with dependents marked by build origin.
 * External-build dependents are marked with a link icon.
 *
 * @property buildEdges dependency edges between builds
 * @property crossBuildAnalysis the cross-build analysis results (hubs, findings)
 */
internal class CrossBuildRenderer(
    private val buildEdges: List<DashboardRenderer.BuildEdge>,
    private val crossBuildAnalysis: AnalysisSummary?,
) {
    fun render(): String =
        buildString {
            appendLine("# Cross-Build References")
            appendLine()
            if (buildEdges.isEmpty() && crossBuildAnalysis == null) {
                appendLine("No cross-build references detected.")
                appendLine()
                return@buildString
            }
            appendBuildEdges()
            appendCrossBuildHubs()
            appendCrossBuildCycles()
        }

    private fun StringBuilder.appendBuildEdges() {
        if (buildEdges.isEmpty()) return
        appendLine("## Build Dependencies")
        appendLine()
        val byFrom = buildEdges.groupBy { it.from }
        for ((from, edges) in byFrom) {
            appendLine("### $from")
            appendLine()
            for (edge in edges) {
                appendLine("- depends on **${edge.to}**")
            }
            appendLine()
        }
    }

    private fun StringBuilder.appendCrossBuildHubs() {
        val hubs = crossBuildAnalysis?.hubs ?: return
        if (hubs.isEmpty()) return
        appendLine("## Shared Hub Classes")
        appendLine()
        appendLine("| Class | File | Dependents | Role |")
        appendLine("|-------|------|------------|------|")
        for (hub in hubs) {
            appendLine("| `${hub.name}` | ${hub.filePath}:${hub.line} | ${hub.dependentCount} | ${hub.role} |")
        }
        appendLine()
        for (hub in hubs.filter { it.dependents.isNotEmpty() }) {
            appendLine("### ${hub.name}")
            appendLine()
            appendLine("```")
            appendLine("${hub.filePath}:${hub.line} \u2014 ${hub.dependentCount} dependents")
            val deps = hub.dependents
            for ((index, dep) in deps.withIndex()) {
                val isLast = index == deps.lastIndex
                val connector = if (isLast) "\u2514\u2500\u2500" else "\u251C\u2500\u2500"
                val externalMarker = if (isExternalBuild(dep.filePath, hub.filePath)) " \uD83D\uDD17" else ""
                appendLine("$connector ${dep.filePath}:${dep.line}$externalMarker")
            }
            appendLine("```")
            appendLine()
        }
    }

    private fun StringBuilder.appendCrossBuildCycles() {
        val cycles = crossBuildAnalysis?.cycles ?: return
        if (cycles.isEmpty()) return
        appendLine("## Cross-Build Cycles")
        appendLine()
        for (cycle in cycles) {
            appendLine("- ${cycle.joinToString(" -> ")}")
        }
        appendLine()
    }

    companion object {
        /**
         * Determines if a dependent is from a different build than the hub.
         * Uses path prefix comparison as a heuristic.
         */
        internal fun isExternalBuild(dependentPath: String, hubPath: String): Boolean {
            val depRoot = dependentPath.substringBefore("/")
            val hubRoot = hubPath.substringBefore("/")
            return depRoot != hubRoot && depRoot.isNotEmpty() && hubRoot.isNotEmpty()
        }
    }
}
