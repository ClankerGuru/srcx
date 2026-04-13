package zone.clanker.gradle.srcx.report

import zone.clanker.gradle.srcx.model.FindingSeverity
import zone.clanker.gradle.srcx.model.HubClass
import zone.clanker.gradle.srcx.model.ProjectSummary
import zone.clanker.gradle.srcx.model.SourceSetSummary

/**
 * Renders a per-project symbol report as Markdown.
 *
 * When source sets are available, symbols are grouped by source set
 * (main, test, androidTest, etc.). Falls back to a flat list when
 * source set data is not present. Includes analysis results when available.
 *
 * @property summary the project summary containing symbols and dependencies
 * @see DashboardRenderer
 */
internal class ProjectReportRenderer(
    private val summary: ProjectSummary,
) {
    companion object {
        /** Hubs with this many or more dependents are labeled as super nodes. */
        const val SUPER_NODE_THRESHOLD = 50
    }

    fun render(): String =
        buildString {
            appendLine("# ${summary.projectPath}")
            appendLine()
            if (summary.sourceSets.isNotEmpty()) {
                appendSourceSets()
            } else {
                appendSymbols()
            }
            appendAnalysis()
            appendDependencies()
            appendBuild()
        }

    private fun StringBuilder.appendSourceSets() {
        for (ss in summary.sourceSets) {
            appendSourceSet(ss)
        }
    }

    private fun StringBuilder.appendSourceSet(ss: SourceSetSummary) {
        appendLine("## ${ss.name}")
        appendLine()
        if (ss.symbols.isEmpty()) {
            appendLine("No symbols extracted.")
            appendLine()
            return
        }
        appendLine("| Kind | Name | Package | File | Line |")
        appendLine("|------|------|---------|------|------|")
        for (s in ss.symbols) {
            appendLine("| ${s.kind} | ${s.name} | ${s.packageName} | ${s.filePath} | ${s.lineNumber} |")
        }
        appendLine()
    }

    private fun StringBuilder.appendSymbols() {
        appendLine("## Symbols")
        appendLine()
        if (summary.symbols.isEmpty()) {
            appendLine("No symbols extracted.")
            appendLine()
            return
        }
        appendLine("| Kind | Name | Package | File | Line |")
        appendLine("|------|------|---------|------|------|")
        for (s in summary.symbols) {
            appendLine("| ${s.kind} | ${s.name} | ${s.packageName} | ${s.filePath} | ${s.lineNumber} |")
        }
        appendLine()
    }

    private fun StringBuilder.appendAnalysis() {
        val analysis = summary.analysis ?: return

        if (analysis.hubs.isNotEmpty()) {
            appendLine("## Hub Classes")
            appendLine()
            for (hub in analysis.hubs) {
                appendHubTree(hub)
            }
        }

        val forbidden = analysis.findings.filter { it.severity == FindingSeverity.FORBIDDEN }
        val warnings = analysis.findings.filter { it.severity == FindingSeverity.WARNING }
        val infos = analysis.findings.filter { it.severity == FindingSeverity.INFO }

        if (forbidden.isNotEmpty() || warnings.isNotEmpty() || infos.isNotEmpty()) {
            appendLine("## Findings")
            appendLine()
            for (f in forbidden) {
                appendLine("- **${f.severity.icon}** ${f.message}")
                appendLine("  - ${f.suggestion}")
            }
            for (f in warnings) {
                appendLine("- **${f.severity.icon}** ${f.message}")
                appendLine("  - ${f.suggestion}")
            }
            for (f in infos) {
                appendLine("- **${f.severity.icon}** ${f.message}")
                appendLine("  - ${f.suggestion}")
            }
            appendLine()
        }

        if (analysis.cycles.isNotEmpty()) {
            appendLine("## Circular Dependencies")
            appendLine()
            for (cycle in analysis.cycles) {
                appendLine("- ${cycle.joinToString(" -> ")}")
            }
            appendLine()
        }
    }

    private fun StringBuilder.appendHubTree(hub: HubClass) {
        val roleTag = if (hub.role.isNotEmpty()) " [${hub.role}]" else ""
        val loc = if (hub.filePath.isNotEmpty()) " — ${hub.filePath}:${hub.line}" else ""
        val depLabel = if (hub.dependentCount == 1) "dependent" else "dependents"
        if (hub.dependentCount >= SUPER_NODE_THRESHOLD) {
            appendLine("- **${hub.name}**$roleTag$loc — super node (${hub.dependentCount} $depLabel)")
        } else {
            appendLine("- **${hub.name}**$roleTag$loc (${hub.dependentCount} $depLabel)")
            for (dep in hub.dependents) {
                appendLine("  - ${dep.name} — ${dep.filePath}:${dep.line}")
            }
        }
        appendLine()
    }

    private fun StringBuilder.appendDependencies() {
        appendLine("## Dependencies")
        appendLine()
        if (summary.dependencies.isEmpty()) {
            appendLine("No dependencies found.")
            appendLine()
            return
        }
        appendLine("| Scope | Artifact | Version |")
        appendLine("|-------|----------|---------|")
        for (dep in summary.dependencies) {
            appendLine("| ${dep.scope} | ${dep.group}:${dep.artifact} | ${dep.version} |")
        }
        appendLine()
    }

    private fun StringBuilder.appendBuild() {
        appendLine("## Build")
        appendLine()
        appendLine("- Build file: ${summary.buildFile}")
        val dirs = summary.sourceDirs.joinToString(", ").ifEmpty { "none" }
        appendLine("- Source dirs: $dirs")
        if (summary.subprojects.isNotEmpty()) {
            val subs = summary.subprojects.joinToString(", ")
            appendLine("- Subprojects: $subs")
        }
        appendLine()
    }
}
