package zone.clanker.gradle.srcx.report

import zone.clanker.gradle.srcx.model.WorkspaceReport

/** Renders the cumulative workspace relationship explorer and its static fallback. */
internal class WorkspaceArchitectureHtmlRenderer(
    private val resources: WorkspaceHtmlResourceRenderer,
) {
    fun render(report: WorkspaceReport): Map<String, String> {
        val graph = buildWorkspaceArchitectureGraph(report)
        return mapOf(
            "architectureSummaryHtml" to renderSummary(graph),
            "architectureGraphHtml" to renderGraph(graph),
            "architectureEvidenceHtml" to renderEvidence(graph),
        )
    }

    private fun renderSummary(graph: WorkspaceArchitectureGraphRenderer): String =
        buildString {
            appendLine("<div class=\"srcx-dashboard__architecture-summary\">")
            appendLine("<p><strong>Explore files first.</strong> Arrows point from the file containing a reference to ")
            appendLine("the file it uses. Switch lenses for symbols, exact file findings, or resolved cycles. ")
            appendLine("Select a node or arrow for source evidence; this is static analysis, not runtime tracing.</p>")
            appendLine("<dl>")
            appendLine("<div><dt>Files</dt><dd>${graph.fileNodes.size}</dd></div>")
            appendLine("<div><dt>File links</dt><dd>${graph.fileEdges.size}</dd></div>")
            appendLine("<div><dt>Symbols</dt><dd>${graph.nodes.size}</dd></div>")
            appendLine("<div><dt>Cycles</dt><dd>${graph.cycles.size}</dd></div>")
            appendLine("</dl></div>")
        }

    private fun renderGraph(graph: WorkspaceArchitectureGraphRenderer): String =
        buildString {
            appendLine("<section class=\"srcx-dashboard__architecture-graph\" data-srcx-architecture-graph>")
            appendLine("<header class=\"srcx-dashboard__architecture-graph-head\">")
            appendLine("<div><span>Interactive source map</span><strong>Workspace atlas</strong></div>")
            appendLine("<p data-srcx-graph-status>${graph.fileNodes.size} files / ${graph.fileEdges.size} links</p>")
            appendLine("</header>")
            appendLine(renderControls())
            appendLine("<div class=\"srcx-dashboard__architecture-viewport\">")
            appendLine(renderFallback(graph))
            appendLine(
                "<svg class=\"srcx-dashboard__architecture-svg\" data-srcx-graph-svg hidden " +
                    "role=\"group\" aria-label=\"Interactive workspace relationship map\"></svg>",
            )
            appendLine(renderDetailPanel())
            appendLine("</div>")
            append("<script type=\"application/json\" data-srcx-architecture-data>")
            append(graph.toJson())
            appendLine("</script>")
            appendLine("</section>")
        }

    private fun renderControls(): String =
        buildString {
            appendLine("<div class=\"srcx-dashboard__architecture-controls\" data-srcx-graph-controls hidden>")
            appendLine("<div class=\"srcx-dashboard__architecture-lenses\" role=\"group\" aria-label=\"Map lens\">")
            appendLine("<button type=\"button\" data-srcx-graph-view=\"files\" aria-pressed=\"true\">Files</button>")
            appendLine(
                "<button type=\"button\" data-srcx-graph-view=\"symbols\" " +
                    "aria-pressed=\"false\">Symbols</button>",
            )
            appendLine(
                "<button type=\"button\" data-srcx-graph-view=\"problems\" " +
                    "aria-pressed=\"false\">Problems</button>",
            )
            appendLine("<button type=\"button\" data-srcx-graph-view=\"cycles\" aria-pressed=\"false\">Cycles</button>")
            appendLine("</div>")
            appendLine("<label class=\"srcx-dashboard__architecture-search\"><span>Find</span>")
            appendLine(
                "<input type=\"search\" data-srcx-graph-search " +
                    "placeholder=\"file, class, function...\"></label>",
            )
            appendLine("<div class=\"srcx-dashboard__architecture-zoom\">")
            appendLine("<button type=\"button\" data-srcx-graph-action=\"zoom-in\" aria-label=\"Zoom in\">+</button>")
            appendLine("<button type=\"button\" data-srcx-graph-action=\"zoom-out\" aria-label=\"Zoom out\">-</button>")
            appendLine("<button type=\"button\" data-srcx-graph-action=\"fit\">Fit</button>")
            appendLine("<button type=\"button\" data-srcx-graph-action=\"reset\">Reset</button>")
            appendLine("</div></div>")
        }

    private fun renderFallback(graph: WorkspaceArchitectureGraphRenderer): String =
        buildString {
            appendLine("<div class=\"srcx-dashboard__architecture-fallback\" data-srcx-graph-fallback>")
            if (graph.fileNodes.isEmpty()) {
                appendLine(renderEmptyGraph())
            } else {
                appendLine("<p><strong>Indexed files.</strong> Enable JavaScript for the interactive map.</p>")
                appendLine("<div class=\"srcx-dashboard__architecture-fallback-files\">")
                graph.fileNodes.forEach { file ->
                    append("<article><strong>${file.name.escapeWorkspaceHtml()}</strong><code>")
                    append(file.path.escapeWorkspaceHtml())
                    append("</code><small>${file.scope.escapeWorkspaceHtml()} / ")
                    append("${file.relationshipCount} relationships")
                    if (file.fileFindingCount > 0) append(" / ${file.fileFindingCount} findings")
                    appendLine("</small></article>")
                }
                appendLine("</div>")
            }
            appendLine("</div>")
        }

    private fun renderEmptyGraph(): String =
        resources.component(
            "empty-state",
            mapOf(
                "title" to "No cumulative relationship map",
                "body" to "No resolved non-import relationship connects indexed workspace files.",
                "mark" to "0",
                "tone" to "neutral",
                "classes" to "srcx-dashboard__architecture-empty",
            ),
        )

    private fun renderDetailPanel(): String =
        buildString {
            appendLine(
                "<aside class=\"srcx-dashboard__architecture-detail\" data-srcx-detail hidden " +
                    "aria-live=\"polite\">",
            )
            appendLine("<header><span data-srcx-detail-kicker>Selection</span>")
            appendLine(
                "<button type=\"button\" data-srcx-detail-close " +
                    "aria-label=\"Close details\">Close</button></header>",
            )
            appendLine("<h3 data-srcx-detail-title>Select a file or relationship</h3>")
            appendLine("<div data-srcx-detail-fields></div>")
            appendLine("</aside>")
        }

    private fun renderEvidence(graph: WorkspaceArchitectureGraphRenderer): String =
        buildString {
            val recordCount = graph.fileEdges.sumOf { it.count }
            appendLine("<details class=\"srcx-disclosure srcx-dashboard__architecture-evidence\">")
            append("<summary>Map limits and evidence / $recordCount resolved records / expand</summary>")
            appendLine("<div class=\"srcx-disclosure__body\">")
            appendLine("<p><code>DIRECT</code> is observed syntax, <code>DERIVED</code> is deterministic resolution, ")
            appendLine("and <code>HEURISTIC</code> is approximate. Import-only facts are excluded.</p>")
            appendLine("<p>The bounded map includes ${graph.fileNodes.size} files and ${graph.nodes.size} symbols. ")
            append("${graph.omittedFileNodeCount} file candidates and ")
            appendLine("${graph.omittedNodeCount} symbol candidates are omitted.</p>")
            appendLine("</div></details>")
        }
}
