package zone.clanker.gradle.srcx.report

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import zone.clanker.gradle.srcx.model.AnalysisSummary
import zone.clanker.gradle.srcx.model.ArchitectureComponent
import zone.clanker.gradle.srcx.model.ArchitectureComponentCycle
import zone.clanker.gradle.srcx.model.ArchitectureDependency
import zone.clanker.gradle.srcx.model.ArchitectureEntryPoint
import zone.clanker.gradle.srcx.model.ArchitectureEntryPointKind
import zone.clanker.gradle.srcx.model.ArchitectureLayer
import zone.clanker.gradle.srcx.model.ArchitectureSummary
import zone.clanker.gradle.srcx.model.ArtifactGroup
import zone.clanker.gradle.srcx.model.ArtifactName
import zone.clanker.gradle.srcx.model.ArtifactVersion
import zone.clanker.gradle.srcx.model.BuildEdge
import zone.clanker.gradle.srcx.model.DependencyEntry
import zone.clanker.gradle.srcx.model.EntryPointKind
import zone.clanker.gradle.srcx.model.EntryPointSummary
import zone.clanker.gradle.srcx.model.FilePath
import zone.clanker.gradle.srcx.model.Finding
import zone.clanker.gradle.srcx.model.FindingSeverity
import zone.clanker.gradle.srcx.model.HubClass
import zone.clanker.gradle.srcx.model.HubDependentRef
import zone.clanker.gradle.srcx.model.ImportantSymbol
import zone.clanker.gradle.srcx.model.ImportantSymbolReason
import zone.clanker.gradle.srcx.model.IncludedBuildSummary
import zone.clanker.gradle.srcx.model.InterfaceSummary
import zone.clanker.gradle.srcx.model.PackageName
import zone.clanker.gradle.srcx.model.ProjectPath
import zone.clanker.gradle.srcx.model.ProjectSummary
import zone.clanker.gradle.srcx.model.ReferenceEvidence
import zone.clanker.gradle.srcx.model.ReferenceKind
import zone.clanker.gradle.srcx.model.SourceSetName
import zone.clanker.gradle.srcx.model.SourceSetSummary
import zone.clanker.gradle.srcx.model.SymbolDetailKind
import zone.clanker.gradle.srcx.model.SymbolEntry
import zone.clanker.gradle.srcx.model.SymbolKind
import zone.clanker.gradle.srcx.model.SymbolName
import zone.clanker.gradle.srcx.model.WorkspaceIndex
import zone.clanker.gradle.srcx.model.WorkspaceReference
import zone.clanker.gradle.srcx.model.WorkspaceRelationship
import zone.clanker.gradle.srcx.model.WorkspaceRelationshipKind
import zone.clanker.gradle.srcx.model.WorkspaceReport
import zone.clanker.gradle.srcx.model.WorkspaceSourceFile
import zone.clanker.gradle.srcx.model.WorkspaceSymbol
import zone.clanker.gradle.srcx.model.WorkspaceSymbolUsage

@Suppress("LargeClass")
class WorkspaceHtmlRendererTest :
    BehaviorSpec({
        given("a full typed workspace report") {
            `when`("it is rendered") {
                val report = fullWorkspaceReport()
                val rendered = WorkspaceHtmlRenderer().render(report)
                val article =
                    rendered.fragment
                        .substringAfter("</style>")
                        .substringBefore("<script data-srcx-vendor")

                then("the embedded fragment contains scoped inline report styles") {
                    rendered.fragment shouldStartWith "<style data-srcx-theme=\"gort\">"
                    rendered.fragment shouldContain ".srcx-theme"
                    rendered.fragment shouldContain ".srcx-dashboard"
                    rendered.fragment shouldContain "<article class=\"srcx-theme srcx-page srcx-dashboard\""
                    rendered.fragment shouldNotContain "<html"
                }

                then("the standalone result is a self-contained HTML5 document") {
                    rendered.document shouldStartWith "<!doctype html>"
                    rendered.document shouldContain "<html lang=\"en\">"
                    rendered.document shouldContain "<meta charset=\"utf-8\">"
                    rendered.document shouldContain "<title>render-lab SRCX source documentation</title>"
                    rendered.document shouldContain rendered.fragment
                    rendered.document shouldNotContain "<link"
                    rendered.document shouldNotContain "<script src="
                    rendered.document shouldNotContain "cdn.jsdelivr"
                    rendered.document shouldContain "<script data-srcx-vendor=\"d3-7.9.0\">"
                    rendered.document shouldContain "<script data-srcx-owned=\"architecture-graph\">"
                    rendered.document shouldContain "data-srcx-architecture-data"
                }

                then("the frame explains the report in workspace terms") {
                    article shouldContain "render-lab"
                    article shouldContain "<strong>4</strong>"
                    article shouldContain "<span>Symbols</span>"
                    article shouldContain "<span>Projects</span>"
                    article shouldContain "Workspace architecture / source evidence"
                    article shouldContain "Scan complete"
                    article shouldContain "SRCX / Multi-build architecture report"
                    article shouldNotContain "Workspace <strong>atlas.</strong>"
                    article shouldNotContain "<h1>"
                    article shouldNotContain "Explore files first ·"
                    article shouldContain "source relationships"
                    article shouldContain "which build owns each file"
                    article shouldContain "which files reference one another"
                    article shouldContain "where each finding came from"
                    article shouldContain "Workspace scan"
                    article shouldContain "Root + included builds"
                    article shouldContain "Evidence"
                    article shouldContain "Resolved source relationships"
                    article shouldContain "Build + project scoped"
                    article shouldContain "Built from the workspace scan and resolved source evidence"
                }

                then("the build atlas shows root-first workspace membership in one matrix") {
                    assertBuildAtlas(article)
                }

                then("one build matrix prints exact values on independent column scales") {
                    assertBuildCharts(article)
                }

                then("finding and source-set matrix cells retain stable composition") {
                    assertBuildChartComposition(article)
                }

                then("build comparison stays compact while findings use button filters") {
                    assertBuildComparisonMarkup(article)
                }

                then("observed build dependencies use directed graphical routes") {
                    assertDirectedBuildDependencies(article)
                }

                then("ownership, source coverage, and production hubs are direct model views") {
                    article shouldContain "Symbol ownership"
                    article shouldContain "<b>render-lab</b><small>3 symbols / 75.0%</small>"
                    article shouldContain "<b>library-build</b><small>1 symbol / 25.0%</small>"
                    article shouldContain
                        "style=\"--symbol-count: 3; --srcx-build-color: ${workspaceBuildColor("render-lab")}\" " +
                        "aria-label=\"render-lab: 3 symbols, 75.0 percent\"></span>"
                    article shouldContain
                        "style=\"--srcx-build-color: ${workspaceBuildColor("library-build")}\"></i>" +
                        "<b>library-build</b>"
                    article shouldContain "\"color\":\"${workspaceBuildColor("render-lab")}\""
                    article shouldNotContain "data-value="
                    article shouldContain "<strong>2 hubs / expand</strong>"
                    article shouldContain
                        "<details class=\"srcx-dashboard__chart srcx-dashboard__chart--hubs " +
                        "srcx-dashboard__hub-chart\">"
                    article shouldNotContain "srcx-dashboard__hub-chart\" open"
                    article shouldContain "srcx-dashboard__hub-detail"
                    article shouldContain "RuntimeHub"
                    article shouldContain "RuntimeConsumer"
                    article shouldContain "src/main/RuntimeHub.kt:12"
                    article shouldContain "NoDetailHub"
                    article shouldNotContain "RuntimeHubTest"
                    article shouldContain "Project analyses"
                }

                then("findings retain their actual build and project scopes") {
                    article shouldContain
                        "<details class=\"srcx-disclosure srcx-dashboard__finding-scope\" " +
                        "data-srcx-finding-scope data-srcx-finding-build=\"render-lab\" " +
                        "data-srcx-finding-project=\":app\">"
                    article shouldNotContain "srcx-dashboard__finding-scope\" open"
                    article shouldContain "<strong>3 findings</strong><small>main, test</small>"
                    article shouldContain "<strong>1 finding</strong><small>main</small>"
                    article shouldContain "Forbidden root package"
                    article shouldContain "Review codec boundary"
                    article shouldContain "srcx-dashboard__finding-row"
                    article shouldNotContain "srcx-dashboard__finding-number"
                    article shouldNotContain "srcx-dashboard__finding-stamp"
                    article shouldNotContain "srcx-dashboard__finding-story"
                }

                then("plain project prompts do not claim Atlas evidence") {
                    val finding =
                        report.rootProjects
                            .single { project -> project.projectPath.value == ":app" }
                            .analysis
                            ?.findings
                            ?.single { item -> item.message == "Forbidden root package" }
                    val findingId =
                        workspaceArchitectureFindingIds(report).getValue(
                            WorkspaceArchitectureFindingKeyRenderer("render-lab", ":app", requireNotNull(finding)),
                        )

                    Regex("data-srcx-finding-id=\"$findingId\"").findAll(article).count() shouldBe 1
                    article shouldNotContain "data-srcx-open-finding data-srcx-finding-id=\"$findingId\""
                    Regex(
                        "Project-scoped review prompt; no exact file or declaration evidence was supplied\\.",
                    ).findAll(article).count() shouldBe 2
                }

                then("exact findings deep-link through one stable Atlas finding ID") {
                    val finding =
                        report.rootProjects
                            .single { project -> project.projectPath.value == ":app" }
                            .analysis
                            ?.findings
                            ?.single { item -> item.message == "Oversized runtime service" }
                    val findingId =
                        workspaceArchitectureFindingIds(report).getValue(
                            WorkspaceArchitectureFindingKeyRenderer("render-lab", ":app", requireNotNull(finding)),
                        )

                    article shouldContain
                        "data-srcx-open-finding data-srcx-finding-id=\"$findingId\">" +
                        "Show exact finding in Problems map</button>"
                    Regex("data-srcx-open-finding data-srcx-finding-id=\"$findingId\"")
                        .findAll(article)
                        .count() shouldBe 1
                    article shouldContain "src/main/kotlin/com/example/domain/RuntimeService.kt:21"
                    article shouldContain "\"id\":\"$findingId\""
                    article shouldContain "\"componentIds\":[\"com.example.domain.RuntimeService\"]"
                    article shouldContain "\"componentSymbolIds\":[\"render-lab:::app::main::"
                    article shouldContain
                        "\"componentFileIds\":[\"file::render-lab:::app::main::" +
                        "src/main/kotlin/com/example/domain/RuntimeService.kt\"]"
                }

                then("project-scoped analyzer cycles deep-link through their linked finding ID") {
                    val finding =
                        report.rootProjects
                            .single { project -> project.projectPath.value == ":app" }
                            .analysis
                            ?.findings
                            ?.single { item -> item.message == "Runtime has no contract" }
                    val findingId =
                        workspaceArchitectureFindingIds(report).getValue(
                            WorkspaceArchitectureFindingKeyRenderer("render-lab", ":app", requireNotNull(finding)),
                        )

                    article shouldContain
                        "data-srcx-open-finding data-srcx-finding-id=\"$findingId\">" +
                        "Open analyzer cycle evidence</button>"
                    Regex("data-srcx-open-finding data-srcx-finding-id=\"$findingId\"")
                        .findAll(article)
                        .count() shouldBe 1
                    article shouldContain "\"analysisCycles\":[{\"id\":\"analysis-cycle-1\""
                    article shouldContain "\"findingIds\":[\"$findingId\"]"
                    article shouldContain "\"analysisCycleId\":\"analysis-cycle-1\""
                    article shouldContain "\"evidence\":\"ANALYZER_INFERRED\""
                }

                then("build edges and the cumulative workspace graph are rendered") {
                    val articleTag = article.substringAfter("<article").substringBefore(">")
                    article shouldContain "Explore files first."
                    article shouldContain
                        "Switch lenses for symbols, exact file findings, observed file cycles, or " +
                        "analyzer-inferred component cycles."
                    article shouldContain "data-srcx-graph-view=\"files\" aria-pressed=\"true\""
                    article shouldContain "data-srcx-graph-view=\"symbols\""
                    article shouldContain "data-srcx-graph-view=\"problems\""
                    article shouldContain "data-srcx-graph-view=\"cycles\""
                    article shouldContain "data-srcx-graph-search"
                    article shouldContain "data-srcx-clear-selected"
                    article shouldNotContain "data-srcx-filter-toggle"
                    article shouldContain "data-srcx-map-legend"
                    article shouldContain "srcx-dashboard__report-shell"
                    article shouldContain
                        "data-srcx-relationship-kind-filter aria-label=\"Relationship kind filter\""
                    article shouldContain
                        "data-srcx-relationship-kind-options role=\"radiogroup\" " +
                        "aria-label=\"Filter map by relationship kind\""
                    article shouldContain "data-srcx-search-recovery hidden"
                    article shouldContain
                        "data-srcx-search-recovery-status role=\"status\" " +
                        "aria-live=\"polite\" aria-atomic=\"true\""
                    article shouldContain "data-srcx-search-recovery-action"
                    article shouldContain "data-srcx-graph-action=\"zoom-in\""
                    article shouldContain "data-srcx-graph-action=\"zoom-out\""
                    article shouldContain "data-srcx-graph-action=\"fit\""
                    article shouldContain "data-srcx-graph-action=\"reset\""
                    articleTag shouldNotContain "data-srcx-color-theme"
                    articleTag shouldNotContain "data-srcx-theme-selector"
                    articleTag shouldNotContain "data-srcx-theme-choice"
                    article shouldContain
                        "data-srcx-graph-fullscreen aria-pressed=\"false\">Full screen</button>"
                    article shouldContain
                        "data-srcx-fullscreen-chrome-toggle aria-expanded=\"true\" " +
                        "aria-label=\"Hide map controls and filters\" hidden>Hide map controls</button>"
                    article shouldContain
                        "data-srcx-graph-navigator " +
                        "aria-label=\"Atlas build, project, and source-set filters\""
                    article shouldNotContain "data-srcx-graph-navigator hidden"
                    article shouldContain
                        "srcx-dashboard__architecture-filter srcx-dashboard__architecture-filter--builds"
                    article shouldContain
                        "srcx-dashboard__architecture-filter srcx-dashboard__architecture-filter--projects\" hidden"
                    article shouldContain
                        "srcx-dashboard__architecture-filter srcx-dashboard__architecture-filter--source-sets\" hidden"
                    article shouldContain
                        "srcx-dashboard__architecture-kind-filter\" hidden"
                    article shouldContain
                        "data-srcx-build-filter role=\"toolbar\" aria-label=\"Filter atlas by build\""
                    article shouldContain
                        "data-srcx-project-filter role=\"toolbar\" aria-label=\"Filter atlas by project\""
                    article shouldContain
                        "data-srcx-source-set-filter role=\"toolbar\" aria-label=\"Filter atlas by source set\""
                    article shouldNotContain "data-srcx-path-filter"
                    article shouldNotContain "Packages / directories"
                    article shouldContain
                        "data-srcx-filter-context role=\"status\" aria-live=\"polite\" aria-atomic=\"true\""
                    article shouldContain "All builds / all projects / all source sets"
                    article shouldNotContain "srcx-dashboard__architecture-filter-panel-head"
                    article shouldNotContain "data-srcx-filter-close"
                    article shouldContain "srcx-dashboard__report-shell"
                    article shouldContain "01 Architecture / 02 Builds / 03 Health / 04 Findings"
                    (
                        article.indexOf("data-srcx-architecture-graph") in
                            0 until article.indexOf("class=\"srcx-dashboard__hero\"")
                    ) shouldBe true
                    article shouldContain "data-srcx-atlas-guide"
                    article shouldContain "How to read this map"
                    article shouldContain "The colored square beside a symbol name"
                    article shouldContain "aria-label=\"Examples of node marks\""
                    article shouldContain "aria-label=\"Examples of review rings\""
                    article shouldContain "aria-label=\"Examples of map routes\""
                    article shouldContain "Routes are hidden at rest."
                    article shouldContain "Size and opacity form a map-signal hierarchy."
                    article shouldContain "Nested dotted boxes are automatic source areas."
                    article shouldContain "Declaring-file finding"
                    article shouldContain "Yellow diamond beside a labeled symbol"
                    article shouldContain "not automatically attributed to that declaration"
                    article shouldContain "Stronger map signal"
                    article shouldContain "Hidden at rest"
                    article shouldContain "Highlighted connection"
                    article shouldContain "team jersey"
                    article shouldContain "phone call leaving one building for another"
                    article shouldContain "address on a letter"
                    article shouldContain "stack of evidence cards"
                    article shouldContain "pencil-dotted best-supported resolver link"
                    article shouldContain "Imports are excluded"
                    article shouldContain "Call / construct records"
                    article shouldContain "not severity, quality, or risk"
                    article shouldContain "relationship records"
                    article shouldContain "more displayed records, not calls, users, executions, or IDE usages"
                    article shouldContain "Root build"
                    article shouldContain "<code>../library</code>"
                    article shouldContain
                        "data-srcx-graph-status role=\"status\" aria-live=\"polite\""
                    article shouldContain
                        "Files / 3 of 3 relationship or exact-finding file candidates shown / 3 links / " +
                        "4 of 4 relationship records shown from the total workspace set"
                    article shouldContain
                        "data-srcx-detail hidden role=\"region\" " +
                        "aria-label=\"Architecture selection evidence\" tabindex=\"-1\""
                    article shouldContain "data-srcx-detail-resize"
                    article shouldContain "role=\"separator\""
                    article shouldContain "aria-orientation=\"vertical\""
                    article shouldContain "tabindex=\"0\""
                    article shouldContain "aria-valuemin="
                    article shouldContain "aria-valuemax="
                    article shouldContain "aria-valuenow="
                    article shouldContain "aria-valuetext="
                    article shouldContain "data-srcx-detail-close"
                    article shouldContain "Select a node or arrow for source evidence"
                    article shouldContain "bounded relationship-connected projection"
                    article shouldContain "data-srcx-architecture-graph"
                    article shouldContain "Map limits and evidence / 4 relationship records / expand"
                    article shouldContain "ApplicationMain"
                    article shouldContain "RuntimeService"
                    article shouldContain "RuntimeRepository"
                    article shouldContain "ApplicationMain.kt"
                    article shouldContain "RuntimeService.kt"
                    article shouldContain "RuntimeRepository.kt"
                    article shouldContain "render-lab:::app::main"
                    article shouldContain "library-build:::codec::main"
                    article shouldContain "\"label\":\"call record\", \"evidence\":\"DIRECT\""
                    article shouldContain "\"label\":\"uses type x2\", \"evidence\":\"DERIVED\""
                    article shouldContain "\"evidence\":\"HEURISTIC\""
                    article shouldContain "\"crossBuild\":true"
                    article shouldContain "\"context\":\"Root build\""
                    article shouldContain "\"context\":\"Included build\""
                    article shouldContain "\"totalRelationshipRecordCount\":4"
                    article shouldContain "\"shownRelationshipRecordCount\":4"
                    article shouldContain "\"shownSymbolRelationshipRecordCount\":4"
                    article shouldContain "\"recordCount\":1"
                    article shouldContain "\"defaultView\":\"files\""
                    article shouldContain "\"fileNodes\""
                    article shouldContain "\"fileEdges\""
                    article shouldContain "\"findings\""
                    article shouldNotContain "Raw edge evidence"
                    article shouldNotContain "Source and relationship evidence"
                    article shouldNotContain "colored enclosure"
                    article shouldNotContain "Dependency paths"
                    article shouldNotContain "Boundary direction"
                    article shouldNotContain "Coupling by package boundary"
                    article shouldNotContain "Declares main()"
                    article shouldNotContain "Application entry points /"
                    article shouldNotContain "ApplicationTest"
                    article shouldNotContain "FakeClock"
                }

                then("the static fallback defines relationship-record directions") {
                    article shouldContain "Bounded relationship or exact-finding files."
                    article shouldContain "This is not every indexed workspace file."
                    article shouldContain "Outbound records start in the file"
                    article shouldContain "inbound records end at declarations in it"
                    article shouldContain "internal records stay within it"
                    article shouldContain "relationship records</small>"
                }

                then("renderer diagnostics are not presented as dashboard content") {
                    article shouldNotContain "Model provenance"
                    article shouldNotContain "WorkspaceReport field coverage"
                    article shouldNotContain "Direct model provenance"
                    article shouldNotContain "id=\"model\""
                    article shouldNotContain "WorkspaceReport / typed HTML"
                    article shouldNotContain "Direct model"
                    article shouldNotContain "direct typed model"
                    article shouldNotContain "Source model"
                    article shouldNotContain "rendered directly from the typed WorkspaceReport"
                    article shouldNotContain "Typed source"
                    article shouldNotContain "SRCX WorkspaceReport"
                    article shouldNotContain "Direct render"
                    article shouldNotContain "WorkspaceReport"
                    article shouldNotContain "extract an interface"
                    article shouldNotContain "use the concrete class"
                    rendered.fragment shouldNotContain "WorkspaceReport"
                    rendered.fragment shouldNotContain "Typed source"
                    rendered.fragment shouldNotContain "Direct render"
                    rendered.fragment shouldNotContain "Direct model"
                }

                then("the compact sections follow the dashboard sequence and findings finish the content") {
                    val architecture = article.indexOf("id=\"architecture\"")
                    val builds = article.indexOf("id=\"builds\"")
                    val health = article.indexOf("id=\"health\"")
                    val findings = article.indexOf("id=\"findings\"")
                    val footer = article.indexOf("<footer class=\"srcx-dashboard__footer\">")

                    (architecture in 0 until builds) shouldBe true
                    (builds in 0 until health) shouldBe true
                    (health in 0 until findings) shouldBe true
                    (findings in 0 until footer) shouldBe true
                }

                then("dashboard finding totals come from project analyses") {
                    val reportWithoutAggregateFindings =
                        fullWorkspaceReport().copy(
                            aggregateAnalysis = AnalysisSummary(emptyList(), emptyList(), emptyList()),
                        )
                    val scopedArticle =
                        WorkspaceHtmlRenderer()
                            .render(reportWithoutAggregateFindings)
                            .fragment
                            .substringAfter("</style>")

                    scopedArticle shouldContain "<strong>4</strong>\n    <span>Findings</span>"
                    scopedArticle shouldContain "<div><dt>Total</dt><dd>4</dd></div>"
                    scopedArticle shouldContain "<strong>3 findings</strong><small>main, test</small>"
                    scopedArticle shouldContain "<strong>1 finding</strong><small>main</small>"
                }

                then("the adapted dashboard makes no sibling-output or repository claims") {
                    val lowercaseArticle = article.lowercase()
                    lowercaseArticle shouldNotContain "markdown"
                    lowercaseArticle shouldNotContain "anti-patterns.md"
                    lowercaseArticle shouldNotContain "interfaces.md"
                    lowercaseArticle shouldNotContain "component inventory"
                    lowercaseArticle shouldNotContain "release"
                    lowercaseArticle shouldNotContain "git"
                    lowercaseArticle shouldNotContain "wrkx"
                }

                then("all resource template slots are resolved") {
                    rendered.fragment shouldNotContain "{{"
                    rendered.document shouldNotContain "{{"
                }

                then("the map-first chrome keeps filters off the hero and exposes FILTER controls") {
                    val styles = rendered.fragment.substringBefore("</style>")
                    val script =
                        WorkspaceHtmlResourceRenderer.readClasspathResource(
                            WorkspaceHtmlResourceRenderer.ARCHITECTURE_GRAPH_SCRIPT,
                        )
                    rendered.document shouldContain "class=\"srcx-atlas-document\""
                    article.indexOf("data-srcx-architecture-graph") shouldBe
                        article.indexOf("data-srcx-architecture-graph").coerceAtMost(
                            article.indexOf("srcx-dashboard__hero"),
                        )
                    (article.indexOf("data-srcx-architecture-graph") < article.indexOf("srcx-dashboard__hero")) shouldBe
                        true
                    styles shouldContain "srcx-dashboard__report-shell"
                    styles shouldNotContain "data-srcx-filters-open"
                    script shouldContain "function setFiltersOpen(open)"
                    script shouldContain "function wireClearSelected()"
                    script shouldContain "root.querySelector(\"[data-srcx-graph-search]\")"
                    script shouldContain "root.querySelector(\"[data-srcx-clear-selected]\")"
                    script shouldContain "srcx-dashboard__architecture-svg-node-core"
                }
            }
        }

        given("an empty typed workspace report") {
            `when`("it is rendered") {
                val rendered = WorkspaceHtmlRenderer().render(emptyWorkspaceReport())

                then("the renderer returns complete empty-state documentation") {
                    rendered.fragment shouldContain "empty-workspace"
                    rendered.fragment shouldContain "data-srcx-atlas-state=\"empty\""
                    rendered.fragment shouldContain "No symbols"
                    rendered.fragment shouldContain "No production hubs"
                    rendered.fragment shouldContain "No scoped findings"
                    rendered.fragment shouldContain "Index a build"
                    rendered.fragment shouldContain
                        "Add a Kotlin/Gradle build to this workspace and run srcx-context."
                    rendered.fragment shouldNotContain
                        "No resolved non-import relationship connects indexed workspace files."
                    rendered.fragment shouldContain "Map limits and evidence / 0 relationship records / expand"
                    rendered.fragment shouldNotContain "Raw edge evidence"
                    rendered.fragment shouldNotContain "WorkspaceReport field coverage"
                }

                then("zero-valued metrics and a root build remain visible") {
                    rendered.fragment shouldContain "<strong>0</strong>"
                    rendered.fragment shouldContain "<span>Symbols</span>"
                    rendered.fragment shouldContain "Root build"
                    rendered.fragment shouldContain "<span>Projects</span>"
                }

                then("missing build edges retain the evidence boundary") {
                    rendered.fragment shouldContain "data-srcx-build-edge-empty"
                    rendered.fragment shouldContain "No observed active-build dependency records"
                    rendered.fragment shouldContain
                        "resolved non-import source evidence crosses build scopes or an"
                    rendered.fragment shouldContain
                        "artifact dependency matches another active build"
                    rendered.fragment shouldContain "Absence does not prove independence."
                    rendered.fragment shouldNotContain "None observed"
                }

                then("no template slot leaks into either output") {
                    rendered.fragment shouldNotContain "{{"
                    rendered.document shouldNotContain "{{"
                }
            }
        }

        given("the root Gradle project") {
            val rootProject = rendererProject(":", emptyList(), emptyList(), null)
            val report = emptyWorkspaceReport().copy(name = "srcx", rootProjects = listOf(rootProject))

            `when`("its build comparison details are rendered") {
                val article = WorkspaceHtmlRenderer().render(report).fragment.substringAfter("</style>")
                val builds = article.substringAfter("id=\"builds\"").substringBefore("id=\"health\"")

                then("the compact matrix explains the root build without a project inspector") {
                    builds shouldContain "data-srcx-build-row=\"srcx\" data-srcx-build-kind=\"root\""
                    builds shouldContain "<strong>srcx</strong>"
                    builds shouldContain
                        "<small>Root build <span aria-hidden=\"true\">&middot;</span> <code>.</code></small>"
                    builds shouldNotContain "data-srcx-build-project"
                    builds shouldNotContain "data-srcx-build-inspector"
                }
            }
        }

        given("per-project architecture without cumulative workspace evidence") {
            val legacyComponent =
                architectureComponent(
                    "LegacyArchitectureOnly",
                    "com.example.legacy",
                    "legacy",
                    ArchitectureLayer.DOMAIN,
                )
            val legacyProject =
                rendererProject(
                    path = ":legacy",
                    symbols = emptyList(),
                    sourceSets = emptyList(),
                    analysis =
                        AnalysisSummary(
                            emptyList(),
                            emptyList(),
                            emptyList(),
                            ArchitectureSummary(components = listOf(legacyComponent)),
                        ),
                )

            `when`("the report is rendered") {
                val report = emptyWorkspaceReport().copy(rootProjects = listOf(legacyProject))
                val rendered = WorkspaceHtmlRenderer().render(report)

                then("the workspace graph does not fall back to the legacy component model") {
                    rendered.fragment shouldContain "Index a build"
                    rendered.fragment shouldNotContain "LegacyArchitectureOnly"
                }
            }
        }

        given("dynamic values containing HTML and template syntax") {
            `when`("the report is rendered") {
                val payload = "<script>alert(\"x\") & 'y' {{slot}}</script>"
                val rendered = WorkspaceHtmlRenderer().render(escapingWorkspaceReport(payload))
                val escaped =
                    "&lt;script&gt;alert(&quot;x&quot;) &amp; &#39;y&#39; " +
                        "&#123;&#123;slot&#125;&#125;&lt;/script&gt;"

                then("every rendered dynamic value is HTML escaped") {
                    rendered.fragment shouldContain escaped
                    rendered.document shouldContain escaped
                    rendered.fragment shouldNotContain payload
                    rendered.fragment shouldNotContain "{{slot}}"
                    rendered.fragment shouldContain "\"sourceFiles\""
                    rendered.fragment shouldContain "\\u003cscript\\u003ealert"
                    rendered.fragment shouldContain "\\u2028"
                    rendered.fragment shouldContain "\\u2029"
                    rendered.fragment shouldNotContain "</script><script>alert"
                }
            }
        }

        given("equivalent reports with differently ordered collections") {
            `when`("both are rendered") {
                val original = fullWorkspaceReport()
                val reordered = reorderWorkspaceReport(original)

                then("their fragments and documents are byte-for-byte deterministic") {
                    WorkspaceHtmlRenderer().render(original) shouldBe WorkspaceHtmlRenderer().render(reordered)
                }
            }
        }

        given("an HTML resource template with a missing slot") {
            `when`("the template is resolved") {
                val resources = WorkspaceHtmlResourceRenderer()
                val failure =
                    shouldThrow<IllegalArgumentException> {
                        resources.renderTemplate(
                            template = "<p>{{known}} {{missing}}</p>",
                            slots = mapOf("known" to "resolved"),
                            templateName = "test-template",
                        )
                    }

                then("the unresolved slot is reported by name") {
                    failure.message shouldContain "Unresolved HTML template slots in test-template"
                    failure.message shouldContain "missing"
                }
            }
        }

        given("the bundled graph scripts") {
            then("D3 is loaded from the verified WebJar path and closing tags are neutralized") {
                WorkspaceHtmlResourceRenderer.D3_WEBJAR_RESOURCE_PATH shouldBe
                    "/META-INF/resources/webjars/d3/7.9.0/dist/d3.min.js"
                val d3 =
                    WorkspaceHtmlResourceRenderer.readAbsoluteClasspathResource(
                        WorkspaceHtmlResourceRenderer.D3_WEBJAR_RESOURCE_PATH,
                    )
                d3 shouldContain "7.9.0"
                WorkspaceHtmlResourceRenderer().scripts() shouldNotContain "<script src="
                "before</ScRiPt>after".escapeClosingScriptSequence() shouldBe "before<\\/script>after"
            }

            then("the owned explorer validates data and fails open to the complete static file list") {
                val script =
                    WorkspaceHtmlResourceRenderer.readClasspathResource(
                        WorkspaceHtmlResourceRenderer.ARCHITECTURE_GRAPH_SCRIPT,
                    )

                script shouldContain "safelyEnhanceGraph"
                script shouldContain "validGraphData(data)"
                script shouldContain "if (!enhanceGraph(root, graphIndex)) restoreFallback(root)"
                script shouldContain "if (!validGraphData(data)) return false"
                script shouldContain "validEdgeEvidenceArrays(data)"
                script shouldContain "function validFileOccurrence(occurrence)"
                script shouldContain "function validRelationshipOccurrence(occurrence)"
                script shouldContain "function countArrayMatchesOccurrences(items, field, occurrences)"
                script shouldContain "edge.recordCount !== edge.occurrences.length"
                script shouldContain "countArrayMatchesOccurrences(edge.kindCounts, \"kind\", edge.occurrences)"
                script shouldContain
                    "countArrayMatchesOccurrences(edge.evidenceCounts, \"evidence\", edge.occurrences)"
                script shouldNotContain "data.fileNodes.length === 0"
                script shouldContain "if (nodes.length === 0)"
                script shouldContain "restoreFallback(root)"
                script shouldContain "using the static fallback"
                script shouldContain "fallback.hidden = false"
                script shouldContain "svg.setAttribute(\"hidden\", \"\")"
                script shouldContain "event.ctrlKey || event.metaKey"
                script shouldContain "No observed file cycles or analyzer-inferred component cycles"
                script shouldContain "svgElement.removeAttribute(\"hidden\")"
                script shouldNotContain "svgElement.hidden = false"
                script shouldNotContain "innerHTML"
                script shouldNotContain "insertAdjacentHTML"
            }

            then("the explorer has resilient fitting, resize, motion, and keyboard contracts") {
                val script =
                    WorkspaceHtmlResourceRenderer.readClasspathResource(
                        WorkspaceHtmlResourceRenderer.ARCHITECTURE_GRAPH_SCRIPT,
                    )

                script shouldContain "cancelScheduledFit()"
                script shouldContain "generation === drawGeneration && !userNavigated && !destroyed"
                script shouldContain "window.addEventListener(\"resize\", requestResize"
                script shouldContain "viewport.clientHeight"
                script shouldContain "window.requestAnimationFrame"
                script shouldContain "if (reducedMotion())"
                script shouldContain
                    ".attr(\"tabindex\", function (node) { return node.id === state.rovingNodeId ? 0 : -1; })"
                script shouldContain
                    ".attr(\"tabindex\", function (edge) { return edge.id === state.rovingEdgeId ? 0 : -1; })"
                script shouldNotContain ".attr(\"tabindex\", 0)"
                script shouldContain "focusAdjacentNode(node, event.key)"
                script shouldContain "ArrowLeft"
                script shouldContain "ArrowRight"
                script shouldContain "ArrowUp"
                script shouldContain "ArrowDown"
                script shouldContain "activationKey(event)"
                script shouldContain "srcx-dashboard__architecture-svg-edge-line"
                script shouldContain "srcx-dashboard__architecture-svg-edge-hit"
                script shouldContain "srcx-dashboard__architecture-svg-node-hit"
                script shouldContain ".attr(\"markerUnits\", \"userSpaceOnUse\")"
                script shouldContain ".attr(\"markerWidth\", 10)"
                script shouldContain ".attr(\"markerHeight\", 10)"
                script shouldContain "is-label-pinned"
                script shouldContain "assignLinkGeometry(links)"
                script shouldContain "renderedGraphBounds(zoomLayer.node())"
                script shouldContain "safeSvgBounds"
                script shouldContain "var minimumZoomScale = 0.01"
                script shouldContain "var maximumZoomScale = 4"
                script shouldContain ".scaleExtent([minimumZoomScale, maximumZoomScale])"
            }

            then("the files lens renders build regions and every complete filename") {
                val script =
                    WorkspaceHtmlResourceRenderer.readClasspathResource(
                        WorkspaceHtmlResourceRenderer.ARCHITECTURE_GRAPH_SCRIPT,
                    )

                script shouldContain "srcx-dashboard__architecture-build-regions"
                script shouldContain "representedBuilds(nodes, buildByName)"
                script shouldContain "updateBuildRegions(nodes, buildGroups, layout.cells)"
                script shouldContain "buildCellLayout(nodes, width, height, buildByName)"
                script shouldContain
                    "constrainNodesToBuildCells(nodes, layout.cells, layout.projectCells, layout.subgroupCells)"
                script shouldContain "packVariableRectangles"
                script shouldContain "var nodeRectangles = members.map(function (node)"
                script shouldContain "automaticNodeSubgroups(members)"
                script shouldContain "var nodePack = packVariableRectangles(subgroupRectangles, 1.35, 14)"
                script shouldContain "var subgroupPack = packVariableRectangles(subgroups, 1.35, subgroupGap)"
                script shouldContain "var placement = subgroup.nodePack.placements.get(node.id)"
                script shouldContain "renderSubgroupRegions(subgroupLayer, layout.subgroups, layout.subgroupCells)"
                script shouldContain "updateSubgroupRegions(subgroupGroups, layout.subgroupCells)"
                script shouldContain "node.subgroupKey = subgroup.key"
                script shouldContain "var staticLayoutNodeThreshold = 240"
                script shouldContain "staticNodeSimulation(nodes)"
                script shouldContain "connectedNodeSimulation(nodes, links, layout)"
                script shouldContain "layout.homes.get(node.id)"
                script shouldContain "homeForceStrength"
                script shouldContain "measureNodeLabels(nodeGroups)"
                script shouldContain "measureBuildLabels(buildGroups)"
                script shouldContain "var cell = cells.get(build.name)"
                script shouldContain ".select(\".srcx-dashboard__architecture-build-region-body\")"
                script shouldContain ".select(\".srcx-dashboard__architecture-build-drag-handle\")"
                script shouldContain ".select(\".srcx-dashboard__architecture-project-region-body\")"
                script shouldContain ".select(\".srcx-dashboard__architecture-project-drag-handle\")"
                script shouldContain "var labelHeight = node.labelPinned ? bounds.height : 0"
                script shouldContain "node.labelDirection === \"left\""
                script shouldNotContain "groupCenters"
                script shouldContain "nodes.forEach(function (node)"
                script shouldContain "buildColor(node.build, buildByName)"
                script shouldContain "persistentLabelIds(nodes)"
                script shouldContain "function comparePersistentLabelPriority(left, right)"
                script shouldContain "Math.min(36, Math.ceil(Math.sqrt(nodes.length) * 2.2))"
                script shouldContain "assignNodeVisualHierarchy(nodes)"
                script shouldContain "node.visualTier ="
                script shouldContain "is-signal-"
                script shouldContain "appendNodeLabel(d3.select(this), node)"
                script shouldContain "wrapVisibleLabel(nodeLabel(node), node.entityType === \"file\" ? 20 : 22)"
                script shouldContain "accessibleNodeName(node)"
                script shouldContain
                    "return node.name + \" / \" + node.build + \" / \" + node.project + \" / \" + " +
                    "node.sourceSet + \" / \" + node.path;"
                script shouldNotContain "shorten(nodeLabel(node), 26).length * 5.2"
            }

            then("the enhanced status retains same-file records and the workspace total") {
                val script =
                    WorkspaceHtmlResourceRenderer.readClasspathResource(
                        WorkspaceHtmlResourceRenderer.ARCHITECTURE_GRAPH_SCRIPT,
                    )

                script shouldContain "node.shownInternalRecordCount || 0"
                script shouldContain "linkedRecords + internalRecords"
                script shouldContain "data.totalRelationshipRecordCount"
                script shouldContain "var boundedLensRecords = projection.availableRelationshipRecordCount +"
                script shouldContain "projection.availableInternalRecordCount || 0"
                script shouldContain "records + \" available in this frame / \""
                script shouldContain "workspace relationship records available to this scope"
            }

            then("the evidence region restores graph focus without modal backdrop state") {
                val script =
                    WorkspaceHtmlResourceRenderer.readClasspathResource(
                        WorkspaceHtmlResourceRenderer.ARCHITECTURE_GRAPH_SCRIPT,
                    )
                val styles =
                    WorkspaceHtmlResourceRenderer.readClasspathResource(
                        WorkspaceHtmlResourceRenderer.DASHBOARD_STYLES,
                    )
                script shouldContain "detailReturnFocus"
                script shouldContain "detailClose.focus({ preventScroll: true })"
                script shouldContain "restoreDetailFocus()"
                script shouldContain "if (event.key !== \"Escape\") return"
                script shouldContain "if (lassoGesture)"
                script shouldContain "if (state.boxSelectMode)"
                script shouldContain "if (detail.hidden || architectureFullscreenActive()) return"
                script shouldContain "if (detail.contains(candidate)) return"
                styles shouldNotContain "is-detail-open"
                styles shouldNotContain "srcx-dashboard__architecture-backdrop"
            }

            then("relationship evidence retains occurrence identity, scope, semantics, and complete source") {
                val script =
                    WorkspaceHtmlResourceRenderer.readClasspathResource(
                        WorkspaceHtmlResourceRenderer.ARCHITECTURE_GRAPH_SCRIPT,
                    )

                script shouldContain "function renderOccurrencePager(edge, occurrences, source, target, parent)"
                script shouldContain "renderOccurrencePager(edge, occurrences, source, target, detailFields)"
                script shouldContain "[\"Relationship kind\", kind]"
                script shouldContain "[\"Evidence\", evidence]"
                script shouldContain "[\"Source declaration\", declarationIdentity(sourceDeclaration, sourceId)]"
                script shouldContain "[\"Target declaration\", declarationIdentity(targetDeclaration, targetId)]"
                script shouldContain "[\"Location\", occurrence.file + \":\" + occurrence.line]"
                script shouldContain
                    "appendDeclarationButton(navigation, \"Open target declaration\", targetDeclaration)"
                script shouldContain "sourceFileKey: occurrenceSourceFileKey(occurrence)"
                script shouldContain "appendSourceViewer(activeEvidenceHost, sourceFileForEvidence(evidence)"
                script shouldNotContain "occurrences.slice(0, 12)"
                script shouldNotContain "occurrence rows omitted from this drawer"
            }

            then("a selected file exposes complete catalog evidence without blanket highlighting") {
                assertFileRelationshipEvidenceBrowserContract()
            }
        }

        registerAtlasUxContractTests()
    })

private fun assertBuildAtlas(article: String) {
    val builds = article.substringAfter("id=\"builds\"").substringBefore("id=\"health\"")
    val rootRow = buildMatrixRow(builds, "render-lab")
    val libraryRow = buildMatrixRow(builds, "library-build")
    val zetaRow = buildMatrixRow(builds, "zeta-build")

    builds shouldContain "data-srcx-build-atlas"
    builds shouldContain "data-srcx-build-comparison"
    builds shouldContain "Unified build matrix"
    builds shouldContain "Build comparison"
    builds shouldContain "Root and included builds are workspace members, not dependency edges"
    builds shouldContain "role=\"table\" aria-label=\"Build metrics comparison\""
    builds shouldContain "<span role=\"columnheader\">Build</span>"
    builds shouldContain "<span role=\"columnheader\">Projects</span>"
    builds shouldContain "<span role=\"columnheader\">Symbols</span>"
    builds shouldContain "<span role=\"columnheader\">Findings / severity mix</span>"
    builds shouldContain "<span role=\"columnheader\">Source-set records / mix</span>"
    builds shouldContain
        "data-srcx-build-row=\"render-lab\" data-srcx-build-kind=\"root\" " +
        "style=\"--srcx-build-color: ${workspaceBuildColor("render-lab")}\""
    builds shouldContain
        "data-srcx-build-row=\"library-build\" data-srcx-build-kind=\"included\" " +
        "style=\"--srcx-build-color: ${workspaceBuildColor("library-build")}\""
    rootRow shouldContain "<strong>render-lab</strong>"
    rootRow shouldContain "<small>Root build <span aria-hidden=\"true\">&middot;</span> <code>.</code></small>"
    libraryRow shouldContain "<strong>library-build</strong>"
    libraryRow shouldContain "<code>../library</code>"
    zetaRow shouldContain "<strong>zeta-build</strong>"
    zetaRow shouldContain "<code>../zeta</code>"
    zetaRow shouldContain "No source-set records"
    val rootIndex = builds.indexOf("<strong>render-lab</strong>")
    val libraryIndex = builds.indexOf("<strong>library-build</strong>")
    val zetaIndex = builds.indexOf("<strong>zeta-build</strong>")
    (rootIndex < libraryIndex) shouldBe true
    (libraryIndex < zetaIndex) shouldBe true
    builds shouldNotContain "srcx-dashboard__build-table"
    builds shouldNotContain "data-srcx-build-card"
    article shouldNotContain "srcx-repo-card"
    article shouldNotContain "id=\"topology\""
}

private fun assertFileRelationshipEvidenceBrowserContract() {
    val script =
        WorkspaceHtmlResourceRenderer.readClasspathResource(
            WorkspaceHtmlResourceRenderer.ARCHITECTURE_GRAPH_SCRIPT,
        )

    script shouldContain "appendFileRelationshipEvidenceBrowser(body, node, sourceFile)"
    script shouldContain "appendFileRelationshipEvidenceBrowser(body, node, sourceFileForNode(node))"
    script shouldContain "function appendFileRelationshipEvidenceBrowser(parent, node, sourceFile)"
    script shouldContain "function fileRelationshipEvidenceItems(node, sourceFile)"
    script shouldContain "Catalog evidence items"
    script shouldContain "Typed catalog outgoing relationship record"
    script shouldNotContain "Typed displayed outgoing relationship record"
    script shouldNotContain "bounded relationship-evidence line"
    script shouldContain "(node.internalOccurrences || []).forEach"
    script shouldContain "availableFileEdges.forEach(function (edge)"
    script shouldContain "if (endpointId(edge.source) !== node.id) return"
    script shouldContain "(sourceFile.relationshipLines || []).forEach"
    script shouldContain "if (typedLines.has(line)) return"
    script shouldContain "Catalog source evidence; target omitted/unavailable"
    script shouldContain "Target omitted/unavailable; no endpoint inferred"
    script shouldContain "Nothing is marked just because the file is open."
    script shouldContain "Browse relationship evidence originating in this file"
    script shouldContain "Previous relationship evidence item from this file"
    script shouldContain "Next relationship evidence item from this file"
    script shouldContain "File relationship evidence item "
    script shouldContain "relationshipLineCounts: fileEvidenceLineCounts(items)"
    script shouldContain "only the selected item is highlighted"
    script shouldContain "fileNodeEvidenceValid"
}

private fun assertBuildCharts(article: String) {
    val builds = article.substringAfter("id=\"builds\"").substringBefore("id=\"health\"")

    Regex("data-srcx-build-comparison(?:\\s|>)").findAll(builds).count() shouldBe 1
    builds shouldContain "Each bar compares builds only within its metric column"
    builds shouldContain "One source-set record is one analyzed Gradle project/source-set summary"
    builds shouldContain "Column maximums"
    builds shouldContain "<dt>Projects</dt><dd>2</dd>"
    builds shouldContain "<dt>Symbols</dt><dd>3</dd>"
    builds shouldContain "<dt>Findings</dt><dd>3</dd>"
    builds shouldContain "<dt>Source-set records</dt><dd>2</dd>"
    builds shouldContain "aria-label=\"render-lab: 2 projects; column maximum 2\""
    builds shouldContain "aria-label=\"library-build: 1 project; column maximum 2\""
    builds shouldContain "aria-label=\"zeta-build: 0 projects; column maximum 2\""
    builds shouldContain "aria-label=\"render-lab: 3 symbols; column maximum 3\""
    builds shouldContain "aria-label=\"library-build: 1 symbol; column maximum 3\""
    builds shouldContain "aria-label=\"zeta-build: 0 symbols; column maximum 3\""
    builds shouldContain "aria-label=\"render-lab: 3 findings; Forbidden: 1, Warning: 1, Info: 1; column maximum 3\""
    builds shouldContain "aria-label=\"library-build: 1 finding; Forbidden: 0, Warning: 0, Info: 1; column maximum 3\""
    builds shouldContain "aria-label=\"zeta-build: 0 findings; Forbidden: 0, Warning: 0, Info: 0; column maximum 3\""
    builds shouldContain "aria-label=\"render-lab: 2 source-set records; main: 1, test: 1; column maximum 2\""
    builds shouldContain "aria-label=\"library-build: 1 source-set record; main: 1; column maximum 2\""
    builds shouldContain "aria-label=\"zeta-build: 0 source-set records; No source-set records; column maximum 2\""
    builds shouldContain "--srcx-build-share: 100.0%"
    builds shouldContain "--srcx-build-share: 50.0%"
    builds shouldContain "--srcx-build-share: 33.3%"
}

private fun assertBuildChartComposition(article: String) {
    val builds = article.substringAfter("id=\"builds\"").substringBefore("id=\"health\"")
    val rootRow = buildMatrixRow(builds, "render-lab")
    val libraryRow = buildMatrixRow(builds, "library-build")
    val rootFindings = buildMetricCell(rootRow, "findings")
    val libraryFindings = buildMetricCell(libraryRow, "findings")
    val rootSourceSets = buildMetricCell(rootRow, "source-set-records")

    rootFindings shouldContain "Forbidden <b>1</b>"
    rootFindings shouldContain "Warning <b>1</b>"
    rootFindings shouldContain "Info <b>1</b>"
    libraryFindings shouldContain "Forbidden <b>0</b>"
    libraryFindings shouldContain "Warning <b>0</b>"
    libraryFindings shouldContain "Info <b>1</b>"
    rootSourceSets shouldContain "main <b>1</b>"
    rootSourceSets shouldContain "test <b>1</b>"
    (rootSourceSets.indexOf("main <b>1</b>") < rootSourceSets.indexOf("test <b>1</b>")) shouldBe true
}

private fun assertBuildComparisonMarkup(article: String) {
    val builds = article.substringAfter("id=\"builds\"").substringBefore("id=\"health\"")
    val findings = article.substringAfter("id=\"findings\"").substringBefore("id=\"footer\"")

    builds shouldContain "data-srcx-build-comparison"
    builds shouldContain "data-srcx-build-metric=\"findings\""
    builds shouldNotContain "data-srcx-build-choice"
    builds shouldNotContain "data-srcx-build-comparison-filter"
    builds shouldNotContain "data-srcx-build-inspector"
    builds shouldNotContain "data-srcx-review-findings"
    builds shouldNotContain "data-srcx-project-finding"
    builds shouldNotContain "data-srcx-open-finding"

    findings shouldContain "data-srcx-finding-controls"
    findings shouldContain "<details class=\"srcx-dashboard__finding-controls\""
    findings shouldContain "<summary class=\"srcx-dashboard__finding-filter-toggle\">"
    findings shouldContain "<strong>Filter findings</strong><small>Build / project / severity</small>"
    findings shouldContain "class=\"srcx-dashboard__finding-filter-body\""
    findings shouldContain "data-srcx-finding-filter-group=\"build\""
    findings shouldContain "data-srcx-finding-filter-group=\"project\""
    findings shouldContain "data-srcx-finding-filter-group=\"severity\""
    findings shouldContain "data-srcx-finding-filter-rail data-srcx-roving-group"
    findings shouldContain "data-srcx-finding-filter=\"build\" data-srcx-finding-filter-value=\"all\""
    findings shouldContain "data-srcx-finding-filter=\"build\" data-srcx-finding-filter-value=\"render-lab\""
    findings shouldContain "data-srcx-finding-filter=\"project\" data-srcx-finding-filter-value=\":codec\""
    findings shouldContain "data-srcx-finding-filter-build=\"library-build\""
    findings shouldContain "data-srcx-finding-filter=\"severity\" data-srcx-finding-filter-value=\"WARNING\""
    findings shouldContain "data-srcx-finding-filter-count=\"4\""
    findings shouldContain "data-srcx-finding-filter-reset disabled"
    findings shouldContain ">4 findings shown</output>"
    findings shouldNotContain "<select"
    findings shouldContain "data-srcx-open-finding"
}

private fun assertDirectedBuildDependencies(article: String) {
    val builds = article.substringAfter("id=\"builds\"").substringBefore("id=\"health\"")

    builds shouldContain "Directed evidence"
    builds shouldContain "Arrow direction: consumer build &rarr; active build it depends on"
    builds shouldContain "membership only and does not create an arrow"
    builds shouldContain "data-srcx-build-route role=\"img\""
    builds shouldContain "aria-label=\"render-lab depends on library-build\""
    builds shouldContain "aria-label=\"library-build depends on zeta-build\""
    builds shouldContain "<small>Consumer build</small>"
    builds shouldContain "<small>depends on</small>"
    builds shouldContain "<small>Target build</small>"
    builds shouldContain "<svg viewBox=\"0 0 120 28\""
    builds shouldNotContain "None observed"
    builds shouldNotContain "weight"
    builds shouldNotContain "provenance"
}

@Suppress("LongMethod")
private fun BehaviorSpec.registerAtlasUxContractTests() {
    given("the Atlas UX contracts") {
        val script =
            WorkspaceHtmlResourceRenderer.readClasspathResource(
                WorkspaceHtmlResourceRenderer.ARCHITECTURE_GRAPH_SCRIPT,
            )
        val styles =
            WorkspaceHtmlResourceRenderer.readClasspathResource(
                WorkspaceHtmlResourceRenderer.DASHBOARD_STYLES,
            )
        val themeStyles =
            WorkspaceHtmlResourceRenderer.readClasspathResource(
                WorkspaceHtmlResourceRenderer.THEME,
            )

        then("file, symbol, and relationship selections render exact embedded source") {
            assertSourceViewerContract(script, styles)
        }

        then("the fullscreen control has native, fallback, escape, resize, and cleanup contracts") {
            assertAtlasFullscreenContract(script, styles)
        }

        then("occurrence paging and target navigation retain exact source scope") {
            assertOccurrencePagingContract(script, styles)
        }

        then("every graph node and relationship remains reachable with roving keyboard navigation") {
            assertAtlasKeyboardContract(script)
        }

        then("motion, build colors, and narrow reflow have explicit reduced-motion-aware contracts") {
            assertAtlasMotionAndResponsiveContract(script, styles, themeStyles)
        }

        then("the Atlas toolbar stays compact while search and small copy remain readable") {
            assertAtlasToolbarReadabilityContract(styles, themeStyles)
        }

        then("the navigator scopes builds, projects, and source sets accessibly") {
            assertAtlasNavigatorContract(script, styles)
        }

        then("semantic labels contribute measured extents to collision, regions, and Fit") {
            assertAtlasLabelFitContract(script, styles)
        }

        then("ring semantics, cycle routes, and problem evidence remain explicit and navigable") {
            assertAtlasCycleAndProblemContract(script, styles)
        }

        then("analyzer-inferred cycles stay typed, validated, count-neutral, and source navigable") {
            assertAtlasAnalysisCycleContract(script, styles)
        }

        then("the source detail panel remains readable and accessibly resizable") {
            assertAtlasSourceDetailResizeContract(script, styles)
        }

        then("source highlighting presents one active exact-evidence line") {
            assertAtlasSingleEvidenceContract(script, styles)
        }

        then("relationship kinds filter exact records and keep routed count labels legible") {
            assertAtlasRelationshipKindAndRoutingContract(script, styles)
        }

        then("selected scopes show complete file and symbol frames on the warm paper palette") {
            assertAtlasScopedSymbolsAndPaletteContract(script, styles, themeStyles)
        }

        then("dense file and symbol frames retain an automatic signal hierarchy") {
            assertAtlasVisualHierarchyContract(script, styles)
        }

        then("build comparison filters every chart and its selected-build evidence") {
            assertBuildComparisonContract(script, styles)
        }

        then("typed external evidence requests deep-link without display-name guessing") {
            assertAtlasEvidenceDeepLinkContract(script)
        }
    }
}

private fun assertAtlasVisualHierarchyContract(
    script: String,
    styles: String,
) {
    script shouldContain "function automaticNodeSubgroups(members)"
    val projectDisplayFunctions =
        Regex("function projectDisplayName\\(projectName, buildName\\)")
            .findAll(script)
            .count()
    projectDisplayFunctions shouldBe 1
    script
        .substringAfter("function projectDisplayWidth(project)")
        .substringBefore("function projectRegionSummaryWidth(node, members)") shouldContain
        "function projectDisplayName(projectName, buildName)"
    script shouldContain "function adaptiveSymbolSubgroups(project, sourceSet, area, members)"
    script shouldContain
        "d3.group(members, function (node) { return normalizeBoundedPath(node.file); })"
    script shouldContain "file.members.length >= 12"
    script shouldContain "bucket.members.length + file.members.length > 64"
    script shouldContain "bucket.fileCount >= 12"
    script shouldContain "file.path,"
    script shouldContain "percentileValue(nodes.map(nodeRelationshipRecordCount), 0.9)"
    script shouldContain "4.5 + Math.pow(signal, 0.72) * 15.5"
    script shouldContain "4 + Math.pow(signal, 0.72) * 14"
    script shouldContain "var bounds = node.labelPinned ? safeSvgBounds(this) : null"
    script shouldContain "rectangles.length <= 64"
    script shouldContain "incidentLinksByNode = indexIncidentLinks(links)"
    script shouldContain "var incident = (incidentLinksByNode.get(node.id) || []).filter(function (edge)"
    script shouldContain "sourceFileFindingsForSymbol(node)"
    script shouldContain "Findings in declaring file"
    script shouldContain "not necessarily attributed to this symbol"
    script shouldContain "srcx-dashboard__architecture-symbol-file-finding-marker"
    script shouldContain "pinnedLabelIds.has(node.id)"
    styles shouldContain ".srcx-dashboard__architecture-subgroup-region rect"
    styles shouldContain "[data-srcx-graph-density=\"dense\"]"
    styles shouldContain ".srcx-dashboard__architecture-symbol-file-finding-marker"
    styles shouldContain "fill: var(--srcx-tertiary)"
}

private fun assertSourceViewerContract(
    script: String,
    styles: String,
) {
    script shouldContain "var sourceFileById = new Map(data.sourceFiles.map"
    script shouldContain "var sourceFileByScopePath = new Map(data.sourceFiles.map"
    script shouldContain "appendActiveEvidenceSection(body, \"Source evidence\")"
    script shouldContain "detailSection(\"Complete source file\")"
    script shouldContain "splitSourceLines(sourceFile.content).forEach"
    script shouldContain "number.textContent = String(lineNumber)"
    script shouldContain "function sourceSyntaxForPath(path)"
    script shouldContain "function appendHighlightedSourceLine(code, line, syntax, state)"
    script shouldContain "document.createTextNode"
    script shouldContain "span.textContent = text"
    script shouldContain "value.endsWith(\".java\")"
    script shouldContain "value.endsWith(\".kt\") || value.endsWith(\".kts\")"
    script shouldContain "value.endsWith(\".groovy\") || value.endsWith(\".gradle\")"
    script shouldContain "value.endsWith(\".json\")"
    script shouldContain "value.endsWith(\".yaml\") || value.endsWith(\".yml\")"
    script shouldContain "value.endsWith(\".toml\")"
    script shouldContain "value.endsWith(\".md\") || value.endsWith(\".markdown\")"
    script shouldContain "return \"plain\""
    script shouldContain "is-declaration-line"
    script shouldContain "is-active-declaration"
    script shouldContain "is-active-relationship"
    script shouldContain "drawer.scrollTo({ top: top, behavior: reducedMotion() ? \"auto\" : \"smooth\" })"
    styles shouldContain ".srcx-dashboard__architecture-source-viewer"
    styles shouldContain ".srcx-dashboard__architecture-source-line"
    styles shouldContain ".is-active-relationship"
    script shouldNotContain "Source outline"
    script shouldNotContain "Relationship flow"
    script shouldNotContain "Relationship neighborhood"
    script shouldNotContain "detailSection(\"Declarations\")"
    script shouldNotContain "Full source text is unavailable"
}

private fun assertAtlasFullscreenContract(
    script: String,
    styles: String,
) {
    assertAtlasFullscreenBehaviorContract(script, styles)
    assertAtlasFullscreenSizingContract(styles)
}

private fun assertAtlasFullscreenBehaviorContract(
    script: String,
    styles: String,
) {
    script shouldContain "root.requestFullscreen || root.webkitRequestFullscreen"
    script shouldContain "activateFallbackFullscreen()"
    script shouldContain "request.then(function ()"
    script shouldContain "if (!destroyed) synchronizeFullscreenState()"
    script shouldContain "document.addEventListener(\"fullscreenchange\", handleFullscreenChange)"
    script shouldContain "if (event.key !== \"Escape\" || !architectureFullscreenActive()) return"
    script shouldContain "fullscreenButton.textContent = active ? \"Exit full screen\" : \"Full screen\""
    script shouldContain "fullscreenButton.setAttribute(\"aria-pressed\", String(active))"
    script shouldContain "savedDocumentOverflow"
    script shouldContain "savedViewportPosition = { x: window.scrollX || 0, y: window.scrollY || 0 }"
    script shouldContain "fullscreenReturnFocus = document.activeElement"
    script shouldContain "window.scrollTo(position.x, position.y)"
    script shouldContain "focusTarget.focus({ preventScroll: true })"
    script shouldContain "restoreDocumentScroll()"
    script shouldContain "scheduleFullscreenFit()"
    script shouldContain "resize();"
    script shouldContain "fitGraph();"
    script shouldContain "document.removeEventListener(\"fullscreenchange\", handleFullscreenChange)"
    script shouldContain "fullscreenButton.removeEventListener(\"click\", toggleArchitectureFullscreen)"
    script shouldContain "fullscreenChromeToggle.addEventListener(\"click\", toggleFullscreenChrome)"
    script shouldContain "fullscreenChromeToggle.removeEventListener(\"click\", toggleFullscreenChrome)"
    script shouldContain "function toggleFullscreenChrome()"
    script shouldContain "function setFullscreenChromeCollapsed(collapsed)"
    val chromeDisclosure =
        script
            .substringAfter("function setFullscreenChromeCollapsed(collapsed)")
            .substringBefore("function enterArchitectureFullscreen()")
    chromeDisclosure shouldContain "svg.interrupt()"
    (
        chromeDisclosure.indexOf("svg.interrupt()") <
            chromeDisclosure.indexOf("d3.zoomTransform(svgElement)")
    ) shouldBe true
    chromeDisclosure shouldContain "markUserNavigation()"
    chromeDisclosure shouldContain "window.clearTimeout(fullscreenFitTimer)"
    chromeDisclosure shouldContain "chromeDisclosureResizePending = true"
    script shouldContain "var preservedTransform = d3.zoomTransform(svgElement)"
    script shouldContain "svg.call(zoom.transform, preservedTransform)"
    chromeDisclosure shouldNotContain "scheduleFullscreenFit()"
    script shouldContain "if (chromeDisclosureResizePending)"
    script shouldContain "function scheduleChromeDisclosureResizeSettlement()"
    script shouldContain "root.dataset.srcxFullscreenChrome = nextCollapsed ? \"collapsed\" : \"expanded\""
    script shouldContain "fullscreenChromeToggle.textContent = nextCollapsed ? \"Show map controls\""
    script shouldContain "setFullscreenChromeCollapsed(active)"
    script shouldContain "function lockNarrowPage()"
    script shouldContain "function closeIdleEvidence()"
    script shouldContain "function scheduleIdleSeedFit()"
    script shouldContain "scheduleIdleSeedFit();"
    script shouldContain "appendFileSourceReveal(body, node)"
    (script.indexOf("appendFindingSection") < script.indexOf("appendFileSourceReveal(body, node)")) shouldBe true
    styles shouldContain ".srcx-dashboard__architecture-graph:fullscreen"
    styles shouldContain ".srcx-dashboard__architecture-graph.is-fullscreen-fallback"
    styles shouldContain ".srcx-dashboard__architecture-graph[data-srcx-fullscreen=\"true\"]"
    styles shouldContain "body.srcx-dashboard--architecture-fullscreen"
    styles shouldContain
        ".srcx-dashboard__architecture-graph[data-srcx-fullscreen=\"true\"]\n" +
        "    .srcx-dashboard__architecture-navigator,"
    styles shouldContain ".srcx-dashboard__architecture-guide"
    styles shouldContain ".srcx-dashboard__architecture-chrome-toggle:not([hidden])"
    styles shouldContain "[data-srcx-fullscreen-chrome=\"collapsed\"]"
    styles shouldContain "> .srcx-dashboard__architecture-graph-head"
    styles shouldContain "> .srcx-dashboard__architecture-controls"
    styles shouldContain "> .srcx-dashboard__architecture-navigator"
    styles shouldContain "position: relative"
}

private fun assertAtlasFullscreenSizingContract(styles: String) {
    val fullscreenSizing =
        styles.substringAfter(
            "/* Keep fullscreen navigation useful without letting the path browser starve the graph. */",
        )
    val fullscreenControlsSizing =
        fullscreenSizing
            .substringAfter("> .srcx-dashboard__architecture-controls {")
            .substringBefore("}")

    fullscreenControlsSizing shouldContain "padding-right: 12px;"
    fullscreenSizing shouldContain
        ".srcx-dashboard__architecture-graph[data-srcx-fullscreen=\"true\"]\n" +
        "    > .srcx-dashboard__architecture-navigator"
    fullscreenSizing shouldContain "max-block-size: min(30dvh, 320px);"
    fullscreenSizing shouldContain "overflow-y: auto;"
    fullscreenSizing shouldContain "overscroll-behavior-y: contain;"
    fullscreenSizing shouldContain "scrollbar-gutter: stable;"
    fullscreenSizing shouldContain
        ".srcx-dashboard__architecture-graph[data-srcx-fullscreen=\"true\"]\n" +
        "    > .srcx-dashboard__architecture-viewport"
    fullscreenSizing shouldContain "flex: 1 0 max(220px, 36dvh);"
    fullscreenSizing shouldContain "min-block-size: max(220px, 36dvh);"
    fullscreenSizing shouldContain "@container (max-width: 1000px) and (min-width: 761px)"
    fullscreenSizing shouldContain "\"lenses actions\""
    fullscreenSizing shouldContain "\"kinds search\""
    fullscreenSizing shouldContain "grid-template-columns: repeat(2, minmax(0, 1fr));"
    fullscreenSizing shouldContain "max-block-size: min(22dvh, 200px);"
    fullscreenSizing shouldContain "flex-basis: max(180px, 32dvh);"
    fullscreenSizing shouldContain "max-block-size: min(28dvh, 260px);"
    fullscreenSizing shouldContain "\"zoom-in zoom-in zoom-out zoom-out fit fit\""
    fullscreenSizing shouldContain "\"reset reset reset fullscreen fullscreen fullscreen\""
    fullscreenSizing shouldContain "grid-template-columns: repeat(6, minmax(0, 1fr));"
    fullscreenSizing shouldContain "@container (max-width: 420px)"
    fullscreenSizing shouldContain "\"fullscreen fullscreen\""
    fullscreenSizing shouldContain "grid-area: fullscreen;"
}

private fun assertOccurrencePagingContract(
    script: String,
    styles: String,
) {
    script shouldContain "var previous = occurrenceButton(\"Previous\""
    script shouldContain "var next = occurrenceButton(\"Next\""
    script shouldContain "var allOccurrences = occurrences.slice()"
    script shouldContain "occurrenceFileGroups(allOccurrences)"
    script shouldContain "occurrenceDistinctLineCount(allOccurrences)"
    script shouldContain "if (activeIndex > 0) activeIndex -= 1"
    script shouldContain "if (activeIndex + 1 < allOccurrences.length) activeIndex += 1"
    script shouldContain "pageStatus.setAttribute(\"aria-live\", \"polite\")"
    script shouldContain "pageStatus.textContent = \"Displayed relationship record \" + (activeIndex + 1)"
    script shouldContain "previous.disabled = activeIndex === 0"
    script shouldContain "next.disabled = activeIndex + 1 === allOccurrences.length"
    script shouldContain "button.dataset.srcxOccurrenceFile = group.key"
    script shouldContain "activeIndex = group.firstIndex"
    script shouldContain "relationshipLineCounts: occurrenceLineCounts(allOccurrences, activeFileKey)"
    script shouldContain "occurrenceSourceFileKey(occurrence)"
    script shouldContain "sourceFileForEvidence(evidence)"
    script shouldContain "navigateToDeclaration(declaration)"
    styles shouldContain ".srcx-dashboard__architecture-occurrence-controls"
    styles shouldContain ".srcx-dashboard__architecture-occurrence-status"
}

private fun assertAtlasKeyboardContract(script: String) {
    script shouldContain "state.rovingNodeId"
    script shouldContain "state.rovingEdgeId"
    script shouldContain "focusIncidentEdge(node)"
    script shouldContain "focusAdjacentEdge(edge"
    script shouldContain "focusEdgeEndpoint(edge"
    script shouldContain "event.key === \"r\" || event.key === \"R\""
    script shouldContain "candidate.id === edge.id ? 0 : -1"
    script shouldContain "candidate.id === node.id ? 0 : -1"
}

private fun assertAtlasMotionAndResponsiveContract(
    script: String,
    styles: String,
    themeStyles: String,
) {
    script shouldContain "wireDocumentNavigation();"
    script shouldContain "behavior: reducedMotion() ? \"auto\" : \"smooth\""
    styles shouldContain "transform: translateX(calc(100% + 14px))"
    styles shouldContain ".srcx-dashboard__architecture-detail.is-open"
    styles shouldContain ".srcx-dashboard__architecture-svg-node.is-dimmed .srcx-dashboard__architecture-svg-node-dot"
    styles shouldNotContain
        ".srcx-dashboard__architecture-svg-node.is-dimmed .srcx-dashboard__architecture-svg-node-label"
    styles shouldContain "background: var(--srcx-build-color, var(--srcx-tone-solid))"
    styles shouldContain "grid-template-columns: repeat(5, minmax(max-content, 1fr))"
    styles shouldContain "grid-template-columns: repeat(2, minmax(0, 1fr))"
    styles shouldContain ".srcx-dashboard__architecture-zoom button:last-child"
    styles shouldContain "@container (max-width: 940px) and (min-width: 761px)"
    styles shouldContain ".srcx-dashboard__metric-strip .srcx-metric-strip__lead + .srcx-metric"
    themeStyles shouldContain "grid-template-rows: auto 0fr"
    themeStyles shouldContain "grid-template-rows: auto 1fr"
    themeStyles shouldContain "visibility: hidden"
    themeStyles shouldContain "visibility: visible"
    themeStyles shouldContain "@media (prefers-reduced-motion: reduce)"
    themeStyles shouldContain ".srcx-theme *::after"
    themeStyles shouldContain "transition-duration: 0.01ms !important"
}

private fun assertAtlasNavigatorContract(
    script: String,
    styles: String,
) {
    assertAtlasNavigatorScopeContract(script)
    assertAtlasNavigatorInteractionContract(script, styles)
    assertAtlasNavigatorStyleContract(styles)
    script shouldContain "if (pathFilter) wirePathFilter()"
    script shouldContain "if (pathFilter) renderPathFilter()"
    script shouldContain "if (!pathFilter) return true"
    script shouldNotContain "!projectFilter || !sourceSetFilter || !pathFilter"
}

private fun assertAtlasNavigatorScopeContract(script: String) {
    script shouldContain "selectedBuild: null"
    script shouldContain "selectedProject: null"
    script shouldContain "selectedSourceSet: null"
    script shouldContain "function renderNavigator(focusKind)"
    script shouldContain "function projectsForSelectedBuild()"
    script shouldContain "function sourceSetsForGraphContext()"
    script shouldContain "return project ? project.sourceSets.slice().sort(sourceSetOrder) : []"
    script shouldContain "return build.sourceSets.slice().sort(sourceSetOrder)"
    script shouldContain "return build.sourceSets"
    script shouldContain "function sourceSetOrder(left, right)"
    script shouldContain "function graphEntitiesForSourceSet(sourceSet)"
    script shouldContain "function applyGraphFilters(selectedBuild, selectedProject, focusKind)"
    script shouldContain "function applySourceSetFilter(sourceSet)"
    script shouldContain "function clearGraphContext(focusKind)"
    script shouldContain "var builds = data.builds.slice()"
    script shouldNotContain "data.builds.slice().sort"
    script shouldContain "if (!state.selectedBuild) return []"
    script shouldContain "return build ? build.projects.slice().sort"
    script shouldContain
        "projectName === \":\" ? String(buildName || \"Build\").toUpperCase() + " +
        "\" · : (root project)\" :"
    script shouldContain "title: projectDisplayName(project.name, selectedBuild.name)"
    script shouldContain "title: \"All builds\""
    script shouldContain "title: \"All projects\""
    script shouldContain "title: \"All source sets\""
    script shouldContain "var totalIndexedFiles = builds.reduce"
    script shouldContain "return sum + build.indexedSymbolCount"
    script shouldContain "function indexedOverviewSummary(scope)"
    script shouldContain "return scope.indexedFileCount + \" indexed files / \" + scope.fileNodeCount +"
    script shouldContain "\" shown in the All overview / \" + scope.indexedSymbolCount"
    script shouldContain
        "scope.symbolNodeCount + \" shown in the All overview; selecting this scope opens one complete frame\""
    script shouldContain "select a build or project for one complete scoped frame"
    script shouldContain "indexedOverviewSummary(build)"
    script shouldContain "indexedOverviewSummary(selectedBuild)"
    script shouldContain "summary: indexedOverviewSummary(project)"
}

private fun assertAtlasNavigatorInteractionContract(
    script: String,
    styles: String,
) {
    script shouldContain "!Number.isInteger(build.indexedFileCount) || build.indexedFileCount < 0"
    script shouldContain "!Number.isInteger(build.indexedSymbolCount) || build.indexedSymbolCount < 0"
    script shouldContain "!uniqueStringArray(build.sourceSets)"
    script shouldContain "Number.isInteger(project.indexedFileCount) && project.indexedFileCount >= 0"
    script shouldContain "Number.isInteger(project.indexedSymbolCount) && project.indexedSymbolCount >= 0"
    script shouldContain "uniqueStringArray(project.sourceSets)"
    script shouldContain "var key = options.value === null ? \"all\" : options.value"
    script shouldContain "button.dataset.srcxFilterBuild = key"
    script shouldContain "button.dataset.srcxFilterProject = key"
    script shouldContain "button.dataset.srcxFilterSourceSet = key"
    script shouldContain "var primaryNodes = allNodes.filter(matchesGraphContext)"
    script shouldContain "var retainIncidentContext = refillCatalog && graphContextRestricted()"
    script shouldContain
        "return retainIncidentContext ? sourceInScope || targetInScope : sourceInScope && targetInScope"
    script shouldContain "if (retainIncidentContext) scopedLinks.forEach(function (edge)"
    script shouldContain "scopedIds.add(endpointId(edge.source))"
    script shouldContain "scopedIds.add(endpointId(edge.target))"
    script shouldContain "var filteredCandidateNodeCount = filteredNodes.length"
    script shouldContain "candidateNodeCount: filteredCandidateNodeCount"
    script shouldContain "state.symbolIndexedCount = primaryNodes.length"
    script shouldContain "var availableFileNodes = catalogOrShown(data.availableFileNodes, data.fileNodes)"
    script shouldContain "var availableFileEdges = catalogOrShown(data.availableFileEdges, data.fileEdges)"
    script shouldContain "function scopedFileRefillActive(relationshipKind)"
    script shouldContain "function scopedSymbolRefillActive(relationshipKind)"
    script shouldContain "function projectionForView(relationshipKindOverride)"
    script shouldContain "var symbolCatalogNodes = refillSymbols ? expandedSymbols.nodes : data.nodes"
    script shouldContain "var symbolCatalogEdges = refillSymbols ? expandedSymbols.edges : data.edges"
    script shouldContain "state.fileIndexedCount = primaryNodes.length"
    script shouldContain "function scopedInternalRecordCount(node, relationshipKind)"
    script shouldContain "if (node.entityType !== \"file\" || node.isScopeContext) return 0"
    script shouldContain "return sum + scopedInternalRecordCount(node, relationshipKind)"
    script shouldContain "var linkedInFrame = links.filter(function (edge)"
    script shouldContain
        "var internalInFrame = scopedInternalRecordCount(node, state.selectedRelationshipKind)"
    script shouldContain "var displayed = linkedInFrame + internalInFrame"
    script shouldContain "attached records are represented in this frame"
    script shouldContain "same-file records have evidence but no arrow"
    script shouldNotContain "records are drawn in the bounded Files lens"
    script
        .substringAfter("function projectionForView(relationshipKindOverride)")
        .substringBefore("function scopedFileRefillActive(relationshipKind)")
        .shouldNotContain("primaryNodes")
    script shouldContain "if (state.selectedBuild && node.build !== state.selectedBuild) return false"
    script shouldContain "if (state.selectedProject && node.project !== state.selectedProject) return false"
    script shouldContain "if (state.selectedSourceSet && node.sourceSet !== state.selectedSourceSet) return false"
    script shouldContain "ids.has(endpointId(edge.source)) && ids.has(endpointId(edge.target))"
    script shouldContain "button.setAttribute(\"aria-pressed\", String(options.selected))"
    script shouldContain "button.tabIndex = options.selected ? 0 : -1"
    script shouldContain "rail.setAttribute(\"aria-orientation\", \"horizontal\")"
    assertAtlasScrollableRailContract(script)
    script shouldContain "root.dataset.srcxGraphBuild = state.selectedBuild || \"all\""
    script shouldContain "root.dataset.srcxGraphProject = state.selectedProject || \"all\""
    script shouldContain "root.dataset.srcxGraphSourceSet = state.selectedSourceSet || \"all\""
    script shouldContain "build.projectCount !== build.projects.length"
    script shouldContain "projectsByBuild.get(node.build).has(node.project)"
    assertAtlasDirectManipulationContract(script, styles)
}

private fun assertAtlasScrollableRailContract(script: String) {
    script shouldContain "function wireFilterRail(rail, kind)"
    script shouldContain "function wireScrollableFilterRail(rail)"
    script shouldContain "var movementThreshold = 6"
    val scrollableRail =
        script
            .substringAfter("function wireScrollableFilterRail(rail)")
            .substringBefore("function moveFilterFocus(")
    scrollableRail
        .substringAfter("function pointerDown(event)")
        .substringBefore("function pointerMove(event)")
        .shouldNotContain("setPointerCapture")
    scrollableRail shouldContain
        "Math.abs(distanceX) < movementThreshold || Math.abs(distanceX) < Math.abs(distanceY)"
    scrollableRail
        .substringAfter("function pointerMove(event)")
        .substringBefore("function pointerEnd(event)")
        .shouldContain("rail.setPointerCapture(event.pointerId)")
    script shouldContain "rail.scrollLeft = gesture.startScrollLeft - distanceX"
    script shouldContain "rail.addEventListener(\"wheel\", horizontalWheel, { passive: false })"
    script shouldContain "event.stopImmediatePropagation()"
    script shouldContain "ArrowLeft"
    script shouldContain "ArrowRight"
    script shouldContain "Home"
    script shouldContain "End"
    script shouldContain "target.focus({ preventScroll: true })"
    script shouldContain "target.scrollIntoView({"
}

private fun assertAtlasDirectManipulationContract(
    script: String,
    styles: String,
) {
    script shouldContain "function ensureSelectionToolbar()"
    script shouldContain "selectionActionButton(\"Box select\""
    script shouldContain "selectionActionButton(\"Select project\""
    script shouldContain "selectionActionButton(\"Select build\""
    script shouldContain "selectionActionButton(\"Frame selection\""
    script shouldContain "function beginLassoSelection(event)"
    script shouldContain "function scopeDragStarted(event, scope, region)"
    Regex("\\.subject\\(function \\(event\\) \\{ return \\{ x: event\\.x, y: event\\.y \\}; \\}\\)")
        .findAll(script)
        .count() shouldBe 2
    script shouldContain "selected.isDirectlyDragged = true"
    script shouldContain "node.isDirectlyDragged = true"
    script shouldContain "if (node.isDirectlyDragged) return"
    script shouldContain "if (!moved && start) restoreNodeDragState(selected, start)"
    script shouldContain "if (!context.moved) restoreNodeDragState(node, start)"
    script shouldContain "function constrainedNodeDragDelta(context, dx, dy)"
    script shouldContain "function constrainedScopeDragDelta(context, dx, dy)"
    script shouldContain "function translateDraggedScopeLayout(context, dx, dy)"
    script shouldContain "function scopeSiblingCells(scope, region)"
    script shouldContain "nodeCircleObstacleBounds(node, 3)"
    script shouldContain "srcx-dashboard__architecture-build-drag-handle"
    script shouldContain "srcx-dashboard__architecture-project-drag-handle"
    script shouldContain "function captureNodeDragState(node)"
    script shouldContain "function restoreNodeDragState(node, stateBeforeDrag)"
    script shouldContain "applyManualScopeOffsets(nodes, layout)"
    script shouldContain "function combinedScopeOffset(build, project)"
    script shouldContain "function accumulateScopeOffset(offsets, key, dx, dy)"
    script shouldContain "function storeManualNodePosition(node)"
    script shouldContain "x: node.x - offset.x, y: node.y - offset.y"
    script shouldContain "node.x = position.x + offset.x"
    script shouldContain "node.y = position.y + offset.y"
    script shouldContain "previous.x + dx"
    script shouldContain "previous.y + dy"
    script shouldContain "if ((node.isDirectlyDragged || node.isManuallyPinned) && node.labelDirection) return"
    val selectionAnchor =
        script
            .substringAfter("function selectionAnchorNode()")
            .substringBefore("function selectScopeNodes(scope)")
    (
        selectionAnchor.indexOf("state.selectedNodeIds.has(node.id)") <
            selectionAnchor.indexOf("state.selectedId && nodes.find")
    ) shouldBe true
    script shouldContain "function resetGraphLayout()"
    script shouldContain "manualNodePositions.clear()"
    assertAtlasDirectNeighborhoodContract(script, styles)
}

private fun assertAtlasDirectNeighborhoodContract(
    script: String,
    styles: String,
) {
    script shouldContain "function directNodeNeighborhood(nodeId)"
    script shouldContain "function enterNodeNeighborhoodFocus(nodeId)"
    script shouldContain "function exitNodeNeighborhoodFocus(restoreTransform)"
    script shouldContain "function neighborhoodNodeSimulation(focusNodes, focusLinks, focusLayout)"
    script shouldContain "function neighborhoodLinkDistance(edge)"
    script shouldContain "function applyNeighborhoodVisibility(visibleIds)"
    script shouldContain "root.dataset.srcxGraphFocus = \"direct-neighborhood\""
    script shouldContain "nodeGroups.classed(\"is-neighborhood-hidden\""
    script shouldContain "buildGroups.classed(\"is-neighborhood-hidden\""
    script shouldContain "projectGroups.classed(\"is-neighborhood-hidden\""
    script shouldContain "layout = buildCellLayout(neighborhood.nodes, width, height, buildByName)"
    script shouldContain "constrainNodesToBuildCells("
    script shouldContain "frameNodes(neighborhood.nodes, \"direct neighborhood\", neighborhood.links)"
    script shouldContain "setNodeSelection([node.id])"
    script shouldContain "exitNodeNeighborhoodFocus(true)"
    script shouldContain "restoreNodeDragState(node, position)"
    script shouldContain "node.subgroupKey = position.subgroupKey"
    styles shouldContain ".srcx-dashboard__architecture-svg-node.is-neighborhood-hidden"
    styles shouldContain ".srcx-dashboard__architecture-build-region.is-neighborhood-hidden"
    styles shouldContain ".srcx-dashboard__architecture-project-region.is-neighborhood-hidden"
    styles shouldContain "visibility: hidden"
    styles shouldContain "pointer-events: none"
}

private fun assertAtlasNavigatorStyleContract(styles: String) {
    styles shouldContain ".srcx-dashboard__architecture-navigator"
    styles shouldContain ".srcx-dashboard__architecture-filter {"
    styles shouldContain ".srcx-dashboard__architecture-filter-rail {"
    styles shouldContain ".srcx-dashboard__architecture-filter-button {"
    styles shouldContain "overflow-x: auto"
    styles shouldContain "overscroll-behavior-inline: contain"
    styles shouldContain "scroll-snap-type: x proximity"
    styles shouldContain "cursor: grab"
    styles shouldContain ".srcx-dashboard__architecture-filter-rail.is-dragging"
    styles shouldContain "white-space: nowrap"
    styles shouldContain "flex: 0 0 auto"
    styles shouldContain "@container (max-width: 600px)"
    val draggableBuildRegion =
        ".srcx-dashboard__architecture-build-region {\n" +
            "    pointer-events: all;\n" +
            "}"
    styles shouldContain draggableBuildRegion
    styles shouldNotContain
        ".srcx-dashboard__architecture-build-region {\n" +
        "    pointer-events: none;\n" +
        "}"
    styles shouldContain "grid-template-columns: 1fr"
    styles shouldContain ".srcx-dashboard__architecture-filter--source-sets"
    styles shouldContain ".srcx-dashboard__architecture-filter-button[aria-pressed=\"true\"]"
    styles shouldContain "inset 0 0 0 3px var(--srcx-accent)"
    styles shouldContain ".srcx-dashboard__architecture-filter-panel"
    styles shouldContain ".srcx-dashboard__architecture-filter-groups"
    styles shouldContain "@container (max-width: 390px)"
    styles shouldContain "@container (max-width: 768px)"
    styles shouldContain "@container (max-width: 1024px)"
    styles shouldContain "@container (max-width: 1440px)"
    styles shouldContain "overflow-y: visible"
}

private fun assertAtlasLabelFitContract(
    script: String,
    styles: String,
) {
    script shouldContain "semanticLabelParts"
    script shouldContain "wrapVisibleLabel"
    script shouldContain ".replace(/([a-z0-9])([A-Z])/g, \"${'$'}1\\u0000${'$'}2\")"
    script shouldContain ".replace(/([/_.-]+)/g, \"${'$'}1\\u0000\")"
    script shouldContain ".split(\"\\u0000\")"
    script shouldContain "line += part"
    script shouldContain "line = part"
    script shouldContain "measureNodeLabels"
    script shouldContain "labelBounds"
    script shouldContain "collisionRadius"
    script shouldContain "nodeVisualBounds"
    script shouldContain "graphBounds"
    script shouldContain "renderedGraphBounds(zoomLayer.node())"
    script shouldContain "var measuredBoundaryPadding = 48"
    script shouldContain "unionGraphBounds(renderedBounds, measuredBounds)"
    script shouldContain "var fitRect = graphFitRect(measuredBoundaryPadding)"
    script shouldContain "Math.min(fitRect.width / bounds.width, fitRect.height / bounds.height)"
    script shouldContain "var minReadableScale = 0.86"
    script shouldContain "var scale = Math.max(minimumZoomScale, Math.min(maximumZoomScale, fittedScale))"
    script shouldContain "var scale = Math.max(minReadableScale, Math.min(maximumZoomScale, fittedScale))"
    script shouldNotContain "Fit shows the complete frame; zoom or hover to read labels on small nodes."
    script shouldNotContain "if (fittedScale < minReadableScale)"
    script shouldNotContain "boxWidth = Math.min(boxWidth, 88)"
    script shouldNotContain "boxHeight = Math.min(boxHeight, 26)"
    script shouldContain "var viewportAspect = Math.max(0.75, width / Math.max(1, height))"
    script shouldContain "var compactSeed = width <= 480"
    script shouldContain "function layoutSeedToViewport(nodes, width, height, buildByName)"
    script shouldContain "function packLabeledSeedGrid(rectangles, tile)"
    script shouldContain "function seedLabelColumns(width)"
    script shouldContain "function seedLabelStackHeight(count, width)"
    script shouldContain "var cols = tile.cols || seedLabelColumns(tile.w)"
    script shouldContain "wrapVisibleLabel(nodeLabel(node), 22)"
    script shouldContain "if (width > 480) {\n" +
        "                assignLabelDirections(positionedNodes, layout.cells, layout.subgroupCells)"
    script shouldContain "if (compactSeed) return layoutSeedToViewport(nodes, width, height, buildByName)"
    script shouldContain "if (window.matchMedia(\"(max-width: 480px)\").matches) {\n" +
        "                svg.call(zoom.transform, d3.zoomIdentity);"
    script shouldContain "Math.min(minimumZoomScale, scale / 2)"
    script shouldContain "Math.max(currentScaleExtent[1], maximumZoomScale, scale * 2)"
    script shouldContain "detail.getBoundingClientRect().width + 18"
    script shouldContain "width - detailOcclusion - padding * 2"
    script shouldContain "node.labelWidth"
    script shouldContain "node.labelHeight"
    script shouldContain "bounds.minX"
    script shouldContain "bounds.maxX"
    script shouldContain "bounds.minY"
    script shouldContain "bounds.maxY"
    script shouldNotContain "remaining.slice(0, limit)"
    script shouldNotContain "part.slice(0, limit)"
    styles shouldContain
        ".srcx-dashboard__architecture-svg-node.is-dimmed {\n" +
        "    opacity: 1;\n}"
    styles shouldContain
        ".srcx-dashboard__architecture-svg-node.is-dimmed .srcx-dashboard__architecture-svg-node-dot"
    styles shouldNotContain
        ".srcx-dashboard__architecture-svg-node.is-dimmed .srcx-dashboard__architecture-svg-node-label"
    styles shouldContain ".srcx-dashboard__architecture-subgroup-region rect"
    styles shouldContain ".srcx-dashboard__architecture-subgroup-region text"
    styles shouldContain ".srcx-dashboard__architecture-svg-node.is-signal-low"
    styles shouldContain ".srcx-dashboard__architecture-svg-node.is-signal-medium"
    styles shouldContain ".srcx-dashboard__architecture-svg-node.is-signal-high"
    styles shouldNotContain
        ".srcx-dashboard__architecture-graph[data-srcx-graph-lens=\"files\"]\n" +
        "    .srcx-dashboard__architecture-svg-node-label {\n" +
        "    opacity: 1;"
}

private fun assertAtlasCycleAndProblemContract(
    script: String,
    styles: String,
) {
    assertAtlasSemanticBadgeContract(script, styles)
    script shouldContain "appendNodeBadges"
    script shouldContain "Why this node looks this way"
    script shouldContain "Dark ring: report-priority / ranking signal"
    script shouldContain "Report-priority signal"
    script shouldContain "Red ring: "
    script shouldContain " exact file findings"
    script shouldContain "Red dashed ring: observed file-cycle member"
    script shouldContain "Exact file findings"
    script shouldContain "Cycle participation"
    (script.indexOf("Exact file findings") < script.indexOf("Cycle participation")) shouldBe true
    script shouldContain
        "No exact file findings. This participant is in Problems because of cycle evidence."
    script shouldContain "var exactFindingFiles = fileNodes.filter(function (node) {"
    script shouldContain "return node.fileFindingCount > 0;"
    script shouldContain "fileNodes = mergeNodes(exactFindingFiles, fileNodes)"
    script shouldContain "state.view === \"problems\" || state.view === \"cycles\" || Boolean("
    script shouldContain "never invent an omitted target"
    script shouldContain
        "if (!nodesById.has(occurrence.sourceSymbolId) || !nodesById.has(occurrence.targetSymbolId)) return"
    script shouldNotContain "extract an interface"
    script shouldNotContain "use the concrete class"
    script shouldContain "function appendCycleRoutes(parent, node, cycles)"
    script shouldContain "Directed cycle routes"
    script shouldContain "Route "
    script shouldContain " \\u2192 "
    script shouldContain "Routes containing this file are rotated to start and end here; related "
    script shouldContain "routes complete the compound cycle group. Step through each distinct directed edge once"
    script shouldContain "function cycleRoutesForNode(node, cycles)"
    script shouldContain "Number(right.containsSelectedFile) - Number(left.containsSelectedFile)"
    script shouldContain "containsSelectedFile: false"
    script shouldContain "containsSelectedFile: true"
    script shouldContain "var seenCycleEdgeIds = new Set()"
    script shouldContain "if (seenCycleEdgeIds.has(edgeId)) return"
    script shouldContain "seenCycleEdgeIds.add(edgeId)"
    script shouldContain "function renderActiveCycleStep()"
    script shouldContain "cycleStepEdgeId: null"
    script shouldContain "state.cycleStepEdgeId = visibleEdge ? visibleEdge.id : null"
    script shouldContain "highlightEdge(visibleEdge)"
    script shouldContain
        "occurrenceButton(\"Previous relationship\", \"Previous directed cycle relationship\")"
    script shouldContain
        "occurrenceButton(\"Next relationship\", \"Next directed cycle relationship\")"
    script shouldContain "previous.dataset.srcxCyclePrevious = \"\""
    script shouldContain "next.dataset.srcxCycleNext = \"\""
    script shouldContain "pageStatus.setAttribute(\"aria-live\", \"polite\")"
    script shouldContain "previous.disabled = activeIndex === 0"
    script shouldContain "next.disabled = activeIndex + 1 === steps.length"
    script shouldContain "This directed edge is outside the current map filter; exact "
    script shouldContain "relationship evidence remains available below."
    script shouldContain "var evidenceEdge = visibleEdge || edge"
    script shouldContain "evidenceEdge.occurrences || []"
    script shouldContain "cycleEdge: cycleEdges.has(edge.id)"
    script shouldContain "if (edge.cycleEdge) classes += \" is-cycle-edge\""
    script shouldContain "cycle.edgeIds.every(function (edgeId) {"
    script shouldContain "return cycle.routes.some(function (route) { return route.edgeIds.includes(edgeId); });"
    styles shouldContain ".srcx-dashboard__architecture-node-badges"
    styles shouldContain ".srcx-dashboard__architecture-cycle-routes"
    styles shouldContain ".srcx-dashboard__architecture-cycle-controls"
}

private fun assertAtlasSemanticBadgeContract(
    script: String,
    styles: String,
) {
    script shouldContain "function declarationSemantic(node)"
    script shouldContain "function semanticBadgeLabel(node)"
    script shouldContain "function semanticImplementationFact(node)"
    script shouldContain "SINGLETON_OBJECT"
    script shouldContain "sourceId: occurrence.sourceSymbolId || endpointId(edge.source)"
    script shouldContain "return record.sourceId ||"
    script shouldContain "return record.sourceId;"
    script shouldContain "displayedIds.has(id)"
    script shouldContain "No resolved implementation record is present in the typed symbol catalog"
    script shouldContain "SRCX does not infer why this declaration exists"
    script shouldContain "declarationSemantic(node)"
    script shouldContain "srcx-dashboard__architecture-semantic-marker"
    script shouldContain ".text(\"■\")"
    script shouldContain "colored square before the name shows"
    script shouldNotContain "is-declaration-kind-ring"
    script shouldNotContain "srcx-dashboard__architecture-semantic-badge"
    styles shouldContain ".srcx-dashboard__architecture-semantic-marker"
    styles shouldContain ".is-singleton-object"
}

private fun assertAtlasScopedSymbolsAndPaletteContract(
    script: String,
    styles: String,
    themeStyles: String,
) {
    script shouldContain "function expandedSymbolProjection()"
    script shouldContain "availableSymbolNodes = catalogOrShown(data.availableNodes, data.nodes)"
    script shouldContain "availableSymbolEdges = catalogOrShown(data.availableEdges, data.edges)"
    script shouldContain "fileNode.symbols || []"
    script shouldContain "node.internalOccurrences || []"
    script shouldContain "nodesById.set(node.id, Object.assign({}, node, {"
    script shouldContain "workspaceInbound: 0"
    script shouldContain "outgoingRecordCount: 0"
    script shouldContain "var projection = projectionForView(categoryId)"
    script shouldContain "filteredLinks = symbolLinks"
    script shouldContain "state.symbolRelationshipRecordCount"
    script shouldContain
        "Symbols show every declaration in the selected build or project scope in one frame"
    script shouldContain "Files show the complete selected build or project scope in one frame"
    script shouldNotContain "filePage"
    script shouldNotContain "symbolPage"
    script shouldNotContain "projectionPages("
    script shouldNotContain "skipSymbolPaging"
    script shouldNotContain "function wireThemeSelectors()"
    script shouldNotContain "window.localStorage.setItem(\"srcx-color-theme\", theme)"
    styles shouldContain "background: var(--srcx-code-bg)"
    styles shouldNotContain ".srcx-dashboard__architecture-symbol-page-status"
    themeStyles shouldContain "--srcx-paper: #f3efe4"
    themeStyles shouldContain "--srcx-surface: #fbf7ec"
    themeStyles shouldContain "color-scheme: light"
    themeStyles shouldNotContain "data-srcx-color-theme"
    themeStyles shouldNotContain "catppuccin"
    themeStyles shouldNotContain "everforest"
}

private fun assertAtlasAnalysisCycleContract(
    script: String,
    styles: String,
) {
    script shouldContain "var analysisCycleById = new Map(data.analysisCycles.map"
    script shouldContain "var findingById = new Map(data.findings.map"
    script shouldContain "var analysisFileMembers = new Set(data.analysisCycles.flatMap"
    script shouldContain "var analysisSymbolMembers = new Set(data.analysisCycles.flatMap"
    script shouldContain "function analysisCycleProjection(fileNodes, symbolNodes)"
    script shouldContain "id: \"analysis-edge::\" + cycle.id + \"::\" + index"
    script shouldContain "entityType: \"analysis-cycle-edge\""
    script shouldContain "function analysisCyclesForNode(node)"
    script shouldContain "function appendAnalysisCycleParticipationSection(parent, node, analysisCycles)"
    script shouldContain "Analyzer-inferred component-cycle participation"
    script shouldContain "directional analysis signals, not resolved relationship records"
    script shouldContain "function appendAnalysisCycleSection(parent, node, analysisCycles)"
    script shouldContain "Analyzer-inferred component cycle"
    script shouldContain "Qualified analyzer route; directional component evidence only. Its hops "
    script shouldContain "are not resolved relationship records and do not add to relationship counts."
    script shouldContain "function appendAnalysisCycleParticipants(parent, cycle)"
    script shouldContain "button.dataset.srcxAnalysisParticipant = component.componentId"
    script shouldContain "Open participant source"
    script shouldContain "function showAnalysisParticipantEvidence(component, cycle, index, focusViewer, parent)"
    script shouldContain "reason: \"Analyzer cycle participant \" + (index + 1)"
    script shouldContain "function renderAnalysisCycleEdgeDetail(edge)"
    script shouldContain "ANALYZER_INFERRED / directional component-analysis signal"
    script shouldContain "Not applicable; this hop is not a resolved relationship record"
    script shouldContain "if (isAnalysisCycleEdge(edge)) return 2.4"
    script shouldContain
        "var relationshipLinks = projection.links.filter(function (edge) { return !isAnalysisCycleEdge(edge); })"
    script shouldContain "var analysisLinks = projection.links.filter(isAnalysisCycleEdge)"
    script shouldContain "function validAnalysisEvidence(data, buildNames, projectsByBuild)"
    script shouldContain "!uniqueStringArray(finding.componentSymbolIds)"
    script shouldContain "!uniqueStringArray(finding.componentFileIds)"
    script shouldContain "!uniqueStringArray(cycle.memberSymbolIds)"
    script shouldContain "!uniqueStringArray(cycle.memberFileIds)"
    script shouldContain "cycle.evidence !== \"ANALYZER_INFERRED\""
    script shouldContain "cycle.componentIds[0] !== cycle.componentIds[cycle.componentIds.length - 1]"
    script shouldContain "sameMembers(routeSymbolIds, cycle.memberSymbolIds)"
    script shouldContain "sameMembers(routeFileIds, cycle.memberFileIds)"
    script shouldContain "finding.componentFileIds.every(function (id) { return cycle.memberFileIds.includes(id); })"
    script shouldContain "finding.analysisCycleId === cycle.id"
    script shouldContain "No participant declaration source is available in this bounded report. "
    script shouldContain "The complete qualified analyzer route remains listed above."
    script shouldNotContain "uniqueStringArray(finding.componentSymbolIds, symbolNodeIds)"
    script shouldNotContain "uniqueStringArray(finding.componentFileIds, fileNodeIds)"
    script shouldContain "Violet dotted ring: analyzer-inferred component-cycle participant"
    styles shouldContain ".srcx-dashboard__architecture-svg-node.has-analysis-cycle .is-analysis-cycle-ring"
    styles shouldContain ".srcx-dashboard__architecture-svg-edge.is-analysis-cycle-edge"
    styles shouldContain ".srcx-dashboard__architecture-analysis-cycle-routes"
    styles shouldContain ".srcx-dashboard__architecture-analysis-cycle-participants"
}

private fun assertAtlasSourceDetailResizeContract(
    script: String,
    styles: String,
) {
    val openDetail = script.substringAfter("function openDetail(").substringBefore("function closeDetail()")

    script shouldContain "detailWidth: null"
    script shouldContain "function preferredSourceWidth()"
    script shouldContain "context.measureText(\"0\").width"
    script shouldContain "characterWidth * 140 + 96"
    script shouldContain "function detailWidthBounds()"
    script shouldContain "Math.floor(availableWidth * 0.5)"
    script shouldContain "Math.max(280, Math.min(480, Math.floor(availableWidth * 0.3)))"
    script shouldContain "min: Math.min(maximum, practicalMinimum)"
    script shouldNotContain "min: Math.min(maximum, preferredSourceWidth())"
    script shouldContain "function clampDetailWidth(value, bounds)"
    script shouldContain "Math.max(bounds.min, Math.min(bounds.max, Math.round(requested)))"
    script shouldContain "function applyDetailWidth(value, shouldPersist)"
    script shouldContain "state.detailWidth = nextWidth"
    script shouldContain "root.style.setProperty(\"--srcx-detail-width\", nextWidth + \"px\")"
    script shouldContain "function persistDetailWidth()"
    script shouldContain "window.localStorage.setItem(detailWidthStorageKey, String(state.detailWidth))"
    script shouldContain "function restoreDetailWidth()"
    script shouldContain "window.localStorage.getItem(detailWidthStorageKey)"
    script shouldContain "function reclampDetailWidth()"
    script shouldContain "function wireDetailResize()"
    script shouldContain "detailResize.addEventListener(\"pointerdown\""
    script shouldContain "window.addEventListener(\"pointermove\""
    script shouldContain "window.addEventListener(\"pointerup\""
    script shouldContain "detailResize.setPointerCapture"
    script shouldContain "detailResize.addEventListener(\"keydown\""
    script shouldContain "ArrowLeft"
    script shouldContain "ArrowRight"
    script shouldContain "Home"
    script shouldContain "End"
    script shouldContain "detailResize.setAttribute(\"aria-valuemin\""
    script shouldContain "detailResize.setAttribute(\"aria-valuemax\""
    script shouldContain "detailResize.setAttribute(\"aria-valuenow\""
    script shouldContain "detailResize.setAttribute(\"aria-valuetext\""
    script shouldContain "\" pixels, \" + percent +"
    script shouldContain "function resize() {\n            reclampDetailWidth()"
    script shouldContain "function scheduleFullscreenFit()"
    script shouldContain "reclampDetailWidth();\n                resize();\n                fitGraph();"
    script shouldContain "restoreDetailWidth()"
    script shouldContain "applyDetailWidth(preferredSourceWidth(), true)"
    script shouldContain "window.removeEventListener(\"pointermove\", handleDetailResizePointerMove)"
    script shouldContain "viewer.addEventListener(\"wheel\", routeSourceWheelToDrawer, { passive: false })"
    script shouldContain "function routeSourceWheelToDrawer(event)"
    script shouldContain "detail.scrollTop += event.deltaY"
    openDetail shouldNotContain "detailWidth"
    openDetail shouldNotContain "--srcx-detail-width"
    assertAtlasSourceDetailResizeStyles(styles)
    script shouldContain "var drawer = viewer.closest(\"[data-srcx-detail]\")"
    script shouldContain "drawer.scrollTo({ top: top, behavior: reducedMotion() ? \"auto\" : \"smooth\" })"
    script shouldNotContain "viewer.scrollTo({ top: top"
}

private fun assertAtlasSourceDetailResizeStyles(styles: String) {
    val resizableDrawerStyles = styles.substringAfter("/* Resizable Atlas source evidence drawer */")
    val detailResizeStyles =
        resizableDrawerStyles
            .substringAfter(".srcx-dashboard__architecture-detail-resize {")
            .substringBefore("}")
    val sourceViewerStyles =
        resizableDrawerStyles
            .substringAfter(".srcx-dashboard__architecture-source-viewer {")
            .substringBefore("}")
    val singleScrollStyles =
        styles.substringAfter("/* The drawer is the only vertical scroll surface").substringBefore("@container")

    resizableDrawerStyles shouldContain "max-width: 50%"
    resizableDrawerStyles shouldContain "width: var(--srcx-detail-width, min(50%, 920px))"
    detailResizeStyles shouldContain "cursor: ew-resize"
    detailResizeStyles shouldContain "right: calc(var(--srcx-detail-width, min(50%, 920px)) - 7px)"
    detailResizeStyles shouldContain "touch-action: none"
    sourceViewerStyles shouldContain "overflow-x: auto"
    singleScrollStyles shouldContain ".srcx-dashboard__architecture-source-viewer"
    singleScrollStyles shouldContain "max-height: none"
    singleScrollStyles shouldContain "overflow-x: auto"
    singleScrollStyles shouldContain "overflow-y: hidden"
    singleScrollStyles shouldContain "overscroll-behavior-y: auto"
    singleScrollStyles shouldContain "touch-action: pan-x pan-y"
}

private fun assertAtlasSingleEvidenceContract(
    script: String,
    styles: String,
) {
    assertAtlasEvidenceStateContract(script)
    assertAtlasSelectedEvidenceContract(script)
    assertAtlasSelectionOnlyEvidenceContract(script)
    assertAtlasEvidenceNoiseContract(script, styles)
}

private fun assertAtlasEvidenceStateContract(script: String) {
    val activeEvidence =
        script.substringAfter("function renderActiveEvidence()").substringBefore("function appendSourceViewer(")

    script shouldContain "selectedEvidence: null"
    script shouldContain "previewEvidence: null"
    script shouldContain "function setSelectedEvidence(evidence)"
    script shouldContain "function renderActiveEvidence()"
    script shouldContain "function bindEvidenceHost(parent)"
    activeEvidence shouldContain "var evidence = state.previewEvidence || state.selectedEvidence"
    script shouldContain "evidenceStatus.dataset.srcxSourceEvidenceStatus = \"\""
    script shouldContain "evidenceViewer.dataset.srcxSourceEvidenceViewer = \"\""
    script shouldContain "evidenceStatus.setAttribute(\"role\", \"status\")"
    script shouldContain "evidenceStatus.setAttribute(\"aria-live\", \"polite\")"
    script shouldContain "activeEvidenceStatus.textContent = \"No source evidence is active.\""
    script shouldContain "previewing ? \"Preview evidence / \" : \"Selected evidence / \""
    script shouldContain "activeType: evidence.kind"
    script shouldContain "if (lineNumber === options.activeLine)"
    script shouldContain "row.setAttribute(\"aria-current\", \"true\")"
}

private fun assertAtlasSelectedEvidenceContract(script: String) {
    val fileDetail =
        script.substringAfter("function renderFileDetail(node)").substringBefore("function renderSymbolDetail(node)")
    val symbolDetail =
        script.substringAfter("function renderSymbolDetail(node)").substringBefore("function renderEdgeDetail(edge)")
    val occurrencePager =
        script.substringAfter("function renderOccurrencePager(").substringBefore("function occurrenceButton(")

    fileDetail shouldContain "kind: \"file\""
    fileDetail shouldContain "sourceFileKey: sourceFileKey(sourceFile)"
    fileDetail shouldContain "line: null"
    symbolDetail shouldContain "kind: \"declaration\""
    symbolDetail shouldContain "sourceFileKey: sourceFileKey(sourceFile)"
    symbolDetail shouldContain "line: node.line"
    occurrencePager shouldContain "kind: \"relationship\""
    occurrencePager shouldContain "sourceFileKey: activeFileKey"
    occurrencePager shouldContain "line: occurrence.line"
    script shouldContain "function appendFindingSection("
    script shouldContain "selectedFindingId,"
    script shouldContain "analyzerInferred,"
    script shouldContain "if (selectedFindingId === finding.id)"
    script shouldContain "select.dataset.srcxFindingEvidence = finding.id"
    script shouldContain
        "select.setAttribute(\"aria-pressed\", " +
        "String(row.classList.contains(\"is-selected-evidence\")))"
    script shouldContain "candidate.setAttribute(\"aria-current\", \"true\")"
    script shouldContain "candidateButton.setAttribute(\"aria-pressed\", String(selected))"
    script shouldContain "setSelectedEvidence(findingEvidence(finding, node))"
}

private fun assertAtlasSelectionOnlyEvidenceContract(script: String) {
    script shouldContain ".on(\"mouseenter\", function (event, edge)"
    script shouldContain ".on(\"focus\", function (event, edge)"
    script shouldContain "function previewRelationshipEvidence(edge, interaction)"
    script shouldContain "function clearRelationshipPreview()"
    script shouldContain "function restoreRelationshipPreview(edge)"
    script shouldContain "detail.classList.contains(\"is-open\")"
    script shouldContain "previewRelationshipEvidence(edge, \"Hovered relationship\")"
    script shouldContain "previewRelationshipEvidence(edge, \"Focused relationship\")"
    script shouldContain "state.cycleStepEdgeId === edge.id ? \"Cycle relationship occurrence \""
    script shouldContain "var evidenceEdge = visibleEdge || edge"
    script shouldContain "evidenceEdge.occurrences || []"
    script shouldContain "state.previewEvidence = null"
    script shouldNotContain "previewEdgeEvidence"
    script shouldNotContain "openDetail(\"Relationship preview\""
}

private fun assertAtlasToolbarReadabilityContract(
    styles: String,
    themeStyles: String,
) {
    val polish = styles.substringAfter("/* Atlas toolbar and reading-size polish */")

    themeStyles shouldContain "font-size: 15px"
    polish shouldContain "\"lenses kinds search actions\""
    polish shouldContain "grid-template-columns: max-content max-content minmax(280px, 1fr) max-content"
    polish shouldContain "grid-area: actions"
    polish shouldContain "width: max-content"
    polish shouldContain "inline-size: 44px"
    polish shouldContain "min-inline-size: 44px"
    polish shouldContain "aspect-ratio: 1"
    polish shouldContain "inline-size: 52px"
    polish shouldContain "inline-size: 62px"
    polish shouldContain "inline-size: 104px"
    polish shouldContain "grid-area: search"
    polish shouldContain "min-width: 280px"
    polish shouldContain "font-size: 11px"
    polish shouldContain "font-size: 14px"
    polish shouldContain "@container (max-width: 1250px)"
    polish shouldContain "@container (max-width: 1000px) and (min-width: 761px)"
    polish shouldContain "@container (max-width: 2200px) and (min-width: 1001px)"
    polish shouldContain "\"lenses actions\""
    polish shouldContain "\"kinds search\""
    polish shouldContain "grid-template-columns: repeat(2, minmax(0, 1fr));"
    polish shouldContain "@container (max-width: 760px)"
}

private fun assertAtlasEvidenceNoiseContract(
    script: String,
    styles: String,
) {
    script shouldContain "every distinct "
    script shouldContain "occurrence line in this file is lightly marked"
    script shouldContain "row.classList.add(\"is-relationship-line\")"
    script shouldContain "srcx-dashboard__architecture-source-record-count"
    styles shouldContain ".srcx-dashboard__architecture-source-evidence-status"
    styles shouldContain ".srcx-dashboard__architecture-finding-severity"
    styles shouldContain ".srcx-dashboard__architecture-finding-suggestion"
    styles shouldContain ".srcx-dashboard__architecture-finding-evidence"
    styles shouldContain ".srcx-dashboard__architecture-source-line.is-relationship-line"
    styles shouldContain ".srcx-dashboard__architecture-source-line.is-active-declaration"
    styles shouldContain ".srcx-dashboard__architecture-source-line.is-active-relationship"
    styles shouldContain "grid-template-columns: minmax(44px, auto) minmax(max-content, 1fr) max-content"
    styles shouldContain "grid-column: 3"
    styles shouldContain "grid-row: 1"
}

private fun assertAtlasRelationshipKindAndRoutingContract(
    script: String,
    styles: String,
) {
    assertAtlasRelationshipKindFilterContract(script)
    assertAtlasRelationshipRoutingContract(script, styles)
}

private fun assertAtlasRelationshipKindFilterContract(script: String) {
    script shouldContain "selectedRelationshipKind: \"all\""
    script shouldContain "var availableRelationshipCategories = relationshipCategories(data)"
    script shouldContain "function relationshipCategory(kindOrEdge)"
    script shouldContain "function relationshipCategories(data)"
    script shouldContain "function edgeMatchesRelationshipKind(edge, categoryId)"
    script shouldContain "function filterEdgeToRelationshipKind(edge, categoryId)"
    script shouldContain "function renderRelationshipKindFilter(focusKind)"
    script shouldContain "function applyRelationshipKindFilter(categoryId, moveFocus)"
    script shouldContain "{ id: \"all\", label: \"All relationships\" }"
    script shouldContain "inheritance: { id: \"inheritance\", label: \"Inheritance & implementation\" }"
    script shouldContain "calls: { id: \"calls\", label: \"Call / construct records\" }"
    script shouldContain "references: { id: \"references\", label: \"Type & member references\" }"
    script shouldContain "imports: { id: \"imports\", label: \"Imports\" }"
    script shouldContain "return ids.has(categoryId)"
    script shouldContain "relationshipKindFilter.hidden = availableRelationshipCategories.length === 0"
    script shouldContain "button.dataset.srcxRelationshipKind = category.id"
    script shouldContain "button.setAttribute(\"role\", \"radio\")"
    script shouldContain "button.setAttribute(\"aria-checked\", String(state.selectedRelationshipKind === category.id))"
    script shouldContain "button.tabIndex = state.selectedRelationshipKind === category.id ? 0 : -1"
    script shouldContain "moveRelationshipKindFocus(event, button)"
    script shouldContain "ArrowLeft"
    script shouldContain "ArrowRight"
    script shouldContain "Home"
    script shouldContain "End"
    script shouldContain "clearSelection(false)"
    script shouldContain "state.selectedRelationshipKind = categoryId"
    script shouldContain "filterEdgeToRelationshipKind(edge, relationshipKind)"
    script shouldContain "Show all bounded matches"
    script shouldContain
        "hidden by the current build, project, source-set, or relationship-kind filters."
    script shouldContain "state.selectedSourceSet = null"
    script shouldContain "state.selectedRelationshipKind = \"all\""
    script shouldContain "var occurrences = (edge.occurrences || []).filter"
    script shouldContain "recordCount: occurrences.length"
    script shouldContain "evidenceCounts: occurrenceEvidenceCounts(occurrences)"
    script shouldContain "function relationshipCategoryStats()"
    script shouldContain "var internalRecords = projection.availableInternalRecordCount"
    script shouldContain "relationship records available in this frame"
    script shouldContain "records available in this frame ("
    script shouldNotContain "displayed relationship records;"
    script shouldContain "same-file records without arrows"
    script shouldContain "function nodeHasInternalRelationshipKind(node, categoryId)"
    script shouldContain "participantIds.add(endpointId(edge.source))"
    script shouldContain "participantIds.add(endpointId(edge.target))"
    script shouldContain
        "return !node.isScopeContext && nodeHasInternalRelationshipKind(node, relationshipKind)"
    script shouldContain
        "return sum + scopedInternalRecordCount(node, state.selectedRelationshipKind)"
    script shouldContain "No \" + relationshipCategoryDefinition(state.selectedRelationshipKind).label.toLowerCase()"
}

private fun assertAtlasRelationshipRoutingContract(
    script: String,
    styles: String,
) {
    script shouldContain ".attr(\"class\", \"srcx-dashboard__architecture-svg-edge-count\")"
    script shouldContain ".attr(\"aria-label\", edgeRecordCountLabel)"
    script shouldContain "function edgeRecordCountText(edge)"
    script shouldContain "function edgeRecordCountLabel(edge)"
    script shouldContain "count + \" displayed relationship \" + (count === 1 ? \"record\" : \"records\")"
    script shouldContain "function nodeObstacleBounds(node, padding)"
    script shouldContain "var bounds = labelBounds(node)"
    script shouldContain "function outerNodeRadius(node)"
    script shouldContain "var gap = outerNodeRadius(node) + 6"
    script shouldContain "var radius = outerNodeRadius(node) + (padding || 0)"
    script shouldContain "pointFromCenter(edge.source, clipped[1], outerNodeRadius(edge.source) + 2)"
    script shouldContain "function routeEdgeAroundLabels(edge, nodes)"
    script shouldContain "function edgeRoute(edge, nodes)"
    script shouldContain "function curveDirectEdge(edge, route)"
    script shouldContain "edge.visualRoute = curveDirectEdge(edge, edge.route)"
    script shouldContain "function roundedEdgePath(route)"
    script shouldContain
        "return \"M\" + roundedPoint(route[0]) + \" Q\" + roundedPoint(route[1]) + \" \" + roundedPoint(route[2])"
    script shouldContain "commands.push(\"Q\" + roundedPoint(corner)"
    script shouldContain "renderEdgeSubset([])"
    script shouldContain "renderEdgeSubset(incident)"
    script shouldContain "renderEdgeSubset(navigationContainsEdge ? edgeNavigationLinks : selectedLinks)"
    script shouldContain "function positionRenderedEdges(groups, visibleLinks, visibleNodes)"
    script shouldContain "var ordered = edgeNavigationLinks.slice().sort"
    script shouldContain "function edgeLabelPoint(edge, nodes, occupied, badgeWidth, badgeHeight)"
    script shouldContain "var route = edge.visualRoute || curveDirectEdge"
    script shouldContain "var fractions = [0.5, 0.38, 0.62]"
    script shouldContain "var offsets = [12, -12, 24, -24, 0]"
    script shouldContain "function edgeRouteSample(route, fraction)"
    script shouldContain "function normalizedNormal(vector)"
    script shouldNotContain "function farthestRectangleDistance(point, rectangles)"
    script shouldContain "edge.countBadgeBounds"
    script shouldContain "graphBounds(nodes, data.builds, links)"

    val directRouter =
        script
            .substringAfter("function edgeRoute(edge, nodes)")
            .substringBefore("function selfEdgeRoute(edge)")
    directRouter shouldContain "return clipEdgeRoute(["
    directRouter shouldNotContain "routeAroundObstacles"

    styles shouldContain ".srcx-dashboard__architecture-kind-filter"
    styles shouldContain ".srcx-dashboard__architecture-kind-filter-rail"
    styles shouldContain ".srcx-dashboard__architecture-kind-filter-button"
    styles shouldContain ".srcx-dashboard__architecture-svg-edge-count"
    styles shouldContain
        "/* Relationship routes exist only for the active node or selected relationship neighborhood. */"
    styles shouldContain ".srcx-dashboard__architecture-svg-edge:not(.is-active):not(:focus-within)"
    styles shouldContain ".srcx-dashboard__architecture-svg-edge-count {\n    opacity: 0;"
    styles shouldContain ".srcx-dashboard__architecture-edge-guide"
    styles shouldContain ".srcx-dashboard__architecture-svg-edge.is-active.is-cycle-edge"
    styles shouldContain ".srcx-dashboard__architecture-build-region-body"
    styles shouldContain ".srcx-dashboard__architecture-project-region-body"
    styles shouldContain "pointer-events: none"
    styles shouldContain ".srcx-dashboard__architecture-build-drag-handle"
    styles shouldContain ".srcx-dashboard__architecture-project-drag-handle"
}

private fun assertBuildComparisonContract(
    script: String,
    styles: String,
) {
    script shouldNotContain "function wireBuildComparison()"
    script shouldNotContain "function openFindingsSection("
    script shouldNotContain "data-srcx-build-inspector"
    script shouldContain "function wireFindingFilters()"
    script shouldContain "var filters = [\"build\", \"project\", \"severity\"]"
    script shouldContain "var state = { build: \"all\", project: \"all\", severity: \"all\" }"
    script shouldContain "[data-srcx-finding-filter='\" + filter + \"']"
    script shouldContain "button.dataset.srcxFindingFilterValue"
    script shouldContain "function syncAvailableProjects()"
    script shouldContain "button.dataset.srcxFindingFilterBuild"
    script shouldContain "if (!selectedProject) state.project = \"all\""
    script shouldContain "button.setAttribute(\"aria-pressed\", String(button === target))"
    script shouldContain "reset.disabled = filters.every"
    script shouldContain "finding.dataset.srcxFindingSeverity === state.severity"
    script shouldContain "scope.hidden = scopeCount === 0"
    script shouldContain "visible === 1 ? \" finding shown\" : \" findings shown\""
    script shouldContain "function moveToolbarFocus(event, buttons, current)"
    script shouldContain "function updateToolbarTabStop(buttons, target)"
    script shouldContain
        "buttons.forEach(function (button) { button.tabIndex = button === target ? 0 : -1; })"
    styles shouldContain ".srcx-dashboard__build-comparison {"
    styles shouldContain ".srcx-dashboard__build-matrix {"
    styles shouldContain ".srcx-dashboard__build-matrix-wrap {\n    min-width: 0;\n    overflow-x: visible;"
    styles shouldContain ".srcx-dashboard__build-matrix {\n    min-width: 0;"
    styles shouldContain ".srcx-dashboard__build-metric[data-srcx-build-metric=\"projects\"]::before"
    styles shouldContain "grid-template-columns: repeat(2, minmax(0, 1fr))"
    styles shouldContain "@container (max-width: 680px)"
    styles shouldContain ".srcx-dashboard__build-matrix-row[data-srcx-build-kind=\"root\"]"
    styles shouldContain ".srcx-dashboard__build-metric-fill--stacked"
    styles shouldContain ".srcx-dashboard__finding-filter-group {"
    styles shouldContain ".srcx-dashboard__finding-filter-rail {"
    styles shouldContain ".srcx-dashboard__finding-filter-rail button[aria-pressed=\"true\"]"
    styles shouldContain ".srcx-dashboard__finding-filter-rail button[hidden]"
}

private fun assertAtlasEvidenceDeepLinkContract(script: String) {
    script shouldContain "wireArchitectureEvidenceActions();"
    script shouldContain "function wireArchitectureEvidenceActions()"
    script shouldContain "event.target.closest(\"[data-srcx-open-finding]\")"
    script shouldContain "var findingId = action.dataset.srcxFindingId"
    script shouldContain "atlas.srcxOpenArchitectureEvidence({ findingId: findingId }, action)"
    script shouldContain "atlas.scrollIntoView({ behavior: reducedMotion() ? \"auto\" : \"smooth\""
    script shouldContain "scrollAndFocus(atlas)"
    script shouldContain "function wireArchitectureEvidenceApi()"
    script shouldContain "dashboard.addEventListener(\"srcx:open-architecture-evidence\""
    script shouldContain "function openArchitectureEvidence(request, origin)"
    script shouldContain "var requestedFinding = request.findingId ? findingById.get(request.findingId) : null"
    script shouldContain "request.fileNodeId ? fileEntityById.get(request.fileNodeId) : null"
    script shouldContain "request.symbolNodeId ? symbolEntityById.get(request.symbolNodeId) : null"
    script shouldContain "request.findingId ? \"problems\" : symbolNode ? \"symbols\" : \"files\""
    script shouldContain "node.findingIds.includes(requestedFinding.id)"
    script shouldContain "fileNode = firstFileNodeByIds(requestedFinding.componentFileIds)"
    script shouldContain "fileNode = firstFileNodeByIds(requestedAnalysisCycle.memberFileIds)"
    script shouldContain "symbolNode = firstSymbolNodeByIds(requestedFinding.componentSymbolIds)"
    script shouldContain "symbolNode = firstSymbolNodeByIds(requestedAnalysisCycle.memberSymbolIds)"
    script shouldContain "Finding evidence has no linked file, declaration, or analyzer cycle in the "
    script shouldContain "typed Atlas catalog."
    script shouldContain "var requestedEdge = fileEdgeById.get(request.edgeId)"
    script shouldContain "findingId: typeof request.findingId === \"string\" ? request.findingId : null"
    script shouldContain "cycleId: typeof request.cycleId === \"string\" ? request.cycleId : null"
    script shouldContain "analysisCycleId: requestedAnalysisCycle ? requestedAnalysisCycle.id : null"
    script shouldContain "analysisParticipantIndex: requestedAnalysisParticipantIndex("
    script shouldContain "routeId: typeof request.routeId === \"string\" ? request.routeId : null"
    script shouldContain "edgeId: typeof request.edgeId === \"string\" ? request.edgeId : null"
    script shouldContain "var evidenceScope = selectedEntity || requestedAnalysisCycle || requestedFinding"
    script shouldContain "if (requestedView === \"symbols\" && selectedEntity === fileNode) requestedView = \"files\""
    script shouldContain "state.selectedBuild = evidenceScope.build"
    script shouldContain "state.selectedProject = evidenceScope.project"
    script shouldContain "var cycleEvidence = Boolean(requestedCycle || requestedAnalysisCycle)"
    script shouldContain
        "state.selectedSourceSet = selectedEntity && !cycleEvidence ? selectedEntity.sourceSet : null"
    script shouldContain "state.selectedRelationshipKind = \"all\""
    script shouldContain "selectNode(selectedNode, origin)"
    script shouldContain "function firstFileNodeByIds(ids)"
    script shouldContain "function firstSymbolNodeByIds(ids)"
    script shouldContain "function requestedAnalysisParticipantIndex(cycle, entity)"
    script shouldContain "dashboard.removeEventListener(\"srcx:open-architecture-evidence\""
    script shouldNotContain "find(function (node) { return node.name === request"
    script shouldNotContain "finding.message === request"
    script shouldNotContain "action.dataset.srcxFindingMessage"
    script shouldNotContain "action.dataset.srcxFindingFile"
}

private fun buildMatrixRow(
    buildsHtml: String,
    buildName: String,
): String =
    buildsHtml
        .substringAfter("data-srcx-build-row=\"$buildName\"")
        .substringBefore("data-srcx-build-row=")
        .substringBefore("</div></div></figure>")

private fun buildMetricCell(
    rowHtml: String,
    metric: String,
): String =
    rowHtml
        .substringAfter("data-srcx-build-metric=\"$metric\"")
        .substringBefore("</div>")

@Suppress("LongMethod")
private fun fullWorkspaceReport(): WorkspaceReport {
    val forbidden = Finding(FindingSeverity.FORBIDDEN, "Forbidden root package", "Rename the package")
    val warning =
        Finding(
            severity = FindingSeverity.WARNING,
            message = "Oversized runtime service",
            suggestion = "Split the service",
            filePath = "src/main/kotlin/com/example/domain/RuntimeService.kt",
            line = 21,
            componentIds = listOf("com.example.domain.RuntimeService"),
        )
    val runtimeCycle =
        ArchitectureComponentCycle(
            listOf(
                "com.example.app.ApplicationMain",
                "com.example.domain.RuntimeService",
                "com.example.app.ApplicationMain",
            ),
        )
    val note =
        Finding(
            severity = FindingSeverity.INFO,
            message = "Runtime has no contract",
            suggestion = "Consider an interface",
            componentIds = runtimeCycle.componentIds.dropLast(1),
            componentCycle = runtimeCycle,
        )
    val codecNote = Finding(FindingSeverity.INFO, "Codec ownership", "Review codec boundary")
    val hubs = fullHubs()
    val core = rendererSymbol("RuntimeCore", "com.example.core", "RuntimeCore.kt")
    val appProject = fullAppProject(listOf(note, forbidden, warning), hubs)
    val coreProject = rendererProject(":core", listOf(core), emptyList(), null)
    val codecProject = fullCodecProject(codecNote)
    val graph = fullWorkspaceGraph()
    return WorkspaceReport(
        name = "render-lab",
        rootProjects = listOf(coreProject, appProject),
        includedBuilds =
            listOf(
                IncludedBuildSummary("zeta-build", "../zeta", emptyList()),
                IncludedBuildSummary("library-build", "../library", listOf(codecProject)),
            ),
        buildEdges =
            listOf(
                BuildEdge("library-build", "zeta-build"),
                BuildEdge("render-lab", "library-build"),
            ),
        aggregateAnalysis =
            AnalysisSummary(
                findings = listOf(codecNote, warning, note, forbidden),
                hubs = hubs,
                cycles = emptyList(),
            ),
        entryPoints =
            listOf(
                EntryPointSummary("FakeClock", "com.example.test", EntryPointKind.MOCK),
                EntryPointSummary("ApplicationTest", "com.example.app", EntryPointKind.TEST),
                EntryPointSummary("ApplicationMain", "com.example.app", EntryPointKind.APP),
            ),
        interfaces =
            listOf(
                InterfaceSummary("RuntimeRepository", "com.example.runtime", 2, true, "main"),
                InterfaceSummary("Clock", "com.example.time", 1, false, "test"),
            ),
        workspaceIndex = graph.index,
        importantSymbols = graph.importantSymbols,
        sourceFiles = fullWorkspaceSourceFiles(),
    )
}

private fun fullWorkspaceSourceFiles(): List<WorkspaceSourceFile> =
    listOf(
        WorkspaceSourceFile(
            build = "library-build",
            project = ":codec",
            sourceSet = "main",
            projectRelativeFile = "src/main/kotlin/com/example/runtime/RuntimeRepository.kt",
            content =
                sourceFileContent(
                    lineCount = 12,
                    lines = mapOf(1 to "interface RuntimeRepository"),
                ),
        ),
        WorkspaceSourceFile(
            build = "render-lab",
            project = ":app",
            sourceSet = "main",
            projectRelativeFile = "src/main/kotlin/com/example/app/ApplicationMain.kt",
            content =
                sourceFileContent(
                    lineCount = 28,
                    lines =
                        mapOf(
                            1 to "class ApplicationMain",
                            18 to "    service.run()",
                            24 to "    RuntimeRepository",
                        ),
                ),
        ),
        WorkspaceSourceFile(
            build = "render-lab",
            project = ":app",
            sourceSet = "main",
            projectRelativeFile = "src/main/kotlin/com/example/domain/RuntimeService.kt",
            content =
                sourceFileContent(
                    lineCount = 30,
                    lines =
                        mapOf(
                            1 to "class RuntimeService",
                            21 to "    private val repository: RuntimeRepository",
                            27 to "    fun save(repository: RuntimeRepository)",
                        ),
                ),
        ),
    )

private fun sourceFileContent(
    lineCount: Int,
    lines: Map<Int, String>,
): String =
    (1..lineCount)
        .joinToString(separator = "\n") { line -> lines[line].orEmpty() }

@Suppress("LongMethod")
private fun fullWorkspaceGraph(): RendererWorkspaceGraph {
    val application =
        rendererWorkspaceSymbol(
            build = "render-lab",
            project = ":app",
            name = "ApplicationMain",
            qualifiedName = "com.example.app.ApplicationMain",
            kind = SymbolDetailKind.CLASS,
            file = "src/main/kotlin/com/example/app/ApplicationMain.kt",
        )
    val service =
        rendererWorkspaceSymbol(
            build = "render-lab",
            project = ":app",
            name = "RuntimeService",
            qualifiedName = "com.example.domain.RuntimeService",
            kind = SymbolDetailKind.CLASS,
            file = "src/main/kotlin/com/example/domain/RuntimeService.kt",
        )
    val repository =
        rendererWorkspaceSymbol(
            build = "library-build",
            project = ":codec",
            name = "RuntimeRepository",
            qualifiedName = "com.example.runtime.RuntimeRepository",
            kind = SymbolDetailKind.INTERFACE,
            file = "src/main/kotlin/com/example/runtime/RuntimeRepository.kt",
        )
    val relationships =
        listOf(
            rendererRelationship(
                application,
                service,
                WorkspaceRelationshipKind.CALL,
                ReferenceKind.CALL,
                ReferenceEvidence.DIRECT,
                18,
                "service.run()",
            ),
            rendererRelationship(
                service,
                repository,
                WorkspaceRelationshipKind.TYPE_REFERENCE,
                ReferenceKind.TYPE_REF,
                ReferenceEvidence.DERIVED,
                21,
                "private val repository: RuntimeRepository",
            ),
            rendererRelationship(
                service,
                repository,
                WorkspaceRelationshipKind.TYPE_REFERENCE,
                ReferenceKind.TYPE_REF,
                ReferenceEvidence.DERIVED,
                27,
                "fun save(repository: RuntimeRepository)",
            ),
            rendererRelationship(
                application,
                repository,
                WorkspaceRelationshipKind.NAME_REFERENCE,
                ReferenceKind.NAME_REF,
                ReferenceEvidence.HEURISTIC,
                24,
                "RuntimeRepository",
            ),
            rendererRelationship(
                application,
                repository,
                WorkspaceRelationshipKind.IMPORT,
                ReferenceKind.IMPORT,
                ReferenceEvidence.DIRECT,
                3,
                "import com.example.runtime.RuntimeRepository",
            ),
        )
    val symbols = listOf(repository, service, application)
    val importantSymbols =
        listOf(
            rendererImportantSymbol(
                application,
                listOf(ImportantSymbolReason.ENTRY_POINT, ImportantSymbolReason.HIGH_WORKSPACE_OUTBOUND),
                relationships,
            ),
            rendererImportantSymbol(
                repository,
                listOf(
                    ImportantSymbolReason.CROSS_BUILD_INBOUND,
                    ImportantSymbolReason.MULTIPLE_IMPLEMENTATIONS,
                ),
                relationships,
            ),
        )
    return RendererWorkspaceGraph(
        index =
            WorkspaceIndex(
                symbols = symbols,
                references = relationships.map { it.sourceEvidence },
                relationships = relationships,
                usages = symbols.map { symbol -> rendererUsage(symbol, relationships) },
            ),
        importantSymbols = importantSymbols,
    )
}

private fun fullHubs(): List<HubClass> {
    val runtimeHub =
        HubClass(
            name = "RuntimeHub",
            dependentCount = 5,
            role = "service",
            filePath = "src/main/RuntimeHub.kt",
            line = 12,
            dependents = listOf(HubDependentRef("RuntimeConsumer", "src/main/RuntimeConsumer.kt", 8)),
        )
    val noDetailHub = HubClass("NoDetailHub", 1, "", "src/main/NoDetailHub.kt", 4)
    val testHub = HubClass("RuntimeHubTest", 10, "test", "src/test/RuntimeHubTest.kt", 3, isTest = true)
    return listOf(testHub, noDetailHub, runtimeHub)
}

private fun fullAppProject(
    findings: List<Finding>,
    hubs: List<HubClass>,
): ProjectSummary {
    val app = rendererSymbol("ApplicationMain", "com.example.app", "ApplicationMain.kt")
    val appTest = rendererSymbol("ApplicationTest", "com.example.app", "ApplicationTest.kt")
    return rendererProject(
        path = ":app",
        symbols = listOf(app, appTest),
        sourceSets =
            listOf(
                SourceSetSummary(SourceSetName("test"), listOf(appTest), listOf("src/test/kotlin")),
                SourceSetSummary(SourceSetName("main"), listOf(app), listOf("src/main/kotlin")),
            ),
        analysis =
            AnalysisSummary(
                findings,
                hubs,
                listOf(listOf("RuntimeService", "RuntimeRepository", "RuntimeService")),
                fullAppArchitecture(),
            ),
        dependencyCount = 1,
    )
}

private fun fullCodecProject(finding: Finding): ProjectSummary {
    val codec = rendererSymbol("Codec", "com.example.codec", "Codec.kt")
    return rendererProject(
        ":codec",
        listOf(codec),
        listOf(SourceSetSummary(SourceSetName("main"), listOf(codec), listOf("src/main/kotlin"))),
        AnalysisSummary(listOf(finding), emptyList(), emptyList()),
        dependencyCount = 1,
    )
}

private fun fullAppArchitecture(): ArchitectureSummary {
    val application = architectureComponent("ApplicationMain", "com.example.app", "app", ArchitectureLayer.PRESENTATION)
    val service = architectureComponent("RuntimeService", "com.example.domain", "domain", ArchitectureLayer.DOMAIN)
    val repository = architectureComponent("RuntimeRepository", "com.example.data", "data", ArchitectureLayer.DATA)
    val test =
        architectureComponent(
            "ApplicationTest",
            "com.example.app",
            "app",
            ArchitectureLayer.TEST,
            isTest = true,
        )
    return ArchitectureSummary(
        components = listOf(application, service, repository, test),
        dependencies =
            listOf(
                ArchitectureDependency(application.id, service.id),
                ArchitectureDependency(service.id, application.id),
                ArchitectureDependency(service.id, repository.id),
                ArchitectureDependency(test.id, service.id),
            ),
        entryPoints =
            listOf(
                ArchitectureEntryPoint(
                    application.id,
                    "Declares main()",
                    ArchitectureEntryPointKind.EXPLICIT,
                ),
            ),
        cycles =
            listOf(
                ArchitectureComponentCycle(listOf(application.id, service.id, application.id)),
            ),
    )
}

private fun architectureComponent(
    name: String,
    packageName: String,
    group: String,
    layer: ArchitectureLayer,
    isTest: Boolean = false,
): ArchitectureComponent =
    ArchitectureComponent(
        id = "$packageName.$name",
        name = name,
        packageName = packageName,
        packageGroup = group,
        role = layer.label,
        layer = layer,
        filePath = "${packageName.replace('.', '/')}/$name.kt",
        line = 1,
        isTest = isTest,
    )

private fun emptyWorkspaceReport(): WorkspaceReport =
    WorkspaceReport("empty-workspace", emptyList(), emptyList(), emptyList(), null, emptyList(), emptyList())

@Suppress("LongMethod")
private fun escapingWorkspaceReport(payload: String): WorkspaceReport {
    val finding = Finding(FindingSeverity.WARNING, payload, payload)
    val hub =
        HubClass(
            payload,
            1,
            payload,
            payload,
            1,
            listOf(HubDependentRef(payload, payload, 1)),
        )
    val symbol = rendererSymbol("Symbol", "com.example", "Symbol.kt")
    val workspaceSymbol =
        rendererWorkspaceSymbol(
            build = payload,
            project = ":$payload",
            name = payload,
            qualifiedName = "com.example.$payload",
            kind = SymbolDetailKind.CLASS,
            file = payload,
        )
    val relationship =
        rendererRelationship(
            workspaceSymbol,
            workspaceSymbol,
            WorkspaceRelationshipKind.NAME_REFERENCE,
            ReferenceKind.NAME_REF,
            ReferenceEvidence.HEURISTIC,
            1,
            payload,
        )
    val usage = rendererUsage(workspaceSymbol, listOf(relationship))
    val project =
        rendererProject(
            path = ":$payload",
            symbols = listOf(symbol),
            sourceSets = listOf(SourceSetSummary(SourceSetName(payload), listOf(symbol), emptyList())),
            analysis =
                AnalysisSummary(
                    listOf(finding),
                    listOf(hub),
                    emptyList(),
                    ArchitectureSummary(
                        components =
                            listOf(
                                ArchitectureComponent(
                                    id = payload,
                                    name = payload,
                                    packageName = payload,
                                    packageGroup = payload,
                                    role = payload,
                                    layer = ArchitectureLayer.OTHER,
                                    filePath = payload,
                                    line = 1,
                                    isTest = false,
                                ),
                            ),
                        entryPoints =
                            listOf(
                                ArchitectureEntryPoint(payload, payload, ArchitectureEntryPointKind.EXPLICIT),
                            ),
                    ),
                ),
        )
    return WorkspaceReport(
        payload,
        listOf(project),
        listOf(IncludedBuildSummary(payload, payload, emptyList())),
        listOf(BuildEdge(payload, payload)),
        AnalysisSummary(listOf(finding), listOf(hub), emptyList()),
        listOf(EntryPointSummary(payload, payload, EntryPointKind.APP)),
        listOf(InterfaceSummary(payload, payload, 1, true, payload)),
        WorkspaceIndex(
            symbols = listOf(workspaceSymbol),
            references = listOf(relationship.sourceEvidence),
            relationships = listOf(relationship),
            usages = listOf(usage),
        ),
        listOf(
            ImportantSymbol(
                workspaceSymbol,
                listOf(ImportantSymbolReason.ENTRY_POINT),
                ImportantSymbolReason.ENTRY_POINT.score,
                usage,
            ),
        ),
        sourceFiles =
            listOf(
                WorkspaceSourceFile(
                    build = payload,
                    project = ":$payload",
                    sourceSet = "main",
                    projectRelativeFile = payload,
                    content = "$payload\n\u2028\u2029",
                ),
            ),
    )
}

private fun reorderWorkspaceReport(report: WorkspaceReport): WorkspaceReport =
    report.copy(
        rootProjects = report.rootProjects.reversed().map(::reverseProjectAnalysis),
        includedBuilds =
            report.includedBuilds
                .reversed()
                .map { build -> build.copy(projects = build.projects.reversed().map(::reverseProjectAnalysis)) },
        buildEdges = report.buildEdges.reversed(),
        aggregateAnalysis = report.aggregateAnalysis?.reverseAnalysis(),
        entryPoints = report.entryPoints.reversed(),
        interfaces = report.interfaces.reversed(),
        workspaceIndex =
            report.workspaceIndex.copy(
                symbols = report.workspaceIndex.symbols.reversed(),
                references = report.workspaceIndex.references.reversed(),
                relationships = report.workspaceIndex.relationships.reversed(),
                usages =
                    report.workspaceIndex.usages
                        .reversed()
                        .map { usage ->
                            usage.copy(incoming = usage.incoming.reversed(), outgoing = usage.outgoing.reversed())
                        },
            ),
        importantSymbols =
            report.importantSymbols
                .reversed()
                .map { important ->
                    important.copy(
                        reasons = important.reasons.reversed(),
                        usage =
                            important.usage.copy(
                                incoming = important.usage.incoming.reversed(),
                                outgoing = important.usage.outgoing.reversed(),
                            ),
                    )
                },
    )

private fun reverseProjectAnalysis(project: ProjectSummary): ProjectSummary =
    project.copy(
        symbols = project.symbols.reversed(),
        sourceSets = project.sourceSets.reversed(),
        analysis = project.analysis?.reverseAnalysis(),
    )

private fun AnalysisSummary.reverseAnalysis(): AnalysisSummary =
    copy(
        findings = findings.reversed(),
        hubs = hubs.reversed(),
        cycles = cycles.reversed(),
        architecture =
            architecture.copy(
                components = architecture.components.reversed(),
                dependencies = architecture.dependencies.reversed(),
                entryPoints = architecture.entryPoints.reversed(),
            ),
    )

private fun rendererProject(
    path: String,
    symbols: List<SymbolEntry>,
    sourceSets: List<SourceSetSummary>,
    analysis: AnalysisSummary?,
    dependencyCount: Int = 0,
): ProjectSummary =
    ProjectSummary(
        projectPath = ProjectPath(path),
        symbols = symbols,
        dependencies =
            (1..dependencyCount).map { index ->
                DependencyEntry(
                    ArtifactGroup("com.example"),
                    ArtifactName("dependency-$index"),
                    ArtifactVersion("1.0"),
                    "implementation",
                )
            },
        buildFile = "build.gradle.kts",
        sourceDirs = emptyList(),
        subprojects = emptyList(),
        sourceSets = sourceSets,
        analysis = analysis,
    )

private fun rendererSymbol(
    name: String,
    packageName: String,
    filePath: String,
): SymbolEntry =
    SymbolEntry(SymbolName(name), SymbolKind.CLASS, PackageName(packageName), FilePath(filePath), 1)

@Suppress("LongParameterList")
private fun rendererWorkspaceSymbol(
    build: String,
    project: String,
    name: String,
    qualifiedName: String,
    kind: SymbolDetailKind,
    file: String,
): WorkspaceSymbol =
    WorkspaceSymbol(
        build = build,
        project = project,
        sourceSet = "main",
        name = name,
        qualifiedName = qualifiedName,
        kind = kind,
        projectRelativeFile = file,
        declarationLine = 1,
    )

@Suppress("LongParameterList")
private fun rendererRelationship(
    source: WorkspaceSymbol,
    target: WorkspaceSymbol,
    kind: WorkspaceRelationshipKind,
    referenceKind: ReferenceKind,
    evidence: ReferenceEvidence,
    line: Int,
    context: String,
): WorkspaceRelationship =
    WorkspaceRelationship(
        source = source,
        target = target,
        kind = kind,
        sourceEvidence =
            WorkspaceReference(
                build = source.build,
                project = source.project,
                sourceSet = source.sourceSet,
                sourceSymbol = source,
                targetName = target.name,
                targetQualifiedName = target.qualifiedName,
                kind = referenceKind,
                projectRelativeFile = source.projectRelativeFile,
                line = line,
                context = context,
                evidence = evidence,
            ),
        evidence = evidence,
    )

private fun rendererImportantSymbol(
    symbol: WorkspaceSymbol,
    reasons: List<ImportantSymbolReason>,
    relationships: List<WorkspaceRelationship>,
): ImportantSymbol = ImportantSymbol(symbol, reasons, reasons.sumOf { it.score }, rendererUsage(symbol, relationships))

private fun rendererUsage(
    symbol: WorkspaceSymbol,
    relationships: List<WorkspaceRelationship>,
): WorkspaceSymbolUsage =
    WorkspaceSymbolUsage(
        symbol,
        relationships.filter { relationship -> relationship.targetIdentity == symbol.identity },
        relationships.filter { relationship -> relationship.sourceIdentity == symbol.identity },
    )

private data class RendererWorkspaceGraph(
    val index: WorkspaceIndex,
    val importantSymbols: List<ImportantSymbol>,
)
