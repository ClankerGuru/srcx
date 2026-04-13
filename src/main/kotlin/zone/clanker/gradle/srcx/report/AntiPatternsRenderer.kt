package zone.clanker.gradle.srcx.report

import zone.clanker.gradle.srcx.model.Finding
import zone.clanker.gradle.srcx.model.FindingSeverity
import zone.clanker.gradle.srcx.model.ProjectSummary

/**
 * Renders the anti-patterns.md file with all findings grouped by severity.
 *
 * Collects findings from all project summaries and included build summaries,
 * deduplicates by message, and renders with severity icons.
 *
 * @property summaries root project summaries
 * @property includedBuildSummaries included build summaries keyed by build name
 */
internal class AntiPatternsRenderer(
    private val summaries: List<ProjectSummary>,
    private val includedBuildSummaries: Map<String, List<ProjectSummary>> = emptyMap(),
) {
    fun render(): String =
        buildString {
            appendLine("# Anti-Patterns")
            appendLine()
            val allFindings = collectAllFindings()
            if (allFindings.isEmpty()) {
                appendLine("No anti-patterns detected.")
                appendLine()
                return@buildString
            }
            val forbidden = allFindings.filter { it.second.severity == FindingSeverity.FORBIDDEN }
            val warnings = allFindings.filter { it.second.severity == FindingSeverity.WARNING }
            val notes = allFindings.filter { it.second.severity == FindingSeverity.INFO }

            if (forbidden.isNotEmpty()) {
                appendLine("## Forbidden")
                appendLine()
                for ((source, finding) in forbidden) {
                    appendLine("- ${finding.severity.icon} **$source** — ${finding.message}")
                    appendLine("  - ${finding.suggestion}")
                }
                appendLine()
            }
            if (warnings.isNotEmpty()) {
                appendLine("## Warnings")
                appendLine()
                for ((source, finding) in warnings) {
                    appendLine("- ${finding.severity.icon} **$source** — ${finding.message}")
                    appendLine("  - ${finding.suggestion}")
                }
                appendLine()
            }
            if (notes.isNotEmpty()) {
                appendLine("## Notes")
                appendLine()
                for ((source, finding) in notes) {
                    appendLine("- ${finding.severity.icon} **$source** — ${finding.message}")
                    appendLine("  - ${finding.suggestion}")
                }
                appendLine()
            }
        }

    private fun collectAllFindings(): List<Pair<String, Finding>> {
        val rootFindings =
            summaries.flatMap { s ->
                val path = s.projectPath.value
                s.analysis?.findings?.map { f -> path to f } ?: emptyList()
            }
        val buildFindings =
            includedBuildSummaries.flatMap { (name, projects) ->
                projects.flatMap { s ->
                    s.analysis?.findings?.map { f -> name to f } ?: emptyList()
                }
            }
        return (rootFindings + buildFindings).distinctBy { it.second.message }
    }
}
