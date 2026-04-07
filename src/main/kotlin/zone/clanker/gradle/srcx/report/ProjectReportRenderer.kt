package zone.clanker.gradle.srcx.report

import zone.clanker.gradle.srcx.model.ProjectSummary

/**
 * Renders a per-project symbol report as Markdown.
 *
 * Produces a report with three sections: symbols table, dependencies
 * table, and build metadata. Output is written to
 * `.srcx/<projectName>/symbols.md`.
 *
 * ```kotlin
 * val renderer = ProjectReportRenderer(summary)
 * val markdown = renderer.render()
 * ```
 *
 * @property summary the project summary containing symbols and dependencies
 * @see zone.clanker.gradle.srcx.report.DashboardRenderer
 */
internal class ProjectReportRenderer(
    private val summary: ProjectSummary,
) {
    /**
     * Render the full per-project Markdown report.
     *
     * @return the complete Markdown content for this project's report
     */
    fun render(): String =
        buildString {
            appendLine("# ${summary.projectPath}")
            appendLine()
            appendSymbols()
            appendDependencies()
            appendBuild()
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
        for (symbol in summary.symbols) {
            appendLine("| ${symbol.kind} | ${symbol.name} | ${symbol.pkg} | ${symbol.file} | ${symbol.line} |")
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
