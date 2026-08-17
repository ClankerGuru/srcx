package zone.clanker.gradle.srcx.report

private val atlasNodeSamples =
    """
    <li class="srcx-dashboard__architecture-guide-example">
    <div class="srcx-dashboard__architecture-ring-guide" role="group" aria-label="Examples of node marks">
    <strong>On-map examples</strong>
    <span><i class="is-build-fill" aria-hidden="true"></i>Build fill</span>
    <span><i class="is-interface-kind-ring" aria-hidden="true"></i>Interface</span>
    <span><i class="is-abstract-kind-ring" aria-hidden="true"></i>Abstract / sealed</span>
    <span><i class="is-concrete-kind-ring" aria-hidden="true"></i>Concrete / data</span>
    <span><i class="is-object-kind-ring" aria-hidden="true"></i>Object</span>
    <span><i class="is-enum-kind-ring" aria-hidden="true"></i>Enum</span>
    </div></li>
    """.trimIndent() + "\n"

private val atlasReviewSamples =
    """
    <li class="srcx-dashboard__architecture-guide-example">
    <div class="srcx-dashboard__architecture-ring-guide" role="group" aria-label="Examples of review rings">
    <strong>On-map examples</strong>
    <span><i class="is-importance-ring" aria-hidden="true"></i>Priority</span>
    <span><i class="is-finding-ring" aria-hidden="true"></i>Exact finding</span>
    <span><i class="is-cycle-ring" aria-hidden="true"></i>Observed cycle</span>
    <span><i class="is-analysis-cycle-ring" aria-hidden="true"></i>Analyzer-inferred cycle</span>
    <span><i class="is-source-file-finding" aria-hidden="true"></i>Declaring-file finding</span>
    <span><i class="is-node-size" aria-hidden="true"></i>Stronger map signal</span>
    </div></li>
    """.trimIndent() + "\n"

private val atlasRelationshipSamples =
    """
    <li class="srcx-dashboard__architecture-guide-example">
    <div class="srcx-dashboard__architecture-edge-guide" role="group" aria-label="Examples of map routes">
    <strong>On-map examples</strong>
    <span><i class="is-quiet" aria-hidden="true"></i>Hidden at rest</span>
    <span><i class="is-active" aria-hidden="true"><b>3</b></i>Highlighted connection</span>
    <span><i class="is-cross-build" aria-hidden="true"></i>Cross-build</span>
    <span><i class="is-heuristic" aria-hidden="true"></i>Heuristic</span>
    <span><i class="is-cycle" aria-hidden="true"></i>Observed cycle</span>
    <span><i class="is-analysis-cycle" aria-hidden="true"></i>Analyzer-inferred</span>
    </div></li>
    """.trimIndent() + "\n"

/** Renders the cumulative workspace relationship explorer and its static fallback. */
internal class WorkspaceArchitectureHtmlRenderer(
    private val resources: WorkspaceHtmlResourceRenderer,
) {
    fun render(graph: WorkspaceArchitectureGraphRenderer): Map<String, String> =
        mapOf(
            "architectureSummaryHtml" to renderSummary(graph),
            "architectureGraphHtml" to renderGraph(graph),
            "architectureEvidenceHtml" to renderEvidence(graph),
        )

    private fun renderSummary(graph: WorkspaceArchitectureGraphRenderer): String =
        buildString {
            appendLine("<div class=\"srcx-dashboard__architecture-summary\">")
            appendLine("<p><strong>Explore files first.</strong> Arrows point from the file containing a reference to ")
            appendLine(
                "the file it uses. Switch lenses for symbols, exact file findings, observed file cycles, or " +
                    "analyzer-inferred component cycles. ",
            )
            appendLine("Select a node or arrow for source evidence; this is static analysis, not runtime tracing. ")
            appendLine(
                "The All view is a bounded overview; selecting a build or project opens its complete typed " +
                    "Files or Symbols scope in one frame, with every project kept in its own region.</p>",
            )
            appendLine("<dl>")
            appendLine("<div><dt>Files shown</dt><dd>${graph.fileNodes.size}</dd></div>")
            appendLine("<div><dt>File links</dt><dd>${graph.fileEdges.size}</dd></div>")
            appendLine("<div><dt>Symbols in All overview</dt><dd>${graph.nodes.size}</dd></div>")
            appendLine(
                "<div><dt>Observed / inferred cycles</dt><dd>" +
                    "${graph.cycles.size} / ${graph.analysisCycles.size}</dd></div>",
            )
            appendLine("</dl></div>")
        }

    private fun renderGraph(graph: WorkspaceArchitectureGraphRenderer): String =
        buildString {
            val totalFileCandidates = graph.fileNodes.size + graph.omittedFileNodeCount
            appendLine("<section class=\"srcx-dashboard__architecture-graph\" data-srcx-architecture-graph>")
            appendLine("<header class=\"srcx-dashboard__architecture-graph-head\">")
            appendLine("<div class=\"srcx-dashboard__architecture-masthead-copy\">")
            appendLine("<span>Explore files first · arrows from a file to the file it uses</span>")
            appendLine("<strong>Workspace atlas</strong>")
            appendLine("<div class=\"srcx-dashboard__architecture-masthead-status\">")
            appendLine(
                "<p data-srcx-graph-status role=\"status\" aria-live=\"polite\">Files / " +
                    "${graph.fileNodes.size} of $totalFileCandidates relationship or exact-finding file candidates " +
                    "shown / " +
                    "${graph.fileEdges.size} links / " +
                    "${graph.shownRelationshipRecordCount} of ${graph.totalRelationshipRecordCount} relationship " +
                    "records shown from the total workspace set</p>",
            )
            appendLine(
                "<button type=\"button\" class=\"srcx-dashboard__architecture-clear-selected\" " +
                    "data-srcx-clear-selected disabled>Clear selected</button>",
            )
            appendLine("</div></div>")
            appendLine("<div class=\"srcx-dashboard__architecture-masthead-tools\">")
            appendLine("<label class=\"srcx-dashboard__architecture-search\"><span>Find</span>")
            appendLine(
                "<input type=\"search\" data-srcx-graph-search " +
                    "placeholder=\"Search everything, or use class:, method:\"></label>",
            )
            appendLine(
                "<button type=\"button\" class=\"srcx-dashboard__architecture-filter-toggle\" " +
                    "data-srcx-filter-toggle aria-expanded=\"false\" aria-controls=\"srcx-atlas-filters\">" +
                    "Filter</button>",
            )
            appendLine("</div></header>")
            appendLine(
                "<button type=\"button\" class=\"srcx-dashboard__architecture-chrome-toggle\" " +
                    "data-srcx-fullscreen-chrome-toggle aria-expanded=\"true\" " +
                    "aria-label=\"Hide map controls and filters\" hidden>Hide map controls</button>",
            )
            appendLine(renderControls())
            appendLine(renderNavigator())
            appendLine("<div class=\"srcx-dashboard__architecture-viewport\">")
            appendLine(renderMapLegend())
            appendLine(renderFallback(graph))
            appendLine(
                "<svg class=\"srcx-dashboard__architecture-svg\" data-srcx-graph-svg hidden " +
                    "role=\"group\" aria-label=\"Interactive workspace relationship map. " +
                    "Use arrow keys between nodes, R for a connected relationship, and arrow keys between " +
                    "relationships and their endpoints.\"></svg>",
            )
            appendLine(renderDetailPanel())
            appendLine("</div>")
            appendLine(renderAtlasGuide(graph))
            append("<script type=\"application/json\" data-srcx-architecture-data>")
            append(graph.toJson())
            appendLine("</script>")
            appendLine("</section>")
        }

    private fun renderNavigator(): String =
        buildString {
            appendLine(
                "<nav class=\"srcx-dashboard__architecture-navigator\" id=\"srcx-atlas-filters\" " +
                    "data-srcx-graph-navigator hidden " +
                    "aria-label=\"Atlas build, project, and source-set filters\">",
            )
            appendLine("<header class=\"srcx-dashboard__architecture-filter-panel-head\">")
            appendLine("<strong>Current map filters</strong>")
            appendLine(
                "<button type=\"button\" class=\"srcx-dashboard__architecture-filter-close\" " +
                    "data-srcx-filter-close>Close</button></header>",
            )
            appendLine(
                "<div class=\"srcx-dashboard__architecture-filter srcx-dashboard__architecture-filter--builds\">",
            )
            appendLine("<div class=\"srcx-dashboard__architecture-filter-label\"><strong>Builds</strong>")
            appendLine("<span>Ownership scope</span></div>")
            appendLine(
                "<div class=\"srcx-dashboard__architecture-filter-rail\" data-srcx-build-filter " +
                    "role=\"toolbar\" aria-label=\"Filter atlas by build\"></div></div>",
            )
            appendLine(
                "<div class=\"srcx-dashboard__architecture-filter srcx-dashboard__architecture-filter--projects\">",
            )
            appendLine("<div class=\"srcx-dashboard__architecture-filter-label\"><strong>Projects</strong>")
            appendLine("<span>Selected build modules</span></div>")
            appendLine(
                "<div class=\"srcx-dashboard__architecture-filter-rail\" data-srcx-project-filter " +
                    "role=\"toolbar\" aria-label=\"Filter atlas by project\"></div></div>",
            )
            appendLine(
                "<div class=\"srcx-dashboard__architecture-filter srcx-dashboard__architecture-filter--source-sets\">",
            )
            appendLine("<div class=\"srcx-dashboard__architecture-filter-label\"><strong>Source sets</strong>")
            appendLine("<span>Production, test, and custom sources</span></div>")
            appendLine(
                "<div class=\"srcx-dashboard__architecture-filter-rail\" data-srcx-source-set-filter " +
                    "role=\"toolbar\" aria-label=\"Filter atlas by source set\"></div></div>",
            )
            appendLine(
                "<div class=\"srcx-dashboard__architecture-kind-filter\" " +
                    "data-srcx-relationship-kind-filter aria-label=\"Relationship kind filter\">",
            )
            appendLine("<span>Relationship kind</span>")
            appendLine(
                "<div class=\"srcx-dashboard__architecture-kind-filter-rail\" " +
                    "data-srcx-relationship-kind-options role=\"radiogroup\" " +
                    "aria-label=\"Filter map by relationship kind\"></div></div>",
            )
            appendLine(
                "<p class=\"srcx-dashboard__architecture-filter-context\" data-srcx-filter-context " +
                    "role=\"status\" aria-live=\"polite\" aria-atomic=\"true\">" +
                    "All builds / all projects / all source sets</p>",
            )
            appendLine("</nav>")
        }

    private fun renderMapLegend(): String =
        """
        <aside class="srcx-dashboard__architecture-legend srcx-dashboard__architecture-legend--atlas" data-srcx-map-legend>
        <strong>Edges</strong>
        <span><i class="is-arrow" aria-hidden="true"></i>Call</span>
        <span><i class="is-heuristic-line" aria-hidden="true"></i>Type / heuristic</span>
        <span><i class="is-cross-build-line" aria-hidden="true"></i>Cross-build</span>
        <strong>Rings / size</strong>
        <span><i class="is-importance-ring" aria-hidden="true"></i>Core = file</span>
        <span><i class="is-finding-ring" aria-hidden="true"></i>Red = finding</span>
        <span><i class="is-cycle-ring" aria-hidden="true"></i>Cycle</span>
        </aside>
        """.trimIndent() + "\n"

    private fun renderAtlasGuide(graph: WorkspaceArchitectureGraphRenderer): String =
        buildString {
            appendLine("<details class=\"srcx-dashboard__architecture-guide\" data-srcx-atlas-guide>")
            appendLine("<summary>How to read this map</summary>")
            appendLine("<div class=\"srcx-dashboard__architecture-guide-sections\">")
            append(renderAtlasNodeGuide())
            append(renderAtlasReviewGuide())
            append(renderAtlasRelationshipGuide())
            append(renderAtlasBuildGuide(graph))
            appendLine("</div></details>")
        }

    private fun renderAtlasNodeGuide(): String =
        buildString {
            appendLine("<section><h4>Node types</h4><ul>")
            append(atlasNodeSamples)
            appendLine(
                "<li><strong>Build-colored fill</strong> is a team jersey: it identifies Gradle-build ownership, " +
                    "not severity, quality, or risk.</li>",
            )
            appendLine(
                "<li><strong>The colored square beside a symbol name</strong> identifies an interface, " +
                    "abstract/sealed class, concrete/data class, object, or enum. A Kotlin object is a " +
                    "language-level singleton instance; the square does not infer architectural intent.</li>",
            )
            appendLine(
                "<li><strong>Size and opacity form a map-signal hierarchy.</strong> Relationship-record volume " +
                    "drives the scale, while exact findings, cycles, and report-priority signals keep review " +
                    "targets prominent. Tiny faint nodes are still real files or declarations; they have low " +
                    "signal in this frame, and hover or keyboard focus reveals their identity.</li>",
            )
            appendLine(
                "<li><strong>Nested dotted boxes are automatic source areas.</strong> Inside each project, the map " +
                    "groups nodes by source set and the first meaningful directory after their shared package " +
                    "prefix. They add visual orientation only; they are not a directory filter or a parsed " +
                    "package claim.</li></ul></section>",
            )
        }

    private fun renderAtlasReviewGuide(): String =
        buildString {
            appendLine("<section><h4>Review signals</h4><ul>")
            append(atlasReviewSamples)
            appendLine(
                "<li><strong>Dark priority mark</strong> means report ranking signals included the node; it is " +
                    "not a quality or risk verdict.</li>",
            )
            appendLine(
                "<li><strong>Red finding mark</strong> is an exact static-analysis finding attached to a file. " +
                    "Open its evidence before deciding what to change.</li>",
            )
            appendLine(
                "<li><strong>Yellow diamond beside a labeled symbol</strong> means its declaring file has exact " +
                    "findings. Those findings are nearby file-level review context, not automatically attributed " +
                    "to that declaration.</li>",
            )
            appendLine(
                "<li><strong>Red dashed cycle</strong> is an observed file cycle in resolved relationships. " +
                    "A <strong>violet dotted cycle</strong> is separately analyzer-inferred component-cycle " +
                    "evidence and is not counted as a resolved relationship.</li></ul></section>",
            )
        }

    private fun renderAtlasRelationshipGuide(): String =
        buildString {
            appendLine("<section><h4>Relationships</h4><ul>")
            append(atlasRelationshipSamples)
            appendLine(
                "<li><strong>Arrow direction</strong> works like the address on a letter: sender/consumer source " +
                    "points to the destination/dependency declaration it uses.</li>",
            )
            appendLine(
                "<li><strong>Routes are hidden at rest.</strong> Hover, focus, or select a node to draw only its " +
                    "connected curved routes and reveal their exact count badges. Select an arrow to isolate " +
                    "one relationship and its source evidence.</li>",
            )
            appendLine(
                "<li><strong>Cross-build line</strong> is a phone call leaving one building for another: source " +
                    "in one Gradle build references a declaration in another. It does not prove overall tight " +
                    "coupling or runtime traffic.</li>",
            )
            appendLine(
                "<li><strong>Heavier arrow and count badge</strong> are a stack of evidence cards: more displayed " +
                    "records, not calls, users, executions, or IDE usages.</li>",
            )
            appendLine(
                "<li><strong>Call / construct records</strong> combine resolved CALL and CONSTRUCTOR source " +
                    "records. They are not unique callers or runtime executions, and one source line can yield " +
                    "multiple records.</li>",
            )
            appendLine(
                "<li><strong>Imports are excluded</strong> from current Atlas relationship counts and arrows. " +
                    "Files aggregate resolved records between displayed files; Symbols draw exact displayed " +
                    "declaration endpoints.</li>",
            )
            appendLine(
                "<li><strong>Direct evidence</strong> is a resolved source relationship. <strong>Derived " +
                    "evidence</strong> means SRCX deterministically composed source facts to resolve the target, " +
                    "such as using one unambiguous explicit import to resolve a reference. A <strong>heuristic " +
                    "dotted line</strong> is a pencil-dotted best-supported resolver link, not compiler proof." +
                    "</li></ul></section>",
            )
            appendLine("<section><h4>Source evidence</h4><ul>")
            appendLine(
                "<li><strong>One relationship record</strong> is an addressed evidence card: source declaration " +
                    "(or the owning build/project/source-set/file for an import) &rarr; target declaration at one " +
                    "source line. Duplicate cards with that same address collapse to one; SRCX keeps the most-" +
                    "specific relationship kind, then the strongest evidence.</li>",
            )
            appendLine(
                "<li><strong>Embedded source</strong> is the full typed source-file pool supplied by the immutable " +
                    "workspace report. The HTML renderer does not perform arbitrary filesystem reads.</li>",
            )
            appendLine(
                "<li><strong>Source marks</strong> work like map pins: declaration lines keep a subtle location " +
                    "mark, and selecting a relationship lightly marks each occurrence line for that edge. Only " +
                    "the active declaration, relationship occurrence, or finding receives the strong highlight." +
                    "</li></ul></section>",
            )
        }

    private fun renderAtlasBuildGuide(graph: WorkspaceArchitectureGraphRenderer): String =
        buildString {
            appendLine("<section><h4>Build ownership</h4>")
            appendLine("<p>Colors are deterministic ownership jerseys and carry no severity meaning.</p>")
            appendLine("<ul class=\"srcx-dashboard__architecture-guide-builds\">")
            graph.builds.forEach { build ->
                append("<li><i style=\"--srcx-build-color:")
                append(build.color.escapeWorkspaceHtml())
                append("\"></i><strong>")
                append(build.name.escapeWorkspaceHtml())
                append("</strong><span>")
                append(build.context.escapeWorkspaceHtml())
                appendLine("</span></li>")
            }
            appendLine("</ul></section>")
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
            appendLine("<div class=\"srcx-dashboard__architecture-search-recovery\" data-srcx-search-recovery hidden>")
            appendLine(
                "<span data-srcx-search-recovery-status role=\"status\" " +
                    "aria-live=\"polite\" aria-atomic=\"true\"></span>",
            )
            appendLine("<button type=\"button\" data-srcx-search-recovery-action></button></div>")
            appendLine("<div class=\"srcx-dashboard__architecture-zoom\">")
            appendLine("<button type=\"button\" data-srcx-graph-action=\"zoom-in\" aria-label=\"Zoom in\">+</button>")
            appendLine("<button type=\"button\" data-srcx-graph-action=\"zoom-out\" aria-label=\"Zoom out\">-</button>")
            appendLine("<button type=\"button\" data-srcx-graph-action=\"fit\">Fit</button>")
            appendLine("<button type=\"button\" data-srcx-graph-action=\"reset\">Reset</button>")
            appendLine(
                "<button type=\"button\" data-srcx-graph-fullscreen " +
                    "aria-pressed=\"false\">Full screen</button>",
            )
            appendLine("</div></div>")
        }

    private fun renderFallback(graph: WorkspaceArchitectureGraphRenderer): String =
        buildString {
            appendLine("<div class=\"srcx-dashboard__architecture-fallback\" data-srcx-graph-fallback>")
            if (graph.fileNodes.isEmpty()) {
                appendLine(renderEmptyGraph())
            } else {
                appendLine(
                    "<p><strong>Bounded relationship or exact-finding files.</strong> Outbound records start in " +
                        "the file, inbound records end at declarations in it, and internal records stay within it. " +
                        "This is not every indexed workspace file. The interactive map loads when bundled D3 and " +
                        "browser scripting are available.</p>",
                )
                appendLine("<div class=\"srcx-dashboard__architecture-fallback-files\">")
                graph.fileNodes.forEach { file ->
                    append("<article><strong>${file.name.escapeWorkspaceHtml()}</strong><code>")
                    append(file.path.escapeWorkspaceHtml())
                    append("</code><small>${file.scope.escapeWorkspaceHtml()} / ")
                    append("${file.relationshipRecordCount} relationship records")
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
                "<div class=\"srcx-dashboard__architecture-detail-resize\" data-srcx-detail-resize hidden " +
                    "role=\"separator\" aria-label=\"Resize source details\" aria-orientation=\"vertical\" " +
                    "aria-valuemin=\"0\" aria-valuemax=\"50\" aria-valuenow=\"44\" " +
                    "aria-valuetext=\"44 percent of map width\" tabindex=\"0\"></div>",
            )
            appendLine(
                "<aside class=\"srcx-dashboard__architecture-detail\" data-srcx-detail hidden " +
                    "role=\"region\" aria-label=\"Architecture selection evidence\" " +
                    "tabindex=\"-1\">",
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
            appendLine("<details class=\"srcx-disclosure srcx-dashboard__architecture-evidence\">")
            append(
                "<summary>Map limits and evidence / ${graph.totalRelationshipRecordCount} relationship records / " +
                    "expand</summary>",
            )
            appendLine("<div class=\"srcx-disclosure__body\">")
            appendLine("<p><code>DIRECT</code> is observed syntax, <code>DERIVED</code> is deterministic resolution, ")
            appendLine("and <code>HEURISTIC</code> is approximate. Import-only facts are excluded.</p>")
            appendLine(
                "<p>The bounded relationship-connected projection includes ${graph.fileNodes.size} files and " +
                    "${graph.nodes.size} symbols; it is not an inventory of every indexed file. ",
            )
            append("${graph.omittedFileNodeCount} file candidates and ")
            append("${graph.omittedNodeCount} symbol candidates are omitted. ")
            appendLine(
                "${graph.shownRelationshipRecordCount} of ${graph.totalRelationshipRecordCount} relationship " +
                    "records are visible in the bounded file projection.</p>",
            )
            appendLine("</div></details>")
        }
}
