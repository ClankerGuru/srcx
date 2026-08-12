package zone.clanker.gradle.srcx.report

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import zone.clanker.gradle.srcx.model.AnalysisSummary
import zone.clanker.gradle.srcx.model.ArchitectureComponent
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
import zone.clanker.gradle.srcx.model.WorkspaceSymbol
import zone.clanker.gradle.srcx.model.WorkspaceSymbolUsage

class WorkspaceHtmlRendererTest :
    BehaviorSpec({
        given("a full typed workspace report") {
            `when`("it is rendered") {
                val rendered = WorkspaceHtmlRenderer().render(fullWorkspaceReport())
                val article =
                    rendered.fragment
                        .substringAfter("</style>")
                        .substringBefore("<script data-srcx-vendor")

                then("the notebook fragment contains scoped inline Gort styles") {
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

                then("workspace and build metrics are rendered from typed projects") {
                    article shouldContain "render-lab"
                    article shouldContain "<strong>4</strong>"
                    article shouldContain "<span>Symbols</span>"
                    article shouldContain "<span>Projects</span>"
                    article shouldContain "library-build"
                    article shouldContain "../library"
                    article shouldContain "zeta-build"
                    article shouldContain "Build comparison"
                    article shouldContain "render-lab &rarr; library-build"
                    article shouldContain "library-build &rarr; zeta-build"
                    article shouldNotContain "srcx-repo-card"
                    article shouldNotContain "id=\"topology\""
                }

                then("ownership, source coverage, and production hubs are direct model views") {
                    article shouldContain "Symbol ownership"
                    article shouldContain "<b>render-lab</b><small>3 symbols / 75.0%</small>"
                    article shouldContain "<b>library-build</b><small>1 symbol / 25.0%</small>"
                    article shouldContain
                        "style=\"--symbol-count: 3\" aria-label=\"render-lab: 3 symbols, 75.0 percent\"></span>"
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
                    article shouldContain "<details class=\"srcx-disclosure srcx-dashboard__finding-scope\">"
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

                then("build edges and the cumulative workspace graph are rendered") {
                    article shouldContain "Explore files first."
                    article shouldContain "Switch lenses for symbols, exact file findings, or resolved cycles."
                    article shouldContain "data-srcx-graph-view=\"files\" aria-pressed=\"true\""
                    article shouldContain "data-srcx-graph-view=\"symbols\""
                    article shouldContain "data-srcx-graph-view=\"problems\""
                    article shouldContain "data-srcx-graph-view=\"cycles\""
                    article shouldContain "data-srcx-graph-search"
                    article shouldContain "data-srcx-graph-action=\"zoom-in\""
                    article shouldContain "data-srcx-graph-action=\"zoom-out\""
                    article shouldContain "data-srcx-graph-action=\"fit\""
                    article shouldContain "data-srcx-graph-action=\"reset\""
                    article shouldContain "data-srcx-detail hidden aria-live=\"polite\""
                    article shouldContain "data-srcx-detail-close"
                    article shouldContain "Select a node or arrow for source evidence"
                    article shouldContain "data-srcx-architecture-graph"
                    article shouldContain "Map limits and evidence / 4 resolved records / expand"
                    article shouldContain "ApplicationMain"
                    article shouldContain "RuntimeService"
                    article shouldContain "RuntimeRepository"
                    article shouldContain "ApplicationMain.kt"
                    article shouldContain "RuntimeService.kt"
                    article shouldContain "RuntimeRepository.kt"
                    article shouldContain "render-lab:::app::main"
                    article shouldContain "library-build:::codec::main"
                    article shouldContain "\"label\":\"calls\", \"count\":1"
                    article shouldContain "\"label\":\"uses type\", \"count\":2"
                    article shouldContain "\"evidence\":\"HEURISTIC\""
                    article shouldContain "\"crossBuild\":true"
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

                then("section four collapses typed model coverage and provenance") {
                    article shouldContain "Model provenance"
                    article shouldContain "<details class=\"srcx-disclosure srcx-dashboard__model-disclosure\">"
                    article shouldNotContain "srcx-dashboard__model-disclosure\" open"
                    article shouldContain "WorkspaceReport field coverage"
                    article shouldContain "aggregateAnalysis"
                    article shouldContain "workspaceIndex"
                    article shouldContain "importantSymbols"
                    article shouldContain "Direct model provenance"
                    article shouldContain "Generated directly from the SRCX WorkspaceReport"
                }

                then("the compact sections follow the dashboard sequence and findings finish the content") {
                    val architecture = article.indexOf("id=\"architecture\"")
                    val builds = article.indexOf("id=\"builds\"")
                    val health = article.indexOf("id=\"health\"")
                    val model = article.indexOf("id=\"model\"")
                    val findings = article.indexOf("id=\"findings\"")
                    val footer = article.indexOf("<footer class=\"srcx-dashboard__footer\">")

                    (architecture in 0 until builds) shouldBe true
                    (builds in 0 until health) shouldBe true
                    (health in 0 until model) shouldBe true
                    (model in 0 until findings) shouldBe true
                    (findings in 0 until footer) shouldBe true
                }

                then("dashboard finding totals come from project analyses") {
                    val report =
                        fullWorkspaceReport().copy(
                            aggregateAnalysis = AnalysisSummary(emptyList(), emptyList(), emptyList()),
                        )
                    val scopedArticle = WorkspaceHtmlRenderer().render(report).fragment.substringAfter("</style>")

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
            }
        }

        given("an empty typed workspace report") {
            `when`("it is rendered") {
                val rendered = WorkspaceHtmlRenderer().render(emptyWorkspaceReport())

                then("the renderer returns complete empty-state documentation") {
                    rendered.fragment shouldContain "empty-workspace"
                    rendered.fragment shouldContain "No symbols"
                    rendered.fragment shouldContain "No production hubs"
                    rendered.fragment shouldContain "No scoped findings"
                    rendered.fragment shouldContain "No cumulative relationship map"
                    rendered.fragment shouldContain
                        "No resolved non-import relationship connects indexed workspace files."
                    rendered.fragment shouldContain "Map limits and evidence / 0 resolved records / expand"
                    rendered.fragment shouldNotContain "Raw edge evidence"
                    rendered.fragment shouldContain "WorkspaceReport field coverage"
                }

                then("zero-valued metrics and a root build remain visible") {
                    rendered.fragment shouldContain "<strong>0</strong>"
                    rendered.fragment shouldContain "<span>Symbols</span>"
                    rendered.fragment shouldContain "Root build"
                    rendered.fragment shouldContain "<span>Projects</span>"
                }

                then("no template slot leaks into either output") {
                    rendered.fragment shouldNotContain "{{"
                    rendered.document shouldNotContain "{{"
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
                    rendered.fragment shouldContain "No cumulative relationship map"
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
                    rendered.fragment shouldContain "\\u003cscript\\u003ealert"
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

            then("the owned explorer script uses the file contract without dynamic HTML injection") {
                val script =
                    WorkspaceHtmlResourceRenderer.readClasspathResource(
                        WorkspaceHtmlResourceRenderer.ARCHITECTURE_GRAPH_SCRIPT,
                    )

                script shouldContain "data.fileNodes.length === 0"
                script shouldContain "event.ctrlKey || event.metaKey"
                script shouldContain "No exact file cycles"
                script shouldNotContain "innerHTML"
                script shouldNotContain "insertAdjacentHTML"
            }
        }
    })

private fun fullWorkspaceReport(): WorkspaceReport {
    val forbidden = Finding(FindingSeverity.FORBIDDEN, "Forbidden root package", "Rename the package")
    val warning = Finding(FindingSeverity.WARNING, "Oversized runtime service", "Split the service")
    val note = Finding(FindingSeverity.INFO, "Runtime has no contract", "Consider an interface")
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
    )
}

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
                12,
                relationships,
            ),
            rendererImportantSymbol(
                repository,
                listOf(
                    ImportantSymbolReason.CROSS_BUILD_INBOUND,
                    ImportantSymbolReason.MULTIPLE_IMPLEMENTATIONS,
                ),
                10,
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
                1,
                usage,
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
    score: Int,
    relationships: List<WorkspaceRelationship>,
): ImportantSymbol = ImportantSymbol(symbol, reasons, score, rendererUsage(symbol, relationships))

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
