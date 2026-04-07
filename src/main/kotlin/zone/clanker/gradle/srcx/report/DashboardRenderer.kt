package zone.clanker.gradle.srcx.report

import zone.clanker.gradle.srcx.model.ProjectSummary

/**
 * Renders the root dashboard index as Markdown.
 *
 * Produces a summary table linking to all project reports, plus an
 * optional included builds section. Output is written to `.srcx/index.md`.
 *
 * ```kotlin
 * val renderer = DashboardRenderer(summaries, includedBuildNames)
 * val markdown = renderer.render()
 * ```
 *
 * @property summaries the list of project summaries to include
 * @property includedBuilds names of included builds (for the extra section)
 * @see zone.clanker.gradle.srcx.report.ProjectReportRenderer
 */
internal class DashboardRenderer(
    private val summaries: List<ProjectSummary>,
    private val includedBuilds: List<String>,
) {
    /**
     * Render the full dashboard Markdown report.
     *
     * @return the complete Markdown content for the dashboard index
     */
    fun render(): String =
        buildString {
            appendLine("# Source Dashboard")
            appendLine()
            appendProjectsTable()
            appendIncludedBuilds()
        }

    private fun StringBuilder.appendProjectsTable() {
        appendLine("## Projects")
        appendLine()
        if (summaries.isEmpty()) {
            appendLine("No projects found.")
            appendLine()
            return
        }
        appendLine("| Project | Symbols | Dependencies | Report |")
        appendLine("|---------|---------|-------------|--------|")
        for (summary in summaries) {
            val reportPath = projectReportPath(summary.projectPath)
            appendLine(
                "| ${summary.projectPath} | ${summary.symbols.size}" +
                    " | ${summary.dependencies.size} | [view]($reportPath) |",
            )
        }
        appendLine()
    }

    private fun StringBuilder.appendIncludedBuilds() {
        if (includedBuilds.isEmpty()) return
        appendLine("## Included Builds")
        appendLine()
        appendLine("| Build | Report |")
        appendLine("|-------|--------|")
        for (build in includedBuilds) {
            appendLine("| $build | [view]($build/index.md) |")
        }
        appendLine()
    }

    companion object {
        /**
         * Convert a Gradle project path to its report file path.
         *
         * @param projectPath the Gradle project path (e.g. ":app" or ":")
         * @return the relative path to the project's report file
         */
        fun projectReportPath(projectPath: String): String {
            val sanitized = projectPath.replace(":", "/").trimStart('/')
            return if (sanitized.isEmpty()) "root/symbols.md" else "$sanitized/symbols.md"
        }
    }
}
