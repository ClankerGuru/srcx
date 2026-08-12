package zone.clanker.gradle.srcx.report

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import zone.clanker.gradle.srcx.model.AnalysisSummary
import zone.clanker.gradle.srcx.model.Finding
import zone.clanker.gradle.srcx.model.FindingSeverity
import zone.clanker.gradle.srcx.model.ImportantSymbol
import zone.clanker.gradle.srcx.model.ImportantSymbolReason
import zone.clanker.gradle.srcx.model.ProjectPath
import zone.clanker.gradle.srcx.model.ProjectSummary
import zone.clanker.gradle.srcx.model.ReferenceEvidence
import zone.clanker.gradle.srcx.model.ReferenceKind
import zone.clanker.gradle.srcx.model.SymbolDetailKind
import zone.clanker.gradle.srcx.model.WorkspaceIndex
import zone.clanker.gradle.srcx.model.WorkspaceReference
import zone.clanker.gradle.srcx.model.WorkspaceRelationship
import zone.clanker.gradle.srcx.model.WorkspaceRelationshipKind
import zone.clanker.gradle.srcx.model.WorkspaceReport
import zone.clanker.gradle.srcx.model.WorkspaceSymbol
import zone.clanker.gradle.srcx.model.WorkspaceSymbolUsage

class WorkspaceArchitectureGraphTest :
    BehaviorSpec({
        given("cumulative relationships around an important symbol") {
            val source = graphSymbol("root", ":app", "Source", SymbolDetailKind.CLASS)
            val target = graphSymbol("library", ":api", "Contract", SymbolDetailKind.INTERFACE)
            val relationships =
                listOf(
                    graphRelationship(source, target, ReferenceEvidence.DIRECT, 14, "target.call()"),
                    graphRelationship(source, target, ReferenceEvidence.DIRECT, 9, "target.callAgain()"),
                    graphRelationship(source, target, ReferenceEvidence.HEURISTIC, 22, "</script>{{slot}}"),
                    graphRelationship(
                        source,
                        target,
                        ReferenceEvidence.DIRECT,
                        2,
                        "import example.Contract",
                        WorkspaceRelationshipKind.IMPORT,
                        ReferenceKind.IMPORT,
                    ),
                    unresolvedGraphRelationship(target, "Contract"),
                )
            val report =
                graphReport(
                    listOf(source, target),
                    relationships,
                    listOf(graphImportant(source, relationships)),
                )

            `when`("the bounded graph is built") {
                val graph = buildWorkspaceArchitectureGraph(report)

                then("it retains scoped nodes, usage, clusters, and exact aggregated evidence") {
                    graph.nodes shouldHaveSize 2
                    graph.clusters.map { it.label } shouldBe listOf("library / :api", "root / :app")
                    graph.edges shouldHaveSize 2
                    val direct = graph.edges.first { it.evidence == ReferenceEvidence.DIRECT.name }
                    direct.count shouldBe 2
                    direct.label shouldBe "calls x2"
                    direct.crossBuild shouldBe true
                    direct.occurrences.map { it.line } shouldBe listOf(9, 14)
                    val heuristic = graph.edges.first { it.evidence == ReferenceEvidence.HEURISTIC.name }
                    heuristic.count shouldBe 1
                    val targetNode = graph.nodes.first { it.id == target.identity.value }
                    targetNode.localInbound shouldBe 0
                    targetNode.workspaceInbound shouldBe 4
                    targetNode.crossBuildInbound shouldBe 4
                    targetNode.outgoingCount shouldBe 0
                    targetNode.importanceScore shouldBe null
                    graph.nodes.first { it.id == source.identity.value }.importanceReasons shouldBe
                        listOf(ImportantSymbolReason.ENTRY_POINT.label)

                    graph.fileNodes shouldHaveSize 2
                    val sourceFile = graph.fileNodes.first { it.path == source.projectRelativeFile }
                    sourceFile.id shouldBe
                        "file::root:::app::main::src/main/kotlin/example/Source.kt"
                    sourceFile.name shouldBe "Source.kt"
                    sourceFile.scope shouldBe "root:::app::main"
                    sourceFile.selectedSymbolIds shouldContainExactly listOf(source.identity.value)
                    sourceFile.importance shouldBe 10
                    val fileEdge = graph.fileEdges.single()
                    fileEdge.source shouldBe sourceFile.id
                    fileEdge.target shouldBe graph.fileNodes.first { it.path == target.projectRelativeFile }.id
                    fileEdge.count shouldBe 3
                    fileEdge.kindCounts.map { it.kind to it.count } shouldContainExactly
                        listOf(WorkspaceRelationshipKind.CALL.name to 3)
                    fileEdge.evidenceCounts.map { it.evidence to it.count } shouldContainExactly
                        listOf(ReferenceEvidence.DIRECT.name to 2, ReferenceEvidence.HEURISTIC.name to 1)
                    fileEdge.occurrences.map { it.line } shouldContainExactly listOf(9, 14, 22)
                }

                then("JSON transport cannot terminate its script element or expose template slots") {
                    val json = graph.toJson()
                    json shouldContain "\"defaultView\":\"files\""
                    json shouldContain "\"fileNodes\""
                    json shouldContain "\"fileEdges\""
                    json shouldContain "\"cycles\""
                    json shouldContain "\\u003c/script\\u003e"
                    json shouldContain "\\u007b\\u007bslot\\u007d\\u007d"
                    json shouldNotContain "</script"
                    json shouldNotContain "{{slot}}"
                }

                then("reversing every cumulative input does not change the graph") {
                    val reversed =
                        report.copy(
                            workspaceIndex =
                                report.workspaceIndex.copy(
                                    symbols = report.workspaceIndex.symbols.reversed(),
                                    references = report.workspaceIndex.references.reversed(),
                                    relationships = report.workspaceIndex.relationships.reversed(),
                                ),
                            importantSymbols = report.importantSymbols.reversed(),
                        )
                    val reversedGraph = buildWorkspaceArchitectureGraph(reversed)
                    reversedGraph shouldBe graph
                    reversedGraph.toJson() shouldBe graph.toJson()
                }
            }
        }

        given("multiple selected symbols and relationships in one file") {
            val sharedPath = "src/main/kotlin/example/Shared.kt"
            val owner = graphSymbol("root", ":app", "Owner", SymbolDetailKind.CLASS, sharedPath, 1)
            val helper = graphSymbol("root", ":app", "Helper", SymbolDetailKind.FUNCTION, sharedPath, 7)
            val target = graphSymbol("root", ":app", "Target", SymbolDetailKind.CLASS)
            val relationships =
                listOf(
                    graphRelationship(owner, helper, ReferenceEvidence.DIRECT, 2, "helper()"),
                    graphRelationship(
                        helper,
                        owner,
                        ReferenceEvidence.DERIVED,
                        8,
                        "Owner",
                        WorkspaceRelationshipKind.TYPE_REFERENCE,
                        ReferenceKind.TYPE_REF,
                    ),
                    graphRelationship(owner, target, ReferenceEvidence.DIRECT, 3, "Target()"),
                )
            val findings =
                listOf(
                    Finding(FindingSeverity.WARNING, "typed file finding", "fix it", sharedPath),
                    Finding(FindingSeverity.INFO, "Shared appears only in prose", "review it"),
                    Finding(FindingSeverity.INFO, "other file", "review it", "src/main/kotlin/example/Other.kt"),
                )
            val report =
                graphReport(
                    listOf(owner, helper, target),
                    relationships,
                    listOf(graphImportant(owner, relationships)),
                ).copy(rootProjects = listOf(graphProject(":app", findings)))

            `when`("the relationships are projected onto file scopes") {
                val graph = buildWorkspaceArchitectureGraph(report)
                val shared = graph.fileNodes.first { it.path == sharedPath }

                then("same-file relationships stay off the canvas but remain counted on the file") {
                    shared.selectedSymbolIds shouldContainExactly
                        listOf(helper.identity.value, owner.identity.value).sorted()
                    shared.symbols.map { it.name } shouldContainExactly listOf("Helper", "Owner")
                    shared.internalCount shouldBe 2
                    shared.outgoingCount shouldBe 1
                    shared.incomingCount shouldBe 0
                    graph.fileEdges shouldHaveSize 1
                    graph.fileEdges.none { it.source == it.target } shouldBe true
                    val contexts =
                        graph.fileEdges
                            .single()
                            .occurrences
                            .map { it.context }
                    contexts shouldContainExactly listOf("Target()")
                }

                then("typed file paths provide exact file and project finding counts") {
                    shared.fileFindingCount shouldBe 1
                    shared.projectFindingCount shouldBe 3
                    graph.fileNodes.first { it.path == target.projectRelativeFile }.fileFindingCount shouldBe 0
                }
            }
        }

        given("more first-hop neighbors than the graph cap") {
            val important = graphSymbol("root", ":app", "Anchor", SymbolDetailKind.CLASS)
            val preferredInterface = graphSymbol("root", ":app", "PreferredInterface", SymbolDetailKind.INTERFACE)
            val crossBuildProperty = graphSymbol("included", ":lib", "CrossBuildProperty", SymbolDetailKind.PROPERTY)
            val functions =
                (0 until ARCHITECTURE_GRAPH_NODE_LIMIT).map { index ->
                    graphSymbol(
                        "root",
                        ":app",
                        "Function${index.toString().padStart(2, '0')}",
                        SymbolDetailKind.FUNCTION,
                    )
                }
            val neighbors = listOf(preferredInterface, crossBuildProperty) + functions
            val relationships =
                neighbors.mapIndexed { index, neighbor ->
                    graphRelationship(important, neighbor, ReferenceEvidence.DIRECT, index + 1, neighbor.name)
                }
            val report =
                graphReport(
                    listOf(important) + neighbors,
                    relationships,
                    listOf(graphImportant(important, relationships)),
                )

            `when`("neighbors are selected deterministically") {
                val graph = buildWorkspaceArchitectureGraph(report)
                val selected = graph.nodes.map { it.id }

                then("the named cap is enforced while cross-build and type declarations win ties") {
                    graph.nodes shouldHaveSize ARCHITECTURE_GRAPH_NODE_LIMIT
                    graph.omittedNodeCount shouldBe 3
                    selected shouldContain important.identity.value
                    selected shouldContain preferredInterface.identity.value
                    selected shouldContain crossBuildProperty.identity.value
                    selected shouldNotContain functions.last().identity.value
                }
            }
        }

        given("more important symbols and adjacent symbols than the sparse cap") {
            val importantSymbols =
                (0 until 22).map { index ->
                    graphSymbol(
                        "root",
                        ":app",
                        "Important${index.toString().padStart(2, '0')}",
                        SymbolDetailKind.CLASS,
                    )
                }
            val neighbors =
                importantSymbols.mapIndexed { index, _ ->
                    graphSymbol(
                        "root",
                        ":app",
                        "Neighbor${index.toString().padStart(2, '0')}",
                        SymbolDetailKind.FUNCTION,
                    )
                }
            val relationships =
                importantSymbols.zip(neighbors).mapIndexed { index, (important, neighbor) ->
                    graphRelationship(important, neighbor, ReferenceEvidence.DIRECT, index + 1, neighbor.name)
                }
            val important =
                importantSymbols.mapIndexed { index, symbol ->
                    graphImportant(symbol, relationships, score = 100 - index)
                }

            `when`("important seeds compete with their ranked neighbors") {
                val graph =
                    buildWorkspaceArchitectureGraph(
                        graphReport(importantSymbols + neighbors, relationships, important),
                    )

                then("twenty-one important seeds leave twenty-one slots for connected context") {
                    graph.nodes shouldHaveSize 42
                    graph.nodes.count { it.isImportant } shouldBe 21
                    graph.nodes.map { it.id } shouldNotContain importantSymbols.last().identity.value
                    graph.nodes.map { it.id } shouldNotContain neighbors.last().identity.value
                    graph.omittedNodeCount shouldBe 2
                }
            }
        }

        given("workspace relationships without an important symbol") {
            val source = graphSymbol("root", ":app", "Source", SymbolDetailKind.CLASS)
            val target = graphSymbol("root", ":app", "Target", SymbolDetailKind.CLASS)
            val orphan = graphSymbol("root", ":app", "Orphan", SymbolDetailKind.CLASS)
            val relationship = graphRelationship(source, target, ReferenceEvidence.DIRECT, 1, "Target")

            then("the symbol projection stays empty while ranked connected files remain explorable") {
                val report = graphReport(listOf(orphan, target, source), listOf(relationship), emptyList())
                val graph = buildWorkspaceArchitectureGraph(report)
                graph.nodes shouldBe emptyList()
                graph.edges shouldBe emptyList()
                graph.clusters shouldBe emptyList()
                graph.omittedNodeCount shouldBe 0
                graph.fileNodes.map { it.name } shouldContainExactly listOf("Source.kt", "Target.kt")
                graph.fileNodes.flatMap { it.selectedSymbolIds }.toSet() shouldBe
                    setOf(source.identity.value, target.identity.value)
                graph.fileEdges.single().count shouldBe 1
                graph.cycles shouldBe emptyList()
                graph.omittedFileNodeCount shouldBe 0

                val reversed =
                    report.copy(
                        workspaceIndex =
                            report.workspaceIndex.copy(
                                symbols = report.workspaceIndex.symbols.reversed(),
                                references = report.workspaceIndex.references.reversed(),
                                relationships = report.workspaceIndex.relationships.reversed(),
                            ),
                    )
                buildWorkspaceArchitectureGraph(reversed).toJson() shouldBe graph.toJson()
            }
        }

        given("a directed cycle between selected files") {
            val alpha = graphSymbol("root", ":app", "Alpha", SymbolDetailKind.CLASS)
            val beta = graphSymbol("root", ":app", "Beta", SymbolDetailKind.CLASS)
            val leaf = graphSymbol("root", ":app", "Leaf", SymbolDetailKind.CLASS)
            val relationships =
                listOf(
                    graphRelationship(alpha, beta, ReferenceEvidence.DIRECT, 1, "Beta"),
                    graphRelationship(beta, alpha, ReferenceEvidence.DIRECT, 2, "Alpha"),
                    graphRelationship(beta, leaf, ReferenceEvidence.DIRECT, 3, "Leaf"),
                )
            val report =
                graphReport(
                    listOf(leaf, beta, alpha),
                    relationships,
                    listOf(graphImportant(alpha, relationships), graphImportant(leaf, relationships)),
                ).copy(
                    aggregateAnalysis =
                        AnalysisSummary(
                            findings = emptyList(),
                            hubs = emptyList(),
                            cycles = listOf(listOf("unrelated", "prose", "unrelated")),
                        ),
                )

            `when`("cycles are derived from selected aggregate file edges") {
                val graph = buildWorkspaceArchitectureGraph(report)
                val alphaId = graph.fileNodes.first { it.name == "Alpha.kt" }.id
                val betaId = graph.fileNodes.first { it.name == "Beta.kt" }.id
                val cycle = graph.cycles.single()

                then("only the multi-file strongly connected component is emitted") {
                    cycle.id shouldBe "cycle-1"
                    cycle.memberIds shouldContainExactly listOf(alphaId, betaId).sorted()
                    cycle.edgeIds shouldContainExactly
                        graph.fileEdges
                            .filter { it.source in cycle.memberIds && it.target in cycle.memberIds }
                            .map { it.id }
                            .sorted()
                    cycle.edgeIds shouldHaveSize 2
                }
            }
        }

        given("duplicate important symbol identities") {
            val symbol = graphSymbol("root", ":app", "Duplicate", SymbolDetailKind.CLASS)
            val important = graphImportant(symbol, emptyList())
            val report = graphReport(listOf(symbol), emptyList(), listOf(important, important))

            then("the invalid graph input is rejected deterministically") {
                shouldThrow<IllegalArgumentException> { buildWorkspaceArchitectureGraph(report) }
            }
        }
    })

@Suppress("LongParameterList")
private fun graphSymbol(
    build: String,
    project: String,
    name: String,
    kind: SymbolDetailKind,
    file: String = "src/main/kotlin/example/$name.kt",
    line: Int = 1,
): WorkspaceSymbol =
    WorkspaceSymbol(
        build = build,
        project = project,
        sourceSet = "main",
        name = name,
        qualifiedName = "example.$name",
        kind = kind,
        projectRelativeFile = file,
        declarationLine = line,
    )

@Suppress("LongParameterList")
private fun graphRelationship(
    source: WorkspaceSymbol,
    target: WorkspaceSymbol,
    evidence: ReferenceEvidence,
    line: Int,
    context: String,
    relationshipKind: WorkspaceRelationshipKind = WorkspaceRelationshipKind.CALL,
    referenceKind: ReferenceKind = ReferenceKind.CALL,
): WorkspaceRelationship =
    WorkspaceRelationship(
        source = source,
        target = target,
        kind = relationshipKind,
        sourceEvidence = graphReference(source, target, evidence, line, context, referenceKind),
        evidence = evidence,
    )

private fun unresolvedGraphRelationship(
    target: WorkspaceSymbol,
    context: String,
): WorkspaceRelationship =
    WorkspaceRelationship(
        source = null,
        target = target,
        kind = WorkspaceRelationshipKind.NAME_REFERENCE,
        sourceEvidence =
            WorkspaceReference(
                build = "root",
                project = ":app",
                sourceSet = "main",
                sourceSymbol = null,
                targetName = target.name,
                targetQualifiedName = target.qualifiedName,
                kind = ReferenceKind.NAME_REF,
                projectRelativeFile = "src/main/kotlin/example/Unknown.kt",
                line = 30,
                context = context,
                evidence = ReferenceEvidence.DERIVED,
            ),
        evidence = ReferenceEvidence.DERIVED,
    )

@Suppress("LongParameterList")
private fun graphReference(
    source: WorkspaceSymbol,
    target: WorkspaceSymbol,
    evidence: ReferenceEvidence,
    line: Int,
    context: String,
    kind: ReferenceKind,
): WorkspaceReference =
    WorkspaceReference(
        build = source.build,
        project = source.project,
        sourceSet = source.sourceSet,
        sourceSymbol = source,
        targetName = target.name,
        targetQualifiedName = target.qualifiedName,
        kind = kind,
        projectRelativeFile = source.projectRelativeFile,
        line = line,
        context = context,
        evidence = evidence,
    )

private fun graphImportant(
    symbol: WorkspaceSymbol,
    relationships: List<WorkspaceRelationship>,
    score: Int = 10,
): ImportantSymbol =
    ImportantSymbol(
        symbol = symbol,
        reasons = listOf(ImportantSymbolReason.ENTRY_POINT),
        score = score,
        usage =
            WorkspaceSymbolUsage(
                symbol,
                relationships.filter { it.targetIdentity == symbol.identity },
                relationships.filter { it.sourceIdentity == symbol.identity },
            ),
    )

private fun graphReport(
    symbols: List<WorkspaceSymbol>,
    relationships: List<WorkspaceRelationship>,
    importantSymbols: List<ImportantSymbol>,
): WorkspaceReport =
    WorkspaceReport(
        name = "root",
        rootProjects = emptyList(),
        includedBuilds = emptyList(),
        buildEdges = emptyList(),
        aggregateAnalysis = null,
        entryPoints = emptyList(),
        interfaces = emptyList(),
        workspaceIndex =
            WorkspaceIndex(
                symbols = symbols,
                references = relationships.map { it.sourceEvidence },
                relationships = relationships,
            ),
        importantSymbols = importantSymbols,
    )

private fun graphProject(
    path: String,
    findings: List<Finding>,
): ProjectSummary =
    ProjectSummary(
        projectPath = ProjectPath(path),
        symbols = emptyList(),
        dependencies = emptyList(),
        buildFile = "build.gradle.kts",
        sourceDirs = emptyList(),
        subprojects = emptyList(),
        analysis = AnalysisSummary(findings, emptyList(), emptyList()),
    )
