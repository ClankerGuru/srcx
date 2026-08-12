package zone.clanker.gradle.srcx.report

import zone.clanker.gradle.srcx.model.WorkspaceReport

/** Renders the dashboard frame, metrics, headings, and provenance footer. */
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
            put("modelHeadingHtml", renderHeading("04", "Direct input", "Model provenance", MODEL_SUMMARY, "accent"))
            put("findingsHeadingHtml", renderHeading("05", "Project scopes", "Findings", FINDINGS_SUMMARY, "error"))
            put("sourceNoteHtml", renderSourceNote())
        }

    private fun renderMasthead(report: WorkspaceReport): String =
        resources.component(
            "masthead",
            mapOf(
                "brand" to "SRCX",
                "bureau" to report.name.escapeWorkspaceHtml(),
                "folio" to "WorkspaceReport / typed HTML",
                "tone" to "success",
                "state" to "Direct model",
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
            append("<span class=\"srcx-tag srcx-tone--accent\">direct typed model</span>")
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
                "source" to "Workspace snapshot",
                "snapshot" to "Project-scoped source data",
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
                "label" to "Typed source",
                "path" to "SRCX WorkspaceReport",
                "meta" to "Direct render",
                "tone" to "secondary",
                "classes" to "",
            ),
        )

    private companion object {
        const val BUILD_SUMMARY = "One comparison of root and included builds, with observed build edges."
        const val HEALTH_SUMMARY = "Ownership, project-scoped severity, production hubs, and source coverage."
        const val FINDINGS_SUMMARY = "Findings remain grouped under the project analysis that produced them."
        const val ARCHITECTURE_SUMMARY =
            "Important symbols and their resolved cumulative workspace relationships, clustered by build and project."
        const val MODEL_SUMMARY = "Collapsed field coverage and provenance for the typed workspace report."
    }
}
