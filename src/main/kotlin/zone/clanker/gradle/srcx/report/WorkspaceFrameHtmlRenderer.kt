package zone.clanker.gradle.srcx.report

import zone.clanker.gradle.srcx.model.WorkspaceReport

/** Renders the dashboard frame, metrics, headings, and evidence footer. */
internal class WorkspaceFrameHtmlRenderer(
    private val resources: WorkspaceHtmlResourceRenderer,
) {
    fun render(report: WorkspaceReport): Map<String, String> =
        buildMap {
            put("workspaceName", report.name.escapeWorkspaceHtml())
            put("mastheadHtml", renderMasthead(report))
            put("heroTagsHtml", renderHeroTags(report))
            put("metricStripHtml", renderMetricStrip(report))
            put(
                "architectureHeadingHtml",
                renderHeading("01", "Source graph", "Architecture", ARCHITECTURE_SUMMARY, "tertiary"),
            )
            put("buildsHeadingHtml", renderHeading("02", "Workspace", "Builds", BUILD_SUMMARY, "primary"))
            put("healthHeadingHtml", renderHeading("03", "Analysis", "Health", HEALTH_SUMMARY, "secondary"))
            put("findingsHeadingHtml", renderHeading("04", "Project scopes", "Findings", FINDINGS_SUMMARY, "error"))
            put("sourceNoteHtml", renderSourceNote())
        }

    private fun renderMasthead(report: WorkspaceReport): String =
        resources.component(
            "masthead",
            mapOf(
                "brand" to "SRCX",
                "bureau" to report.name.escapeWorkspaceHtml(),
                "folio" to "Workspace architecture / source evidence",
                "tone" to "success",
                "state" to "Scan complete",
                "classes" to "",
            ),
        )

    private fun renderHeroTags(report: WorkspaceReport): String =
        buildString {
            append("<span class=\"srcx-tag srcx-tone--primary\">")
            append(report.name.escapeWorkspaceHtml())
            append("</span>")
            append("<span class=\"srcx-tag srcx-tone--secondary\">")
            append(report.includedBuilds.size + 1)
            append(" builds</span>")
            append("<span class=\"srcx-tag srcx-tone--accent\">source relationships</span>")
        }

    private fun renderMetricStrip(report: WorkspaceReport): String {
        val projectFindingCount = report.allProjects.sumOf { project -> project.analysis?.findings?.size ?: 0 }
        val metrics =
            listOf(
                renderMetric(report.projectCount, "Projects", "primary"),
                renderMetric(report.symbolCount, "Symbols", "secondary"),
                renderMetric(report.dependencyCount, "Dependencies", "accent"),
                renderMetric(projectFindingCount, "Findings", "tertiary"),
            )
        return resources.component(
            "metric-strip",
            mapOf(
                "source" to "Workspace scan",
                "snapshot" to "Root + included builds",
                "metricCount" to metrics.size.toString(),
                "itemsHtml" to metrics.joinToString("\n"),
                "classes" to "",
            ),
        )
    }

    private fun renderMetric(
        value: Int,
        label: String,
        tone: String,
    ): String =
        resources.component(
            "metric",
            mapOf(
                "value" to value.toString(),
                "label" to label,
                "tone" to tone,
                "classes" to "",
            ),
        )

    private fun renderHeading(
        index: String,
        kicker: String,
        title: String,
        summary: String,
        tone: String,
    ): String =
        resources.component(
            "section-heading",
            mapOf(
                "index" to index,
                "kicker" to kicker,
                "title" to title,
                "summary" to summary,
                "tone" to tone,
                "classes" to "",
            ),
        )

    private fun renderSourceNote(): String =
        resources.component(
            "source-note",
            mapOf(
                "label" to "Evidence",
                "path" to "Resolved source relationships",
                "meta" to "Build + project scoped",
                "tone" to "secondary",
                "classes" to "",
            ),
        )

    private companion object {
        const val BUILD_SUMMARY =
            "See workspace membership, compare build sizes, and inspect observed cross-build dependencies."
        const val HEALTH_SUMMARY = "Review symbol ownership, finding severity, production hubs, and scan coverage."
        const val FINDINGS_SUMMARY = "Each finding stays with the build and project where the scan found it."
        const val ARCHITECTURE_SUMMARY =
            "See which build owns each indexed file and how resolved source references connect files across projects."
    }
}
