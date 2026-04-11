package zone.clanker.gradle.srcx.report

import zone.clanker.gradle.srcx.model.AnalysisSummary

/**
 * Renders the cross-build.md file showing cross-build references grouped by build pair.
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
}
