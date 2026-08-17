package zone.clanker.gradle.srcx.report

import zone.clanker.gradle.srcx.model.WorkspaceReport

/** Renders a typed [WorkspaceReport] as embedded and standalone HTML without source or Gradle access. */
class WorkspaceHtmlRenderer internal constructor(
    private val resources: WorkspaceHtmlResourceRenderer,
) {
    constructor() : this(WorkspaceHtmlResourceRenderer())

    private val frameRenderer = WorkspaceFrameHtmlRenderer(resources)
    private val structureRenderer = WorkspaceStructureHtmlRenderer(resources)
    private val analysisRenderer = WorkspaceAnalysisHtmlRenderer(resources)
    private val architectureRenderer = WorkspaceArchitectureHtmlRenderer(resources)

    fun render(report: WorkspaceReport): RenderedWorkspaceHtml {
        val architectureGraph = buildWorkspaceArchitectureGraph(report)
        val findingEvidence = workspaceArchitectureFindingEvidence(report, architectureGraph)
        val slots =
            frameRenderer.render(report) +
                structureRenderer.render(report, findingEvidence) +
                analysisRenderer.render(report, findingEvidence) +
                architectureRenderer.render(architectureGraph) +
                mapOf("scriptsHtml" to resources.scripts())
        val article = resources.dashboard(slots).trim()
        val fragment =
            buildString {
                appendLine("<style data-srcx-theme=\"gort\">")
                appendLine(resources.styles())
                appendLine("</style>")
                append(article)
            }
        return RenderedWorkspaceHtml(
            fragment = fragment,
            document = renderDocument(report.name, fragment),
        )
    }

    private fun renderDocument(
        workspaceName: String,
        fragment: String,
    ): String =
        buildString {
            appendLine("<!doctype html>")
            appendLine("<html lang=\"en\">")
            appendLine("<head>")
            appendLine("<meta charset=\"utf-8\">")
            appendLine("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
            appendLine("<title>${workspaceName.escapeWorkspaceHtml()} SRCX source documentation</title>")
            appendLine("</head>")
            appendLine("<body class=\"srcx-atlas-document\" style=\"margin: 0; padding: 0\">")
            appendLine(fragment)
            appendLine("</body>")
            appendLine("</html>")
        }

    /** Both supported render surfaces for one immutable workspace report. */
    data class RenderedWorkspaceHtml(
        val fragment: String,
        val document: String,
    )
}
