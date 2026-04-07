package zone.clanker.gradle.srcx.report

import zone.clanker.gradle.srcx.model.ProjectSummary

/**
 * Renders the index page for a single included build.
 *
 * Produces a dashboard showing all projects within the included build
 * with links to their individual symbol reports.
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
            appendProjectsTable()
        }

    private fun StringBuilder.appendProjectsTable() {
        appendLine("## Projects")
        appendLine()
        if (summaries.isEmpty()) {
            appendLine("No projects found.")
            appendLine()
            return
        }
        appendLine("| Project | Symbols | Source Sets | Report |")
        appendLine("|---------|---------|------------|--------|")
        for (s in summaries) {
            val path = s.projectPath
            val report = DashboardRenderer.projectReportPath(path.value)
            val syms = s.symbols.size
            val sets = s.sourceSets.joinToString(", ") { it.name.value }.ifEmpty { "-" }
            appendLine("| $path | $syms | $sets | [view]($report) |")
        }
        appendLine()
    }
}
