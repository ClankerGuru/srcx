@file:Suppress("LargeClass")

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
import zone.clanker.gradle.srcx.model.ArchitectureComponent
import zone.clanker.gradle.srcx.model.ArchitectureComponentCycle
import zone.clanker.gradle.srcx.model.ArchitectureDependency
import zone.clanker.gradle.srcx.model.ArchitectureLayer
import zone.clanker.gradle.srcx.model.ArchitectureSummary
import zone.clanker.gradle.srcx.model.DeclarationSemantic
import zone.clanker.gradle.srcx.model.Finding
import zone.clanker.gradle.srcx.model.FindingSeverity
import zone.clanker.gradle.srcx.model.ImportantSymbol
import zone.clanker.gradle.srcx.model.ImportantSymbolReason
import zone.clanker.gradle.srcx.model.IncludedBuildSummary
import zone.clanker.gradle.srcx.model.ProjectPath
import zone.clanker.gradle.srcx.model.ProjectSummary
import zone.clanker.gradle.srcx.model.ReferenceEvidence
import zone.clanker.gradle.srcx.model.ReferenceKind
import zone.clanker.gradle.srcx.model.SourceSetName
import zone.clanker.gradle.srcx.model.SourceSetSummary
import zone.clanker.gradle.srcx.model.SymbolDetailKind
import zone.clanker.gradle.srcx.model.WorkspaceIndex
import zone.clanker.gradle.srcx.model.WorkspaceReference
import zone.clanker.gradle.srcx.model.WorkspaceRelationship
import zone.clanker.gradle.srcx.model.WorkspaceRelationshipKind
import zone.clanker.gradle.srcx.model.WorkspaceReport
import zone.clanker.gradle.srcx.model.WorkspaceSourceFile
import zone.clanker.gradle.srcx.model.WorkspaceSymbol
import zone.clanker.gradle.srcx.model.WorkspaceSymbolUsage

class WorkspaceArchitectureGraphTest :
    BehaviorSpec({
        given("cumulative relationships around an important symbol") {
            val source =
                graphSymbol(
                    build = "root",
                    project = ":app",
                    name = "Source",
                    kind = SymbolDetailKind.CLASS,
                    line = 3,
                )
            val target =
                graphSymbol(
                    build = "library",
                    project = ":api",
                    name = "Contract",
                    kind = SymbolDetailKind.INTERFACE,
                    line = 3,
                )
            val sourceContent =
                "package example\n\nclass Source ${'{'}\n" +
                    "    val payload = \"</script><atlas data-label='quoted'>&{{slot}} snow 雪 " +
                    "${'\u2028'}${'\u2029'}😀\"\n}\n"
            val targetContent = "package example\n\ninterface Contract\n"
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
                    symbols = listOf(source, target),
                    relationships = relationships,
                    importantSymbols = listOf(graphImportant(source, relationships)),
                    sourceFiles =
                        listOf(
                            graphSourceFile(symbol = source, content = sourceContent),
                            graphSourceFile(symbol = target, content = targetContent),
                        ),
                ).copy(includedBuilds = listOf(IncludedBuildSummary("library", "../library", emptyList())))

            `when`("the bounded graph is built") {
                val graph = buildWorkspaceArchitectureGraph(report)

                then("it retains scoped nodes, usage, clusters, and exact aggregated evidence") {
                    graph.nodes shouldHaveSize 2
                    graph.clusters.map { it.label } shouldBe listOf("library / :api", "root / :app")
                    graph.edges shouldHaveSize 3
                    val direct =
                        graph.edges.single {
                            it.kind == WorkspaceRelationshipKind.CALL.name &&
                                it.evidence == ReferenceEvidence.DIRECT.name
                        }
                    direct.recordCount shouldBe 2
                    direct.label shouldBe "${WorkspaceRelationshipKind.CALL.label} x2"
                    direct.crossBuild shouldBe true
                    direct.occurrences.map { it.line } shouldBe listOf(9, 14)
                    val heuristic = graph.edges.first { it.evidence == ReferenceEvidence.HEURISTIC.name }
                    heuristic.recordCount shouldBe 1
                    val importEdge =
                        graph.edges.single { it.kind == WorkspaceRelationshipKind.IMPORT.name }
                    importEdge.recordCount shouldBe 1
                    importEdge.evidence shouldBe ReferenceEvidence.DIRECT.name
                    importEdge.occurrences.map { it.line } shouldBe listOf(2)
                    val targetNode = graph.nodes.first { it.id == target.identity.value }
                    targetNode.localInbound shouldBe 0
                    targetNode.workspaceInbound shouldBe 3
                    targetNode.crossBuildInbound shouldBe 3
                    targetNode.outgoingRecordCount shouldBe 0
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
                    sourceFile.importance shouldBe ImportantSymbolReason.ENTRY_POINT.score
                    val fileEdge = graph.fileEdges.single()
                    fileEdge.source shouldBe sourceFile.id
                    fileEdge.target shouldBe graph.fileNodes.first { it.path == target.projectRelativeFile }.id
                    fileEdge.recordCount shouldBe 4
                    fileEdge.kindCounts.map { it.kind to it.count } shouldContainExactly
                        listOf(
                            WorkspaceRelationshipKind.CALL.name to 3,
                            WorkspaceRelationshipKind.IMPORT.name to 1,
                        )
                    fileEdge.evidenceCounts.map { it.evidence to it.count } shouldContainExactly
                        listOf(ReferenceEvidence.DIRECT.name to 3, ReferenceEvidence.HEURISTIC.name to 1)
                    fileEdge.occurrences.map { it.line } shouldContainExactly listOf(9, 14, 22, 2)
                    fileEdge.occurrences.map { it.sourceSymbolId }.distinct() shouldBe listOf(source.identity.value)
                    fileEdge.occurrences.map { it.targetSymbolId }.distinct() shouldBe listOf(target.identity.value)
                }

                then("it embeds exact complete source and all source-scoped evidence lines") {
                    graph.sourceFiles.map { it.id } shouldContainExactly graph.fileNodes.map { it.id }
                    val sourceFile = graph.sourceFiles.first { it.id.contains("Source.kt") }
                    sourceFile.content shouldBe sourceContent
                    sourceFile.declarationLines shouldContainExactly listOf(3)
                    sourceFile.relationshipLines shouldContainExactly listOf(2, 9, 14, 22)
                    graph.sourceFiles.first { it.id.contains("Contract.kt") }.relationshipLines shouldBe emptyList()
                }

                then("it emits one uncapped shared catalog for scoped refills") {
                    graph.availableNodes.map { it.id } shouldContainExactly graph.nodes.map { it.id }
                    graph.availableEdges shouldBe graph.edges
                    graph.availableFileNodes.map { it.id } shouldContainExactly graph.fileNodes.map { it.id }
                    graph.availableFileEdges shouldBe graph.fileEdges
                    graph.availableCycles shouldBe graph.cycles
                    graph.availableEdges
                        .single {
                            it.kind == WorkspaceRelationshipKind.CALL.name &&
                                it.evidence == ReferenceEvidence.DIRECT.name
                        }.occurrences
                        .map { it.line } shouldContainExactly listOf(9, 14)
                    graph.availableEdges
                        .single { it.kind == WorkspaceRelationshipKind.IMPORT.name }
                        .occurrences
                        .map { it.line } shouldContainExactly listOf(2)
                }

                then("it emits stable build ownership metadata and explicit record totals") {
                    graph.builds.map { it.name } shouldContainExactly listOf("root", "library")
                    val rootBuild = graph.builds.first { it.name == "root" }
                    rootBuild.context shouldBe "Root build"
                    rootBuild.indexedFileCount shouldBe 1
                    rootBuild.indexedSymbolCount shouldBe 1
                    rootBuild.projects.map { it.name } shouldContainExactly listOf(":app")
                    val included = graph.builds.first { it.name == "library" }
                    included.context shouldBe "Included build"
                    included.relativePath shouldBe "../library"
                    included.fileNodeCount shouldBe 1
                    included.projectCount shouldBe 1
                    included.indexedFileCount shouldBe 1
                    included.indexedSymbolCount shouldBe 1
                    included.projects.single().name shouldBe ":api"
                    included.color shouldBe graph.builds.first { it.name == "library" }.color
                    graph.totalRelationshipRecordCount shouldBe 4
                    graph.shownRelationshipRecordCount shouldBe 4
                }

                then("JSON transport cannot terminate its script element or expose template slots") {
                    val json = graph.toJson()
                    json shouldContain "\"defaultView\":\"files\""
                    json shouldContain "\"fileNodes\""
                    json shouldContain "\"fileEdges\""
                    json shouldContain "\"cycles\""
                    json shouldContain "\"sourceFiles\""
                    json shouldContain "\"availableNodes\""
                    json shouldContain "\"availableEdges\""
                    json shouldContain "\"availableFileNodes\""
                    json shouldContain "\"availableFileEdges\""
                    json shouldContain "\"availableCycles\""
                    json shouldContain "\"builds\""
                    json shouldContain "\"context\":\"Included build\""
                    json shouldContain "\"projects\":[{\"name\":\":app\""
                    json shouldContain "\"recordCount\":4"
                    json shouldContain "\"totalRelationshipRecordCount\":4"
                    json shouldContain "payload = \\\"\\u003c/script"
                    json shouldContain "\\u003c/script\\u003e"
                    json shouldContain "\\u003catlas data-label='quoted'\\u003e"
                    json shouldContain "\\u0026"
                    json shouldContain "\\u007b\\u007bslot\\u007d\\u007d"
                    json shouldContain "\\u2028"
                    json shouldContain "\\u2029"
                    json shouldContain "雪"
                    json shouldContain "😀"
                    json shouldNotContain "</script"
                    json shouldNotContain "<atlas"
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

        given("declared workspace builds and projects outside the bounded projection") {
            val source = graphSymbol("root", ":app", "Source", SymbolDetailKind.CLASS)
            val target = graphSymbol("build-20", ":api", "Target", SymbolDetailKind.CLASS)
            val indexedOnly = graphSymbol("build-07", ":two", "IndexedOnly", SymbolDetailKind.CLASS)
            val relationship = graphRelationship(source, target, ReferenceEvidence.DIRECT, 4, "Target")
            val includedBuilds =
                (1..20).map { index ->
                    val name = "build-${index.toString().padStart(2, '0')}"
                    val projectPaths = if (index == 20) listOf(":api", ":unused") else listOf(":one", ":two")
                    IncludedBuildSummary(
                        name = name,
                        relativePath = "../$name",
                        projects =
                            projectPaths.map { path ->
                                graphProject(
                                    path = path,
                                    findings = emptyList(),
                                    sourceSets =
                                        if (index == 7 && path == ":two") {
                                            listOf("main", "slopTest")
                                        } else {
                                            emptyList()
                                        },
                                )
                            },
                    )
                }
            val report =
                graphReport(
                    symbols = listOf(source, target, indexedOnly),
                    relationships = listOf(relationship),
                    importantSymbols = listOf(graphImportant(source, listOf(relationship))),
                ).copy(
                    rootProjects = listOf(graphProject(":app", emptyList()), graphProject(":admin", emptyList())),
                    includedBuilds = includedBuilds,
                )

            `when`("the build navigator payload is assembled") {
                val graph = buildWorkspaceArchitectureGraph(report)

                then("it retains complete build and project membership with bounded-node counts") {
                    graph.builds.map { it.name } shouldContainExactly
                        listOf("root") + (1..20).map { index -> "build-${index.toString().padStart(2, '0')}" }
                    graph.builds.map { it.color }.distinct() shouldHaveSize 21
                    val rootBuild = graph.builds.first()
                    rootBuild.projects.map { it.name } shouldContainExactly listOf(":admin", ":app")
                    rootBuild.projects.map { it.fileNodeCount } shouldContainExactly listOf(0, 1)
                    rootBuild.indexedFileCount shouldBe 1
                    rootBuild.indexedSymbolCount shouldBe 1
                    val representedBuild = graph.builds.first { it.name == "build-07" }
                    representedBuild.projectCount shouldBe 2
                    representedBuild.fileNodeCount shouldBe 1
                    representedBuild.symbolNodeCount shouldBe 1
                    representedBuild.indexedFileCount shouldBe 1
                    representedBuild.indexedSymbolCount shouldBe 1
                    representedBuild.sourceSets shouldContainExactly listOf("main", "slopTest")
                    representedBuild.projects.map { it.name } shouldContainExactly listOf(":one", ":two")
                    representedBuild.projects.map { it.fileNodeCount } shouldContainExactly listOf(0, 1)
                    val indexedProject = representedBuild.projects.single { it.name == ":two" }
                    indexedProject.fileNodeCount shouldBe 1
                    indexedProject.symbolNodeCount shouldBe 1
                    indexedProject.indexedFileCount shouldBe 1
                    indexedProject.indexedSymbolCount shouldBe 1
                    indexedProject.sourceSets shouldContainExactly listOf("main", "slopTest")
                    val finalBuild = graph.builds.first { it.name == "build-20" }
                    finalBuild.projects.map { it.name } shouldContainExactly
                        listOf(":api", ":unused")
                    finalBuild.indexedFileCount shouldBe 1
                    finalBuild.indexedSymbolCount shouldBe 1
                    finalBuild.projects.single { it.name == ":api" }.indexedFileCount shouldBe 1
                    finalBuild.projects.single { it.name == ":unused" }.indexedFileCount shouldBe 0
                }

                then("its JSON exposes hierarchy counts without dropping empty modules") {
                    val json = graph.toJson()
                    val indexedBuildJson =
                        json.substringAfter("\"name\":\"build-07\"").substringBefore("\"name\":\"build-08\"")
                    json shouldContain "\"name\":\"build-07\""
                    json shouldContain "\"projectCount\":2"
                    indexedBuildJson shouldContain
                        "\"fileNodeCount\":1, \"symbolNodeCount\":1, " +
                        "\"indexedFileCount\":1, \"indexedSymbolCount\":1"
                    indexedBuildJson shouldContain "\"sourceSets\":[\"main\", \"slopTest\"]"
                    json shouldContain "\"name\":\":unused\", \"fileNodeCount\":0, \"symbolNodeCount\":0"
                    json shouldContain
                        "\"name\":\":two\", \"fileNodeCount\":1, \"symbolNodeCount\":1, " +
                        "\"indexedFileCount\":1, \"indexedSymbolCount\":1"
                }

                then("membership order cannot change the navigator payload") {
                    val reordered =
                        report.copy(
                            rootProjects = report.rootProjects.reversed(),
                            includedBuilds =
                                report.includedBuilds.reversed().map { build ->
                                    build.copy(projects = build.projects.reversed())
                                },
                        )
                    buildWorkspaceArchitectureGraph(reordered).toJson() shouldBe graph.toJson()
                }
            }
        }

        given("a typed project whose declarations fall beyond the global cap") {
            val root = graphSymbol("root", ":app", "RootEntry", SymbolDetailKind.CLASS)
            val includedSymbols =
                (1..ARCHITECTURE_GRAPH_NODE_LIMIT).map { index ->
                    val suffix = index.toString().padStart(2, '0')
                    graphSymbol(
                        build = "build-$suffix",
                        project = if (index == ARCHITECTURE_GRAPH_NODE_LIMIT) ":hidden" else ":module",
                        name = "Build${suffix}Type",
                        kind = SymbolDetailKind.CLASS,
                    )
                }
            val visibleCycleMember = includedSymbols[ARCHITECTURE_GRAPH_NODE_LIMIT - 2]
            val hiddenCycleMember = includedSymbols.last()
            val relationships =
                listOf(
                    graphRelationship(root, includedSymbols.first(), ReferenceEvidence.DIRECT, 3, "Build01Type"),
                    graphRelationship(
                        visibleCycleMember,
                        hiddenCycleMember,
                        ReferenceEvidence.DIRECT,
                        7,
                        "Build42Type",
                    ),
                    graphRelationship(
                        hiddenCycleMember,
                        visibleCycleMember,
                        ReferenceEvidence.DIRECT,
                        9,
                        "Build41Type",
                    ),
                )
            val includedBuilds =
                includedSymbols.mapIndexed { index, symbol ->
                    IncludedBuildSummary(
                        name = symbol.build,
                        relativePath = "../${symbol.build}",
                        projects =
                            listOf(
                                graphProject(
                                    path = if (index == includedSymbols.lastIndex) ":hidden" else ":module",
                                    findings = emptyList(),
                                ),
                            ),
                    )
                }
            val report =
                graphReport(
                    symbols = listOf(root) + includedSymbols,
                    relationships = relationships,
                    importantSymbols = listOf(graphImportant(root, relationships)),
                ).copy(
                    rootProjects = listOf(graphProject(":app", emptyList())),
                    includedBuilds = includedBuilds,
                )

            `when`("the bounded overview and shared catalogs are rendered") {
                val graph = buildWorkspaceArchitectureGraph(report)
                val hiddenFileId = hiddenCycleMember.graphTestFileId()

                then("the omitted project remains refillable with exact typed source and edges") {
                    graph.nodes shouldHaveSize ARCHITECTURE_GRAPH_NODE_LIMIT
                    graph.fileNodes shouldHaveSize ARCHITECTURE_GRAPH_FILE_NODE_LIMIT
                    graph.nodes.map { it.id } shouldNotContain hiddenCycleMember.identity.value
                    graph.fileNodes.map { it.id } shouldNotContain hiddenFileId
                    val hiddenProject =
                        graph.builds
                            .single { it.name == hiddenCycleMember.build }
                            .projects
                            .single { it.name == ":hidden" }
                    hiddenProject.symbolNodeCount shouldBe 0
                    hiddenProject.fileNodeCount shouldBe 0
                    hiddenProject.indexedSymbolCount shouldBe 1
                    hiddenProject.indexedFileCount shouldBe 1

                    graph.availableNodes shouldHaveSize ARCHITECTURE_GRAPH_NODE_LIMIT + 1
                    graph.availableFileNodes shouldHaveSize ARCHITECTURE_GRAPH_FILE_NODE_LIMIT + 1
                    graph.availableNodes.map { it.id } shouldContain hiddenCycleMember.identity.value
                    graph.availableFileNodes.map { it.id } shouldContain hiddenFileId
                    graph.sourceFiles.map { it.id } shouldContain hiddenFileId
                    graph.sourceFiles.single { it.id == hiddenFileId }.content shouldBe
                        "complete source for ${hiddenCycleMember.name}\n"
                    graph.availableEdges.sumOf { it.recordCount } shouldBe relationships.size
                    graph.availableFileEdges.sumOf { it.recordCount } shouldBe relationships.size
                    graph.availableEdges.all { it.crossBuild } shouldBe true
                    graph.availableFileEdges.all { it.crossBuild } shouldBe true
                    graph.edges.forEach { boundedEdge ->
                        graph.availableEdges.single { availableEdge ->
                            availableEdge.source == boundedEdge.source &&
                                availableEdge.target == boundedEdge.target &&
                                availableEdge.kind == boundedEdge.kind &&
                                availableEdge.evidence == boundedEdge.evidence
                        } shouldBe boundedEdge
                    }
                    graph.fileEdges.forEach { boundedEdge ->
                        graph.availableFileEdges.single { availableEdge ->
                            availableEdge.source == boundedEdge.source &&
                                availableEdge.target == boundedEdge.target
                        } shouldBe boundedEdge
                    }
                }

                then("available cycles reference only stable available edge IDs") {
                    graph.cycles shouldBe emptyList()
                    val cycle = graph.availableCycles.single()
                    cycle.memberIds shouldContainExactly
                        listOf(visibleCycleMember.graphTestFileId(), hiddenFileId).sorted()
                    cycle.edgeIds shouldHaveSize 2
                    cycle.edgeIds.all { edgeId ->
                        graph.availableFileEdges.any { edge -> edge.id == edgeId }
                    } shouldBe true
                }

                then("reversing every unordered typed input preserves the complete JSON") {
                    val reversed =
                        report.copy(
                            includedBuilds =
                                report.includedBuilds.reversed().map { build ->
                                    build.copy(projects = build.projects.reversed())
                                },
                            workspaceIndex =
                                report.workspaceIndex.copy(
                                    symbols = report.workspaceIndex.symbols.reversed(),
                                    references = report.workspaceIndex.references.reversed(),
                                    relationships = report.workspaceIndex.relationships.reversed(),
                                ),
                            importantSymbols = report.importantSymbols.reversed(),
                        )
                    buildWorkspaceArchitectureGraph(reversed).toJson() shouldBe graph.toJson()
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
                    shared.totalInternalRecordCount shouldBe 2
                    shared.shownInternalRecordCount shouldBe 2
                    shared.totalOutgoingRecordCount shouldBe 1
                    shared.shownOutgoingRecordCount shouldBe 1
                    shared.totalIncomingRecordCount shouldBe 0
                    shared.internalDeclarationCount shouldBe 2
                    shared.internalOccurrences.map { it.line } shouldContainExactly listOf(2, 8)
                    graph.fileEdges shouldHaveSize 1
                    graph.fileEdges.none { it.source == it.target } shouldBe true
                    val contexts =
                        graph.fileEdges
                            .single()
                            .occurrences
                            .map { it.context }
                    contexts shouldContainExactly listOf("Target()")
                    graph.totalRelationshipRecordCount shouldBe 3
                    graph.shownRelationshipRecordCount shouldBe 3
                }

                then("typed file paths provide exact file and project finding counts") {
                    shared.fileFindingCount shouldBe 1
                    shared.projectFindingCount shouldBe 3
                    graph.fileNodes.first { it.path == target.projectRelativeFile }.fileFindingCount shouldBe 0
                }
            }
        }

        given("a finding with Windows path separators") {
            val sourcePath = "src/main/kotlin/example/Source.kt"
            val source = graphSymbol("root", ":app", "Source", SymbolDetailKind.CLASS, sourcePath)
            val target = graphSymbol("root", ":app", "Target", SymbolDetailKind.CLASS)
            val relationship = graphRelationship(source, target, ReferenceEvidence.DIRECT, 4, "Target()")
            val finding =
                Finding(
                    FindingSeverity.WARNING,
                    "Windows-scoped finding",
                    "Fix the exact file",
                    sourcePath.replace('/', '\\'),
                )
            val report =
                graphReport(
                    listOf(source, target),
                    listOf(relationship),
                    listOf(graphImportant(source, listOf(relationship))),
                ).copy(rootProjects = listOf(graphProject(":app", listOf(finding))))

            `when`("the finding is matched and serialized") {
                val graph = buildWorkspaceArchitectureGraph(report)
                val sourceFile = graph.fileNodes.first { it.path == sourcePath }

                then("the normalized path provides exact file evidence") {
                    sourceFile.fileFindingCount shouldBe 1
                    sourceFile.findingIds shouldContainExactly listOf("finding-1")
                    graph.findings.single().filePath shouldBe sourcePath
                    graph.toJson() shouldContain "\"filePath\":\"$sourcePath\""
                    graph.toJson() shouldNotContain "Source.kt\\\\"
                }
            }
        }

        given("more exact finding files than the bounded file projection can show") {
            val findings =
                (0..ARCHITECTURE_GRAPH_FILE_NODE_LIMIT).map { index ->
                    val path = "src/main/kotlin/example/Finding${index.toString().padStart(2, '0')}.kt"
                    Finding(
                        severity = FindingSeverity.WARNING,
                        message = "Exact file finding $index",
                        suggestion = "Review the exact file",
                        filePath = path,
                        line = 1,
                    )
                }
            val sourceFiles =
                findings.map { finding ->
                    WorkspaceSourceFile(
                        build = "root",
                        project = ":app",
                        sourceSet = "main",
                        projectRelativeFile = requireNotNull(finding.filePath),
                        content = "class Finding\n",
                    )
                }
            val report =
                graphReport(
                    symbols = emptyList(),
                    relationships = emptyList(),
                    importantSymbols = emptyList(),
                    sourceFiles = sourceFiles,
                ).copy(rootProjects = listOf(graphProject(":app", findings)))

            `when`("finding links are derived from the completed graph") {
                val graph = buildWorkspaceArchitectureGraph(report)
                val evidence = workspaceArchitectureFindingEvidence(report, graph)
                val omittedFinding = findings.last()
                val omittedKey = WorkspaceArchitectureFindingKeyRenderer("root", ":app", omittedFinding)

                then("every exact finding in the shared file catalog receives an exact-file link") {
                    graph.fileNodes shouldHaveSize ARCHITECTURE_GRAPH_FILE_NODE_LIMIT
                    graph.omittedFileNodeCount shouldBe 1
                    evidence.values.count {
                        it.linkKind == WorkspaceArchitectureFindingLinkKindRenderer.EXACT_FILE
                    } shouldBe ARCHITECTURE_GRAPH_FILE_NODE_LIMIT + 1
                    evidence.getValue(omittedKey).id shouldBe "finding-${ARCHITECTURE_GRAPH_FILE_NODE_LIMIT + 1}"
                    evidence.getValue(omittedKey).linkKind shouldBe
                        WorkspaceArchitectureFindingLinkKindRenderer.EXACT_FILE
                    graph.availableFileNodes.map { file -> file.findingIds }.flatten() shouldContain
                        evidence.getValue(omittedKey).id
                }

                then("input ordering cannot change stable finding IDs or projection eligibility") {
                    val reordered =
                        report.copy(
                            rootProjects = listOf(graphProject(":app", findings.reversed())),
                        )
                    val reorderedGraph = buildWorkspaceArchitectureGraph(reordered)

                    workspaceArchitectureFindingEvidence(reordered, reorderedGraph) shouldBe evidence
                }
            }
        }

        given("typed analyzer evidence whose participants are outside the bounded projection") {
            val alpha = graphSymbol("root", ":app", "UnindexedAlpha", SymbolDetailKind.CLASS)
            val beta = graphSymbol("root", ":app", "UnindexedBeta", SymbolDetailKind.CLASS)
            val cycle = ArchitectureComponentCycle(listOf(alpha.qualifiedName, beta.qualifiedName, alpha.qualifiedName))
            val componentFinding =
                Finding(
                    severity = FindingSeverity.INFO,
                    message = "Unprojected analyzer component",
                    suggestion = "Review the typed component",
                    componentIds = listOf(alpha.qualifiedName),
                )
            val cycleFinding =
                Finding(
                    severity = FindingSeverity.WARNING,
                    message = "Unprojected analyzer cycle",
                    suggestion = "Break the typed route",
                    componentIds = listOf(alpha.qualifiedName, beta.qualifiedName),
                    componentCycle = cycle,
                )
            val architecture =
                ArchitectureSummary(
                    components = listOf(alpha, beta).map(::graphArchitectureComponent),
                    dependencies =
                        listOf(
                            ArchitectureDependency(alpha.qualifiedName, beta.qualifiedName),
                            ArchitectureDependency(beta.qualifiedName, alpha.qualifiedName),
                        ),
                    cycles = listOf(cycle),
                )
            val report =
                graphReport(emptyList(), emptyList(), emptyList(), emptyList()).copy(
                    rootProjects =
                        listOf(
                            graphProject(
                                path = ":app",
                                findings = listOf(componentFinding, cycleFinding),
                                architecture = architecture,
                            ),
                        ),
                )

            `when`("finding links are derived from typed analyzer metadata") {
                val graph = buildWorkspaceArchitectureGraph(report)
                val evidence = workspaceArchitectureFindingEvidence(report, graph)

                then("component and cycle links remain available without projected participants") {
                    graph.nodes shouldBe emptyList()
                    graph.fileNodes shouldBe emptyList()
                    val componentEvidence =
                        evidence.getValue(
                            WorkspaceArchitectureFindingKeyRenderer("root", ":app", componentFinding),
                        )
                    val cycleEvidence =
                        evidence.getValue(
                            WorkspaceArchitectureFindingKeyRenderer("root", ":app", cycleFinding),
                        )
                    componentEvidence.linkKind shouldBe WorkspaceArchitectureFindingLinkKindRenderer.ANALYZER_COMPONENT
                    cycleEvidence.linkKind shouldBe WorkspaceArchitectureFindingLinkKindRenderer.ANALYZER_CYCLE
                    componentEvidence.linkKind?.buttonLabel shouldBe "Open analyzer finding evidence"
                    cycleEvidence.linkKind?.buttonLabel shouldBe "Open analyzer cycle evidence"
                }
            }
        }

        given("an exact typed component finding in an otherwise isolated declaration") {
            val sourcePath = "src/main/kotlin/example/ExactSource.kt"
            val source = graphSymbol("root", ":app", "ExactSource", SymbolDetailKind.CLASS, sourcePath, 17)
            val component = graphArchitectureComponent(source)
            val finding =
                Finding(
                    severity = FindingSeverity.FORBIDDEN,
                    message = "Exact component boundary",
                    suggestion = "Move the declaration",
                    filePath = sourcePath,
                    line = 19,
                    componentIds = listOf(component.id),
                )
            val report =
                graphReport(
                    symbols = listOf(source),
                    relationships = emptyList(),
                    importantSymbols = emptyList(),
                    sourceFiles = listOf(graphSourceFile(source, "class ExactSource\n")),
                ).copy(
                    rootProjects =
                        listOf(
                            graphProject(
                                path = ":app",
                                findings = listOf(finding),
                                architecture = ArchitectureSummary(components = listOf(component)),
                            ),
                        ),
                )

            `when`("the bounded Atlas graph is built") {
                val graph = buildWorkspaceArchitectureGraph(report)
                val renderedFinding = graph.findings.single()
                val fileNode = graph.fileNodes.single()

                then("the analyzer component seeds both symbol and file payloads before ranking") {
                    graph.nodes.map { it.id } shouldContainExactly listOf(source.identity.value)
                    fileNode.id shouldBe graphFileId("root", ":app", "main", sourcePath)
                    fileNode.selectedSymbolIds shouldContainExactly listOf(source.identity.value)
                    fileNode.symbols.map { it.id } shouldContainExactly listOf(source.identity.value)
                    fileNode.findingIds shouldContainExactly listOf(renderedFinding.id)
                    graph.sourceFiles.single().declarationLines shouldContainExactly listOf(17)
                    graph.sourceFiles.single().relationshipLines shouldBe emptyList()
                }

                then("the finding keeps exact typed declarations, file scope, line, and stable JSON fields") {
                    renderedFinding.id shouldBe "finding-1"
                    renderedFinding.filePath shouldBe sourcePath
                    renderedFinding.line shouldBe 19
                    renderedFinding.componentIds shouldContainExactly listOf(component.id)
                    renderedFinding.componentSymbolIds shouldContainExactly listOf(source.identity.value)
                    renderedFinding.componentFileIds shouldContainExactly listOf(fileNode.id)
                    renderedFinding.analysisCycleId shouldBe null
                    val json = graph.toJson()
                    json shouldContain "\"componentIds\":[\"${component.id}\"]"
                    json shouldContain "\"componentSymbolIds\":[\"${source.identity.value}\"]"
                    json shouldContain "\"componentFileIds\":[\"${fileNode.id}\"]"
                    json shouldContain "\"analysisCycleId\":null"
                }
            }
        }

        given("an analyzer-inferred compound cycle with project-only participants") {
            val alpha = graphSymbol("root", ":app", "Alpha", SymbolDetailKind.CLASS, line = 3)
            val beta = graphSymbol("root", ":app", "Beta", SymbolDetailKind.CLASS, line = 7)
            val gamma = graphSymbol("root", ":app", "Gamma", SymbolDetailKind.CLASS, line = 11)
            val delta = graphSymbol("root", ":app", "Delta", SymbolDetailKind.CLASS, line = 15)
            val components = listOf(alpha, beta, gamma, delta).map(::graphArchitectureComponent)
            val analysisRoute =
                ArchitectureComponentCycle(
                    listOf(alpha.qualifiedName, beta.qualifiedName, gamma.qualifiedName, alpha.qualifiedName),
                )
            val projectOnlyRoute =
                ArchitectureComponentCycle(
                    listOf(alpha.qualifiedName, gamma.qualifiedName, delta.qualifiedName, alpha.qualifiedName),
                )
            val finding =
                Finding(
                    severity = FindingSeverity.WARNING,
                    message = "Compound component cycle",
                    suggestion = "Break the directed route",
                    componentIds = analysisRoute.componentIds.dropLast(1),
                    componentCycle = analysisRoute,
                )
            val relationships =
                listOf(
                    graphRelationship(alpha, beta, ReferenceEvidence.DIRECT, 4, "Beta"),
                    graphRelationship(beta, gamma, ReferenceEvidence.DIRECT, 8, "Gamma"),
                    graphRelationship(gamma, alpha, ReferenceEvidence.DIRECT, 12, "Alpha"),
                    graphRelationship(beta, alpha, ReferenceEvidence.DIRECT, 9, "Alpha"),
                    graphRelationship(gamma, delta, ReferenceEvidence.DIRECT, 13, "Delta"),
                    graphRelationship(delta, alpha, ReferenceEvidence.DIRECT, 16, "Alpha"),
                )
            val architecture =
                ArchitectureSummary(
                    components = components,
                    dependencies =
                        listOf(
                            ArchitectureDependency(alpha.qualifiedName, beta.qualifiedName),
                            ArchitectureDependency(beta.qualifiedName, gamma.qualifiedName),
                            ArchitectureDependency(gamma.qualifiedName, alpha.qualifiedName),
                            ArchitectureDependency(beta.qualifiedName, alpha.qualifiedName),
                            ArchitectureDependency(alpha.qualifiedName, gamma.qualifiedName),
                            ArchitectureDependency(gamma.qualifiedName, delta.qualifiedName),
                            ArchitectureDependency(delta.qualifiedName, alpha.qualifiedName),
                        ),
                    cycles = listOf(analysisRoute, projectOnlyRoute),
                )
            val report =
                graphReport(
                    symbols = listOf(gamma, alpha, delta, beta),
                    relationships = relationships,
                    importantSymbols = emptyList(),
                ).copy(rootProjects = listOf(graphProject(":app", listOf(finding), architecture)))

            `when`("typed and observed cycle evidence is projected") {
                val graph = buildWorkspaceArchitectureGraph(report)
                val analysisCycle = graph.analysisCycles.single { cycle -> cycle.findingIds.isNotEmpty() }
                val projectOnlyCycle = graph.analysisCycles.single { cycle -> cycle.findingIds.isEmpty() }
                val observedCycle = graph.cycles.single()
                val renderedFinding = graph.findings.single()

                then("all typed cycle participants seed symbol and file declarations before ranking") {
                    graph.nodes.map { it.id }.toSet() shouldBe
                        setOf(alpha, beta, gamma, delta).map { it.identity.value }.toSet()
                    graph.fileNodes.map { it.path }.toSet() shouldBe
                        setOf(
                            alpha.projectRelativeFile,
                            beta.projectRelativeFile,
                            gamma.projectRelativeFile,
                            delta.projectRelativeFile,
                        )
                    graph.analysisCycles.flatMap { it.memberSymbolIds }.toSet() shouldBe
                        graph.nodes.map { it.id }.toSet()
                    graph.analysisCycles.flatMap { it.memberFileIds }.toSet() shouldBe
                        graph.fileNodes.map { it.id }.toSet()
                    projectOnlyCycle.componentIds shouldContainExactly projectOnlyRoute.componentIds
                    projectOnlyCycle.memberSymbolIds shouldContain delta.identity.value
                    projectOnlyCycle.memberFileIds shouldContain
                        graph.fileNodes.single { it.path == delta.projectRelativeFile }.id
                }

                then("the qualified analyzer route remains closed, ordered, scoped, and linked to its finding") {
                    analysisCycle.id shouldBe "analysis-cycle-1"
                    analysisCycle.build shouldBe "root"
                    analysisCycle.project shouldBe ":app"
                    analysisCycle.componentIds shouldContainExactly analysisRoute.componentIds
                    analysisCycle.route.map { it.componentId } shouldContainExactly analysisRoute.componentIds
                    analysisCycle.route.map { it.name } shouldContainExactly listOf("Alpha", "Beta", "Gamma", "Alpha")
                    analysisCycle.route.mapNotNull { it.sourceSet }.distinct() shouldContainExactly listOf("main")
                    analysisCycle.route.mapNotNull { it.line } shouldContainExactly listOf(3, 7, 11, 3)
                    analysisCycle.findingIds shouldContainExactly listOf(renderedFinding.id)
                    renderedFinding.analysisCycleId shouldBe analysisCycle.id
                    renderedFinding.componentIds shouldContainExactly analysisRoute.componentIds.dropLast(1)
                    renderedFinding.componentSymbolIds.toSet() shouldBe
                        setOf(alpha, beta, gamma).map { it.identity.value }.toSet()
                    renderedFinding.componentFileIds.toSet() shouldBe analysisCycle.memberFileIds.toSet()
                }

                then("observed relationship cycles stay independent and cover every compound edge") {
                    observedCycle.memberIds shouldHaveSize 4
                    observedCycle.edgeIds shouldHaveSize 6
                    observedCycle.routes.flatMap { route -> route.edgeIds }.toSet() shouldBe
                        observedCycle.edgeIds.toSet()
                    observedCycle.routes.all { route -> route.nodeIds.first() == route.nodeIds.last() } shouldBe true
                    graph.toJson() shouldContain "\"analysisCycles\":[{\"id\":\"analysis-cycle-1\""
                    graph.toJson() shouldContain "\"evidence\":\"ANALYZER_INFERRED\""
                }

                then("input ordering cannot change either typed or observed cycle payloads") {
                    val reordered =
                        report.copy(
                            rootProjects =
                                listOf(
                                    graphProject(
                                        ":app",
                                        listOf(finding),
                                        architecture.copy(
                                            components = components.reversed(),
                                            dependencies = architecture.dependencies.reversed(),
                                            cycles = architecture.cycles.reversed(),
                                        ),
                                    ),
                                ),
                            workspaceIndex =
                                report.workspaceIndex.copy(
                                    symbols = report.workspaceIndex.symbols.reversed(),
                                    references = report.workspaceIndex.references.reversed(),
                                    relationships = report.workspaceIndex.relationships.reversed(),
                                ),
                        )
                    buildWorkspaceArchitectureGraph(reordered).toJson() shouldBe graph.toJson()
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
                importantSymbols.map { symbol -> graphImportant(symbol, relationships) }

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

        given("typed finding declarations exceed the bounded symbol cap") {
            val prioritySymbols =
                (0..ARCHITECTURE_GRAPH_NODE_LIMIT).map { index ->
                    graphSymbol(
                        "root",
                        ":app",
                        "Priority${index.toString().padStart(2, '0')}",
                        SymbolDetailKind.CLASS,
                    )
                }
            val rankedImportant =
                (0 until 22).map { index ->
                    graphSymbol(
                        "root",
                        ":app",
                        "ImportantAfterPriority${index.toString().padStart(2, '0')}",
                        SymbolDetailKind.CLASS,
                    )
                }
            val finding =
                Finding(
                    severity = FindingSeverity.WARNING,
                    message = "Many typed declarations",
                    suggestion = "Review the bounded projection",
                    componentIds = prioritySymbols.map { symbol -> symbol.qualifiedName },
                )
            val report =
                graphReport(
                    symbols = prioritySymbols + rankedImportant,
                    relationships = emptyList(),
                    importantSymbols = rankedImportant.map { symbol -> graphImportant(symbol, emptyList()) },
                ).copy(rootProjects = listOf(graphProject(":app", listOf(finding))))

            then("ranked important seeds cannot overflow the named cap") {
                val graph = buildWorkspaceArchitectureGraph(report)

                graph.nodes shouldHaveSize ARCHITECTURE_GRAPH_NODE_LIMIT
                graph.nodes.map { it.id }.toSet() shouldBe
                    prioritySymbols.take(ARCHITECTURE_GRAPH_NODE_LIMIT).map { it.identity.value }.toSet()
                graph.nodes.none { it.isImportant } shouldBe true
                graph.omittedNodeCount shouldBe rankedImportant.size + 1
                graph.fileNodes shouldHaveSize ARCHITECTURE_GRAPH_FILE_NODE_LIMIT
                graph.findings
                    .single()
                    .componentSymbolIds
                    .toSet() shouldBe
                    prioritySymbols.map { it.identity.value }.toSet()
                graph.findings.single().componentFileIds shouldHaveSize ARCHITECTURE_GRAPH_NODE_LIMIT + 1
                (
                    graph.findings
                        .single()
                        .componentSymbolIds
                        .toSet() - graph.nodes.map { it.id }.toSet()
                ).shouldHaveSize(1)
                (
                    graph.findings
                        .single()
                        .componentFileIds
                        .toSet() - graph.fileNodes.map { it.id }.toSet()
                ).shouldHaveSize(1)
            }
        }

        given("forty strict-priority declarations and one evidence-rich relationship pair") {
            val prioritySymbols =
                (0 until ARCHITECTURE_GRAPH_NODE_LIMIT - 2).map { index ->
                    graphSymbol(
                        "root",
                        ":app",
                        "Priority${index.toString().padStart(2, '0')}",
                        SymbolDetailKind.CLASS,
                    )
                }
            val connectedSource = graphSymbol("root", ":app", "ConnectedSource", SymbolDetailKind.CLASS)
            val connectedTarget = graphSymbol("root", ":app", "ConnectedTarget", SymbolDetailKind.INTERFACE)
            val isolated = graphSymbol("root", ":app", "IsolatedRankingSignal", SymbolDetailKind.CLASS)
            val directCalls =
                (1..3).map { line ->
                    graphRelationship(
                        connectedSource,
                        connectedTarget,
                        ReferenceEvidence.DIRECT,
                        line,
                        "direct-$line",
                    )
                }
            val heuristicCalls =
                (4..5).map { line ->
                    graphRelationship(
                        connectedSource,
                        connectedTarget,
                        ReferenceEvidence.HEURISTIC,
                        line,
                        "heuristic-$line",
                    )
                }
            val typeRecord =
                graphRelationship(
                    connectedSource,
                    connectedTarget,
                    ReferenceEvidence.DIRECT,
                    6,
                    "type-record",
                    WorkspaceRelationshipKind.TYPE_REFERENCE,
                    ReferenceKind.TYPE_REF,
                )
            val keptImport =
                graphRelationship(
                    connectedSource,
                    connectedTarget,
                    ReferenceEvidence.DIRECT,
                    7,
                    "import example.ConnectedTarget",
                    WorkspaceRelationshipKind.IMPORT,
                    ReferenceKind.IMPORT,
                )
            val relationships = directCalls + heuristicCalls + typeRecord + keptImport
            val finding =
                Finding(
                    severity = FindingSeverity.WARNING,
                    message = "Strict priority declarations",
                    suggestion = "Keep every finding declaration in the bounded graph",
                    componentIds = prioritySymbols.map { symbol -> symbol.qualifiedName },
                )
            val report =
                graphReport(
                    symbols = prioritySymbols + connectedSource + connectedTarget + isolated,
                    relationships = relationships,
                    importantSymbols =
                        listOf(
                            graphImportant(connectedSource, relationships),
                            graphImportant(
                                isolated,
                                emptyList(),
                                ImportantSymbolReason.ANTI_PATTERN_INVOLVEMENT,
                            ),
                        ),
                ).copy(rootProjects = listOf(graphProject(":app", listOf(finding))))

            `when`("the last two slots can contain a complete pair or isolated ranking signals") {
                val graph = buildWorkspaceArchitectureGraph(report)
                val selectedIds = graph.nodes.map { node -> node.id }.toSet()

                then("the complete relationship pair is selected without evicting strict-priority declarations") {
                    graph.nodes shouldHaveSize ARCHITECTURE_GRAPH_NODE_LIMIT
                    selectedIds shouldContain connectedSource.identity.value
                    selectedIds shouldContain connectedTarget.identity.value
                    selectedIds shouldNotContain isolated.identity.value
                    prioritySymbols.all { symbol -> symbol.identity.value in selectedIds } shouldBe true
                    graph.omittedNodeCount shouldBe 1
                }

                then("symbol edges count exactly the selected endpoint occurrences including IMPORT") {
                    graph.edges shouldHaveSize 4
                    graph.edges.sumOf { edge -> edge.recordCount } shouldBe 7
                    graph.shownSymbolRelationshipRecordCount shouldBe 7
                    graph.totalRelationshipRecordCount shouldBe 7
                    graph.edges
                        .flatMap { edge -> edge.occurrences }
                        .map { occurrence -> occurrence.line }
                        .sorted() shouldContainExactly (1..7).toList()
                    graph.edges
                        .single { edge ->
                            edge.kind == WorkspaceRelationshipKind.CALL.name &&
                                edge.evidence == ReferenceEvidence.DIRECT.name
                        }.recordCount shouldBe 3
                    graph.edges
                        .single { edge -> edge.kind == WorkspaceRelationshipKind.IMPORT.name }
                        .recordCount shouldBe 1
                }

                then("reversing symbols, records, and ranking inputs leaves byte-stable JSON") {
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
                    buildWorkspaceArchitectureGraph(reversed).toJson() shouldBe graph.toJson()
                }
            }
        }

        given("forty-two strict-priority declarations competing with local and cross-build pairs") {
            val prioritySymbols =
                (0 until ARCHITECTURE_GRAPH_NODE_LIMIT).map { index ->
                    graphSymbol(
                        "root",
                        ":app",
                        "Priority${index.toString().padStart(2, '0')}",
                        SymbolDetailKind.CLASS,
                    )
                }
            val crossBuildSource = graphSymbol("root", ":app", "CrossBuildSource", SymbolDetailKind.CLASS)
            val crossBuildTarget = graphSymbol("included", ":api", "CrossBuildTarget", SymbolDetailKind.INTERFACE)
            val localSource = graphSymbol("root", ":app", "LocalBusySource", SymbolDetailKind.CLASS)
            val localTarget = graphSymbol("root", ":app", "LocalBusyTarget", SymbolDetailKind.INTERFACE)
            val crossBuildRelationship =
                graphRelationship(
                    crossBuildSource,
                    crossBuildTarget,
                    ReferenceEvidence.DIRECT,
                    1,
                    "crossBuildTarget()",
                )
            val localRelationships =
                (1..20).map { line ->
                    graphRelationship(
                        localSource,
                        localTarget,
                        ReferenceEvidence.DIRECT,
                        line,
                        "localTarget-$line",
                    )
                }
            val relationships = localRelationships + crossBuildRelationship
            val finding =
                Finding(
                    severity = FindingSeverity.WARNING,
                    message = "Strict priority declarations",
                    suggestion = "Reserve remaining context without displacing finding evidence",
                    componentIds = prioritySymbols.map { symbol -> symbol.qualifiedName },
                )
            val report =
                graphReport(
                    symbols =
                        prioritySymbols +
                            localSource +
                            localTarget +
                            crossBuildSource +
                            crossBuildTarget,
                    relationships = relationships,
                    importantSymbols =
                        listOf(
                            graphImportant(localSource, localRelationships),
                            graphImportant(crossBuildSource, listOf(crossBuildRelationship)),
                        ),
                ).copy(rootProjects = listOf(graphProject(":app", listOf(finding))))

            `when`("both bounded projections are saturated") {
                val graph = buildWorkspaceArchitectureGraph(report)
                val nodeIds = graph.nodes.map { node -> node.id }.toSet()
                val fileIds = graph.fileNodes.map { node -> node.id }.toSet()

                then("the symbol projection reserves a complete real cross-build pair") {
                    graph.nodes shouldHaveSize ARCHITECTURE_GRAPH_NODE_LIMIT
                    prioritySymbols
                        .take(ARCHITECTURE_GRAPH_NODE_LIMIT - 2)
                        .all { symbol -> symbol.identity.value in nodeIds } shouldBe true
                    nodeIds shouldNotContain prioritySymbols[ARCHITECTURE_GRAPH_NODE_LIMIT - 2].identity.value
                    nodeIds shouldNotContain prioritySymbols[ARCHITECTURE_GRAPH_NODE_LIMIT - 1].identity.value
                    nodeIds shouldContain crossBuildSource.identity.value
                    nodeIds shouldContain crossBuildTarget.identity.value
                    nodeIds shouldNotContain localSource.identity.value
                    nodeIds shouldNotContain localTarget.identity.value
                    graph.edges.single().crossBuild shouldBe true
                    graph.edges.single().source shouldBe crossBuildSource.identity.value
                    graph.edges.single().target shouldBe crossBuildTarget.identity.value
                }

                then("the file projection preserves the same cross-build relationship") {
                    graph.fileNodes shouldHaveSize ARCHITECTURE_GRAPH_FILE_NODE_LIMIT
                    fileIds shouldNotContain prioritySymbols[ARCHITECTURE_GRAPH_NODE_LIMIT - 2].graphTestFileId()
                    fileIds shouldNotContain prioritySymbols[ARCHITECTURE_GRAPH_NODE_LIMIT - 1].graphTestFileId()
                    fileIds shouldContain
                        graphFileId(
                            crossBuildSource.build,
                            crossBuildSource.project,
                            crossBuildSource.sourceSet,
                            crossBuildSource.projectRelativeFile,
                        )
                    fileIds shouldContain
                        graphFileId(
                            crossBuildTarget.build,
                            crossBuildTarget.project,
                            crossBuildTarget.sourceSet,
                            crossBuildTarget.projectRelativeFile,
                        )
                    graph.fileEdges.single().crossBuild shouldBe true
                    graph.fileEdges.single().recordCount shouldBe 1
                }

                then("reversing every ranked input leaves byte-stable bounded JSON") {
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
                    buildWorkspaceArchitectureGraph(reversed).toJson() shouldBe graph.toJson()
                }
            }
        }

        given("CopilotPluginTest.kt with a bounded subset of its outbound target files") {
            val prioritySymbols =
                (0 until 33).map { index ->
                    graphSymbol(
                        "root",
                        ":app",
                        "Priority${index.toString().padStart(2, '0')}",
                        SymbolDetailKind.CLASS,
                    )
                }
            val copilotPluginTest =
                graphSymbol(
                    build = "clkx-agents",
                    project = ":copilot",
                    name = "CopilotPluginTest",
                    kind = SymbolDetailKind.CLASS,
                    file = "src/test/kotlin/zone/clanker/agents/copilot/CopilotPluginTest.kt",
                    sourceSet = "test",
                    qualifiedName = "zone.clanker.agents.copilot.CopilotPluginTest",
                )
            val targets =
                (0 until 12).map { index ->
                    val fileIndex = if (index < 10) index else index - 10
                    graphSymbol(
                        build = "clkx-agents",
                        project = ":copilot",
                        name = "Target${index.toString().padStart(2, '0')}",
                        kind = SymbolDetailKind.CLASS,
                        file =
                            "src/main/kotlin/zone/clanker/agents/copilot/" +
                                "TargetFile${fileIndex.toString().padStart(2, '0')}.kt",
                        qualifiedName = "zone.clanker.agents.copilot.Target${index.toString().padStart(2, '0')}",
                    )
                }
            val targetRecordCounts = listOf(6, 6, 6, 6, 6, 6, 6, 6, 3, 4, 10, 10)
            val targetIndexes =
                targetRecordCounts.flatMapIndexed { targetIndex, count ->
                    List(count) { targetIndex }
                }
            val relationships =
                targetIndexes.mapIndexed { occurrenceIndex, targetIndex ->
                    graphRelationship(
                        source = copilotPluginTest,
                        target = targets[targetIndex],
                        evidence = ReferenceEvidence.DIRECT,
                        line = occurrenceIndex + 1,
                        context = "target-${targetIndex.toString().padStart(2, '0')}",
                    )
                }
            val finding =
                Finding(
                    severity = FindingSeverity.WARNING,
                    message = "Strict priority declarations",
                    suggestion = "Keep the bounded projection saturated",
                    componentIds = prioritySymbols.map { symbol -> symbol.qualifiedName },
                )
            val report =
                graphReport(
                    symbols = prioritySymbols + copilotPluginTest + targets,
                    relationships = relationships,
                    importantSymbols = listOf(graphImportant(copilotPluginTest, relationships)),
                ).copy(rootProjects = listOf(graphProject(":app", listOf(finding))))

            `when`("the exact test file is projected with only eight of ten target files") {
                val graph = buildWorkspaceArchitectureGraph(report)
                val fileNode =
                    graph.fileNodes.single { node ->
                        node.build == "clkx-agents" &&
                            node.project == ":copilot" &&
                            node.sourceSet == "test" &&
                            node.path == "src/test/kotlin/zone/clanker/agents/copilot/CopilotPluginTest.kt"
                    }

                then("the complete file metrics remain exact") {
                    fileNode.totalIncomingRecordCount shouldBe 0
                    fileNode.totalOutgoingRecordCount shouldBe 75
                    fileNode.totalInternalRecordCount shouldBe 0
                    fileNode.relationshipRecordCount shouldBe 75
                    fileNode.outgoingTargetDeclarationCount shouldBe 12
                    fileNode.outgoingTargetFileCount shouldBe 10
                }

                then("shown metrics describe only endpoints surviving the bounded file projection") {
                    fileNode.shownIncomingRecordCount shouldBe 0
                    fileNode.shownOutgoingRecordCount shouldBe 68
                    fileNode.shownInternalRecordCount shouldBe 0
                    fileNode.shownRelationshipRecordCount shouldBe 68
                    fileNode.shownOutgoingTargetDeclarationCount shouldBe 10
                    fileNode.shownOutgoingTargetFileCount shouldBe 8
                }

                then("the named file keeps its exact workspace scope and declaration") {
                    fileNode.selectedSymbolIds shouldContainExactly listOf(copilotPluginTest.identity.value)
                    fileNode.symbols.single().qualifiedName shouldBe
                        "zone.clanker.agents.copilot.CopilotPluginTest"
                    graph.fileNodes shouldHaveSize ARCHITECTURE_GRAPH_FILE_NODE_LIMIT
                }
            }
        }

        given("a complete cross-build pair already present in forty-two strict-priority declarations") {
            val rootPrioritySymbols =
                (0 until ARCHITECTURE_GRAPH_NODE_LIMIT - 1).map { index ->
                    graphSymbol(
                        "root",
                        ":app",
                        "RootPriority${index.toString().padStart(2, '0')}",
                        SymbolDetailKind.CLASS,
                    )
                }
            val includedTarget =
                graphSymbol("included", ":api", "IncludedPriority", SymbolDetailKind.INTERFACE)
            val relationship =
                graphRelationship(
                    rootPrioritySymbols.first(),
                    includedTarget,
                    ReferenceEvidence.DIRECT,
                    1,
                    "includedPriority()",
                )
            val rootFinding =
                Finding(
                    severity = FindingSeverity.WARNING,
                    message = "Root strict priorities",
                    suggestion = "Keep all strict declarations",
                    componentIds = rootPrioritySymbols.map { symbol -> symbol.qualifiedName },
                )
            val includedFinding =
                Finding(
                    severity = FindingSeverity.WARNING,
                    message = "Included strict priority",
                    suggestion = "Keep the cross-build endpoint",
                    componentIds = listOf(includedTarget.qualifiedName),
                )
            val report =
                graphReport(
                    symbols = rootPrioritySymbols + includedTarget,
                    relationships = listOf(relationship),
                    importantSymbols = listOf(graphImportant(rootPrioritySymbols.first(), listOf(relationship))),
                ).copy(
                    rootProjects = listOf(graphProject(":app", listOf(rootFinding))),
                    includedBuilds =
                        listOf(
                            IncludedBuildSummary(
                                name = "included",
                                relativePath = "../included",
                                projects = listOf(graphProject(":api", listOf(includedFinding))),
                            ),
                        ),
                )

            then("no strict symbol or file is sacrificed for a redundant reservation") {
                val graph = buildWorkspaceArchitectureGraph(report)
                val expectedSymbolIds = (rootPrioritySymbols + includedTarget).map { it.identity.value }.toSet()
                val expectedFileIds = (rootPrioritySymbols + includedTarget).map { it.graphTestFileId() }.toSet()

                graph.nodes shouldHaveSize ARCHITECTURE_GRAPH_NODE_LIMIT
                graph.nodes.map { node -> node.id }.toSet() shouldBe expectedSymbolIds
                graph.fileNodes shouldHaveSize ARCHITECTURE_GRAPH_FILE_NODE_LIMIT
                graph.fileNodes.map { node -> node.id }.toSet() shouldBe expectedFileIds
                graph.edges.single().crossBuild shouldBe true
                graph.fileEdges.single().crossBuild shouldBe true
                graph.omittedNodeCount shouldBe 0
                graph.omittedFileNodeCount shouldBe 0
            }
        }

        given("forty-one strict-priority declarations with one incoming endpoint") {
            val prioritySymbols =
                (0 until ARCHITECTURE_GRAPH_NODE_LIMIT - 1).map { index ->
                    graphSymbol(
                        "root",
                        ":app",
                        "Priority${index.toString().padStart(2, '0')}",
                        SymbolDetailKind.CLASS,
                    )
                }
            val incomingSource = graphSymbol("root", ":app", "IncomingSource", SymbolDetailKind.CLASS)
            val isolated = graphSymbol("root", ":app", "IsolatedImportant", SymbolDetailKind.INTERFACE)
            val relationship =
                graphRelationship(
                    incomingSource,
                    prioritySymbols.first(),
                    ReferenceEvidence.DIRECT,
                    9,
                    "priorityTarget()",
                )
            val finding =
                Finding(
                    severity = FindingSeverity.WARNING,
                    message = "Strict priority declarations",
                    suggestion = "Keep every finding declaration in the bounded graph",
                    componentIds = prioritySymbols.map { symbol -> symbol.qualifiedName },
                )
            val report =
                graphReport(
                    symbols = prioritySymbols + incomingSource + isolated,
                    relationships = listOf(relationship),
                    importantSymbols = listOf(graphImportant(isolated, emptyList())),
                ).copy(rootProjects = listOf(graphProject(":app", listOf(finding))))

            then("the final slot completes the arrow into a selected priority target") {
                val graph = buildWorkspaceArchitectureGraph(report)
                val selectedIds = graph.nodes.map { node -> node.id }.toSet()

                graph.nodes shouldHaveSize ARCHITECTURE_GRAPH_NODE_LIMIT
                prioritySymbols.all { symbol -> symbol.identity.value in selectedIds } shouldBe true
                selectedIds shouldContain incomingSource.identity.value
                selectedIds shouldNotContain isolated.identity.value
                graph.edges.single().recordCount shouldBe 1
                graph.omittedNodeCount shouldBe 1
            }
        }

        given("workspace relationships without an important symbol") {
            val source = graphSymbol("root", ":app", "Source", SymbolDetailKind.CLASS)
            val target = graphSymbol("root", ":app", "Target", SymbolDetailKind.CLASS)
            val orphan = graphSymbol("root", ":app", "Orphan", SymbolDetailKind.CLASS)
            val relationship = graphRelationship(source, target, ReferenceEvidence.DIRECT, 1, "Target")

            then("the build retains one symbol representative while connected files remain explorable") {
                val report = graphReport(listOf(orphan, target, source), listOf(relationship), emptyList())
                val graph = buildWorkspaceArchitectureGraph(report)
                graph.nodes.map { it.name } shouldContainExactly listOf("Source")
                graph.edges shouldBe emptyList()
                graph.clusters.map { it.id } shouldContainExactly listOf("root:::app")
                graph.omittedNodeCount shouldBe 1
                graph.fileNodes.map { it.name } shouldContainExactly listOf("Source.kt", "Target.kt")
                graph.fileNodes.flatMap { it.selectedSymbolIds }.toSet() shouldBe
                    setOf(source.identity.value, target.identity.value)
                graph.fileEdges.single().recordCount shouldBe 1
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

        given("an important symbol in an isolated file") {
            val source = graphSymbol("root", ":app", "Source", SymbolDetailKind.CLASS)
            val target = graphSymbol("root", ":app", "Target", SymbolDetailKind.CLASS)
            val isolated = graphSymbol("root", ":app", "Isolated", SymbolDetailKind.CLASS)
            val relationship = graphRelationship(source, target, ReferenceEvidence.DIRECT, 1, "Target")

            then("the isolated priority file remains available alongside connected files") {
                val report =
                    graphReport(
                        symbols = listOf(isolated, target, source),
                        relationships = listOf(relationship),
                        importantSymbols = listOf(graphImportant(isolated, listOf(relationship))),
                        sourceFiles =
                            listOf(isolated, target, source).map { symbol ->
                                graphSourceFile(
                                    symbol = symbol,
                                    content = "complete source for ${symbol.name}\n",
                                )
                            },
                    )
                val graph = buildWorkspaceArchitectureGraph(report)

                graph.fileNodes.map { it.name } shouldContainExactly listOf("Isolated.kt", "Source.kt", "Target.kt")
                graph.omittedFileNodeCount shouldBe 0
                graph.nodes.map { it.name } shouldContainExactly listOf("Isolated")
                graph.sourceFiles.map { it.path }.toSet() shouldBe
                    setOf(
                        isolated.projectRelativeFile,
                        source.projectRelativeFile,
                        target.projectRelativeFile,
                    )
                val isolatedSource = graph.sourceFiles.first { it.path == isolated.projectRelativeFile }
                isolatedSource.declarationLines shouldContainExactly listOf(isolated.declarationLine)
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
                    cycle.id.startsWith("cycle-") shouldBe true
                    graph.availableCycles.single().id shouldBe cycle.id
                    cycle.memberIds shouldContainExactly listOf(alphaId, betaId).sorted()
                    cycle.edgeIds shouldContainExactly
                        graph.fileEdges
                            .filter { it.source in cycle.memberIds && it.target in cycle.memberIds }
                            .map { it.id }
                            .sorted()
                    cycle.edgeIds shouldHaveSize 2
                    cycle.routes shouldHaveSize 1
                    cycle.routes.single().nodeIds shouldContainExactly listOf(alphaId, betaId, alphaId)
                    cycle.routes.single().edgeIds shouldContainExactly
                        listOf(
                            graph.fileEdges.first { it.source == alphaId && it.target == betaId }.id,
                            graph.fileEdges.first { it.source == betaId && it.target == alphaId }.id,
                        )
                    graph.toJson() shouldContain "\"routes\":[{\"nodeIds\""
                }
            }
        }

        given("a compound directed cycle with an internal chord") {
            val alpha = graphSymbol("root", ":app", "Alpha", SymbolDetailKind.CLASS)
            val beta = graphSymbol("root", ":app", "Beta", SymbolDetailKind.CLASS)
            val gamma = graphSymbol("root", ":app", "Gamma", SymbolDetailKind.CLASS)
            val relationships =
                listOf(
                    graphRelationship(alpha, beta, ReferenceEvidence.DIRECT, 1, "Beta"),
                    graphRelationship(beta, alpha, ReferenceEvidence.DIRECT, 2, "Alpha"),
                    graphRelationship(alpha, gamma, ReferenceEvidence.DIRECT, 3, "Gamma"),
                    graphRelationship(gamma, alpha, ReferenceEvidence.DIRECT, 4, "Alpha"),
                    graphRelationship(beta, gamma, ReferenceEvidence.DIRECT, 5, "Gamma"),
                )
            `when`("cycle routes are rendered") {
                val graph =
                    buildWorkspaceArchitectureGraph(
                        graphReport(
                            symbols = listOf(gamma, beta, alpha),
                            relationships = relationships,
                            importantSymbols = listOf(graphImportant(alpha, relationships)),
                        ),
                    )

                then("closed routes collectively cover every edge in the component") {
                    val cycle = graph.cycles.single()
                    val edgeById = graph.fileEdges.associateBy { edge -> edge.id }
                    cycle.memberIds shouldHaveSize 3
                    cycle.edgeIds shouldHaveSize 5
                    cycle.routes shouldHaveSize 3
                    cycle.routes.flatMap { route -> route.edgeIds }.toSet() shouldBe cycle.edgeIds.toSet()
                    cycle.routes.all { route -> route.nodeIds.first() == route.nodeIds.last() } shouldBe true
                    cycle.routes.all { route -> route.nodeIds.size == route.edgeIds.size + 1 } shouldBe true
                    cycle.routes.flatMap { route -> route.nodeIds }.toSet() shouldBe cycle.memberIds.toSet()
                    cycle.routes.all { route ->
                        route.edgeIds.indices.all { index ->
                            edgeById.getValue(route.edgeIds[index]).source == route.nodeIds[index] &&
                                edgeById.getValue(route.edgeIds[index]).target == route.nodeIds[index + 1]
                        }
                    } shouldBe true

                    val reversed =
                        graphReport(
                            symbols = listOf(alpha, beta, gamma),
                            relationships = relationships.reversed(),
                            importantSymbols = listOf(graphImportant(alpha, relationships)),
                        )
                    buildWorkspaceArchitectureGraph(reversed).toJson() shouldBe graph.toJson()
                }
            }
        }

        given("duplicate basenames in different workspace scopes") {
            val source =
                graphSymbol(
                    "root",
                    ":app",
                    "Source",
                    SymbolDetailKind.CLASS,
                    "src/main/kotlin/app/Shared.kt",
                )
            val target =
                graphSymbol(
                    "included",
                    ":lib",
                    "Target",
                    SymbolDetailKind.CLASS,
                    "src/main/kotlin/lib/Shared.kt",
                )
            val relationship = graphRelationship(source, target, ReferenceEvidence.DIRECT, 7, "Target()")

            then("the file payload keeps complete scoped identities despite identical names") {
                val graph =
                    buildWorkspaceArchitectureGraph(
                        graphReport(
                            symbols = listOf(source, target),
                            relationships = listOf(relationship),
                            importantSymbols = listOf(graphImportant(source, listOf(relationship))),
                            sourceFiles =
                                listOf(
                                    graphSourceFile(symbol = source, content = "app Shared source\n"),
                                    graphSourceFile(symbol = target, content = "library Shared source\n"),
                                ),
                        ),
                    )

                graph.fileNodes.map { it.name } shouldContainExactly listOf("Shared.kt", "Shared.kt")
                graph.fileNodes.map { it.id }.distinct() shouldHaveSize 2
                graph.fileNodes.map { Triple(it.build, it.project, it.path) } shouldContainExactly
                    listOf(
                        Triple("included", ":lib", "src/main/kotlin/lib/Shared.kt"),
                        Triple("root", ":app", "src/main/kotlin/app/Shared.kt"),
                    )
                graph.builds.map { it.name } shouldContainExactly listOf("root", "included")
                graph.sourceFiles.map { it.id } shouldContainExactly graph.fileNodes.map { it.id }
                graph.sourceFiles.map { it.path } shouldContainExactly
                    listOf(
                        "src/main/kotlin/lib/Shared.kt",
                        "src/main/kotlin/app/Shared.kt",
                    )
                graph.sourceFiles.map { it.content } shouldContainExactly
                    listOf("library Shared source\n", "app Shared source\n")
            }
        }

        given("selected-symbol files plus more connected files than the bounded cap") {
            val anchor = graphSymbol("root", ":app", "Anchor", SymbolDetailKind.CLASS)
            val findingOnly = graphSymbol("included", ":lib", "FindingOnly", SymbolDetailKind.CLASS)
            val neighbors =
                (0 until ARCHITECTURE_GRAPH_FILE_NODE_LIMIT + 2).map { index ->
                    graphSymbol(
                        "included",
                        ":lib",
                        "Neighbor${index.toString().padStart(2, '0')}",
                        SymbolDetailKind.CLASS,
                    )
                }
            val baseRelationships =
                neighbors.mapIndexed { index, neighbor ->
                    val evidenceLine = if (index == 1) 1 else index + 1
                    graphRelationship(anchor, neighbor, ReferenceEvidence.DIRECT, evidenceLine, neighbor.name)
                }
            val repeatedRecords =
                (0 until 3).map { index ->
                    graphRelationship(anchor, neighbors.first(), ReferenceEvidence.DIRECT, 100 + index, "repeat-$index")
                }
            val relationships = baseRelationships + repeatedRecords
            val report =
                graphReport(
                    symbols = listOf(anchor, findingOnly) + neighbors,
                    relationships = relationships,
                    importantSymbols = listOf(graphImportant(anchor, relationships)),
                    sourceFiles =
                        (listOf(anchor, findingOnly) + neighbors).map { symbol ->
                            graphSourceFile(
                                symbol = symbol,
                                content = "complete source for ${symbol.name}\n",
                            )
                        },
                ).copy(
                    includedBuilds =
                        listOf(
                            IncludedBuildSummary(
                                name = "included",
                                relativePath = "../included",
                                projects =
                                    listOf(
                                        graphProject(
                                            ":lib",
                                            listOf(
                                                Finding(
                                                    FindingSeverity.WARNING,
                                                    "Exact isolated finding",
                                                    "Review the file",
                                                    findingOnly.projectRelativeFile,
                                                ),
                                            ),
                                        ),
                                    ),
                            ),
                        ),
                )

            `when`("the seeded file projection is bounded") {
                val graph = buildWorkspaceArchitectureGraph(report)
                val anchorFile = graph.fileNodes.first { it.path == anchor.projectRelativeFile }

                then("omitted connected candidates and total versus shown records stay distinct") {
                    graph.fileNodes shouldHaveSize ARCHITECTURE_GRAPH_FILE_NODE_LIMIT
                    graph.omittedFileNodeCount shouldBe 4
                    val findingFile = graph.fileNodes.first { it.path == findingOnly.projectRelativeFile }
                    findingFile.fileFindingCount shouldBe 1
                    findingFile.relationshipRecordCount shouldBe 0
                    graph.totalRelationshipRecordCount shouldBe 47
                    graph.shownRelationshipRecordCount shouldBe 43
                    anchorFile.totalOutgoingRecordCount shouldBe 47
                    anchorFile.shownOutgoingRecordCount shouldBe 43
                    anchorFile.outgoingTargetDeclarationCount shouldBe 44
                    anchorFile.shownOutgoingTargetDeclarationCount shouldBe 40
                    anchorFile.outgoingTargetFileCount shouldBe 44
                    anchorFile.shownOutgoingTargetFileCount shouldBe 40
                }

                then("the shared source catalog retains every refillable file and occurrence") {
                    graph.sourceFiles shouldHaveSize report.sourceFiles.size
                    graph.sourceFiles.size shouldBe
                        graph.sourceFiles
                            .map { sourceFile -> sourceFile.id }
                            .distinct()
                            .size
                    val expectedSourceFileIds =
                        report.sourceFiles
                            .map { sourceFile ->
                                graphFileId(
                                    build = sourceFile.build,
                                    project = sourceFile.project,
                                    sourceSet = sourceFile.sourceSet,
                                    path = sourceFile.projectRelativeFile,
                                )
                            }.toSet()
                    graph.sourceFiles.map { sourceFile -> sourceFile.id }.toSet() shouldBe expectedSourceFileIds
                    graph.availableNodes shouldHaveSize report.workspaceIndex.symbols.size
                    graph.availableFileNodes shouldHaveSize report.sourceFiles.size
                    graph.availableEdges.sumOf { edge -> edge.recordCount } shouldBe relationships.size
                    graph.availableFileEdges.sumOf { edge -> edge.recordCount } shouldBe relationships.size
                    val anchorSource = graph.sourceFiles.first { it.path == anchor.projectRelativeFile }
                    anchorSource.relationshipLines shouldContainExactly
                        relationships
                            .map { relationship -> relationship.sourceEvidence.line }
                            .distinct()
                            .sorted()
                    (anchorSource.relationshipLines.size < relationships.size) shouldBe true
                    val omittedTargetPaths =
                        neighbors.map { it.projectRelativeFile }.toSet() - graph.fileNodes.map { it.path }.toSet()
                    omittedTargetPaths shouldHaveSize 4
                    val omittedTargetEvidenceLines =
                        relationships
                            .filter { relationship -> relationship.target.projectRelativeFile in omittedTargetPaths }
                            .map { relationship -> relationship.sourceEvidence.line }
                    omittedTargetEvidenceLines shouldHaveSize 4
                    omittedTargetEvidenceLines.all { line -> line in anchorSource.relationshipLines } shouldBe true
                }

                then("the JSON names every count as records or unique adjacency") {
                    val json = graph.toJson()
                    json shouldContain "\"totalOutgoingRecordCount\":47"
                    json shouldContain "\"shownOutgoingRecordCount\":43"
                    json shouldContain "\"outgoingTargetDeclarationCount\":44"
                    json shouldContain "\"shownOutgoingTargetFileCount\":40"
                    json shouldNotContain "\"outgoingCount\":47"
                }
            }
        }

        given("class-like declaration categories") {
            val interfaceSymbol = graphSymbol("root", ":app", "Endpoint", SymbolDetailKind.INTERFACE)
            val abstractSymbol =
                graphSymbol(
                    "root",
                    ":app",
                    "BaseService",
                    SymbolDetailKind.CLASS,
                    declarationSemantic = DeclarationSemantic.ABSTRACT_CLASS,
                )
            val sealedSymbol =
                graphSymbol(
                    "root",
                    ":app",
                    "UiState",
                    SymbolDetailKind.CLASS,
                    declarationSemantic = DeclarationSemantic.ABSTRACT_CLASS,
                )
            val concreteSymbol = graphSymbol("root", ":app", "ConcreteService", SymbolDetailKind.CLASS)
            val enumSymbol = graphSymbol("root", ":app", "Mode", SymbolDetailKind.ENUM)
            val objectSymbol = graphSymbol("root", ":app", "Registry", SymbolDetailKind.OBJECT)
            val symbols =
                listOf(
                    interfaceSymbol,
                    abstractSymbol,
                    sealedSymbol,
                    concreteSymbol,
                    enumSymbol,
                    objectSymbol,
                )
            val sourceBySymbol =
                mapOf(
                    interfaceSymbol to "sealed interface Endpoint\n",
                    abstractSymbol to "public abstract class BaseService {}\n",
                    sealedSymbol to "sealed class UiState\n",
                    concreteSymbol to "class ConcreteService\n",
                    enumSymbol to "enum class Mode { ON }\n",
                    objectSymbol to "object Registry\n",
                )
            val report =
                graphReport(
                    symbols = symbols,
                    relationships = emptyList(),
                    importantSymbols = symbols.map { symbol -> graphImportant(symbol, emptyList()) },
                    sourceFiles =
                        symbols.map { symbol ->
                            graphSourceFile(symbol = symbol, content = sourceBySymbol.getValue(symbol))
                        },
                )

            `when`("the symbol projection is built") {
                val graph = buildWorkspaceArchitectureGraph(report)

                then("it gives each declaration a descriptive category without treating it as quality") {
                    graph.nodes.associate { node -> node.name to node.declarationCategory } shouldBe
                        mapOf(
                            "BaseService" to "ABSTRACT",
                            "ConcreteService" to "CONCRETE",
                            "Endpoint" to "INTERFACE",
                            "Mode" to "ENUM",
                            "Registry" to "CONCRETE",
                            "UiState" to "ABSTRACT",
                        )
                }

                then("the declaration categories survive the self-contained JSON transport") {
                    val json = graph.toJson()
                    json shouldContain "\"declarationCategory\":\"INTERFACE\""
                    json shouldContain "\"declarationCategory\":\"ABSTRACT\""
                    json shouldContain "\"declarationCategory\":\"CONCRETE\""
                    json shouldContain "\"declarationCategory\":\"ENUM\""
                    json shouldContain "\"declarationSemantic\":\"INTERFACE\""
                    json shouldContain "\"declarationSemantic\":\"ABSTRACT_CLASS\""
                    json shouldContain "\"declarationSemantic\":\"CONCRETE_CLASS\""
                    json shouldContain "\"declarationSemantic\":\"SINGLETON_OBJECT\""
                    json shouldContain "\"declarationSemantic\":\"ENUM\""
                    json shouldContain "\"declarationSemanticLabel\":\"Kotlin object\""
                    json shouldContain "\"declarationSemanticDetail\":"
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

        given("a selected graph file without embedded source") {
            val source = graphSymbol("root", ":app", "Source", SymbolDetailKind.CLASS)
            val target = graphSymbol("root", ":app", "Target", SymbolDetailKind.CLASS)
            val relationship = graphRelationship(source, target, ReferenceEvidence.DIRECT, 1, "Target")
            val report =
                graphReport(
                    symbols = listOf(source, target),
                    relationships = listOf(relationship),
                    importantSymbols = listOf(graphImportant(source, listOf(relationship))),
                    sourceFiles = emptyList(),
                )

            `when`("the Atlas payload is built") {
                then("the renderer rejects an atlas that the client could not open") {
                    shouldThrow<IllegalArgumentException> { buildWorkspaceArchitectureGraph(report) }
                        .message shouldContain "architecture source payload is missing selected files"
                }
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
    declarationSemantic: DeclarationSemantic = DeclarationSemantic.from(kind),
    sourceSet: String = "main",
    qualifiedName: String = "example.$name",
): WorkspaceSymbol =
    WorkspaceSymbol(
        build = build,
        project = project,
        sourceSet = sourceSet,
        name = name,
        qualifiedName = qualifiedName,
        kind = kind,
        projectRelativeFile = file,
        declarationLine = line,
        declarationSemantic = declarationSemantic,
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
    reason: ImportantSymbolReason = ImportantSymbolReason.ENTRY_POINT,
): ImportantSymbol =
    ImportantSymbol(
        symbol = symbol,
        reasons = listOf(reason),
        score = reason.score,
        usage =
            WorkspaceSymbolUsage(
                symbol,
                relationships.filter { it.targetIdentity == symbol.identity },
                relationships.filter { it.sourceIdentity == symbol.identity },
            ),
    )

private fun graphSourceFile(
    symbol: WorkspaceSymbol,
    content: String,
): WorkspaceSourceFile =
    WorkspaceSourceFile(
        build = symbol.build,
        project = symbol.project,
        sourceSet = symbol.sourceSet,
        projectRelativeFile = symbol.projectRelativeFile,
        content = content,
    )

private fun graphFileId(
    build: String,
    project: String,
    sourceSet: String,
    path: String,
): String = "file::$build::$project::$sourceSet::$path"

private fun WorkspaceSymbol.graphTestFileId(): String =
    graphFileId(build, project, sourceSet, projectRelativeFile)

private fun graphReport(
    symbols: List<WorkspaceSymbol>,
    relationships: List<WorkspaceRelationship>,
    importantSymbols: List<ImportantSymbol>,
    sourceFiles: List<WorkspaceSourceFile> =
        symbols
            .distinctBy { symbol ->
                graphFileId(
                    build = symbol.build,
                    project = symbol.project,
                    sourceSet = symbol.sourceSet,
                    path = symbol.projectRelativeFile,
                )
            }.map { symbol -> graphSourceFile(symbol, "complete source for ${symbol.name}\n") },
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
        sourceFiles =
            sourceFiles.sortedWith(
                compareBy<WorkspaceSourceFile> { sourceFile -> sourceFile.build }
                    .thenBy { sourceFile -> sourceFile.project }
                    .thenBy { sourceFile -> sourceFile.sourceSet }
                    .thenBy { sourceFile -> sourceFile.projectRelativeFile },
            ),
    )

private fun graphProject(
    path: String,
    findings: List<Finding>,
    architecture: ArchitectureSummary = ArchitectureSummary(),
    sourceSets: List<String> = emptyList(),
): ProjectSummary =
    ProjectSummary(
        projectPath = ProjectPath(path),
        symbols = emptyList(),
        dependencies = emptyList(),
        buildFile = "build.gradle.kts",
        sourceDirs = emptyList(),
        subprojects = emptyList(),
        sourceSets =
            sourceSets.map { sourceSet ->
                SourceSetSummary(SourceSetName(sourceSet), emptyList(), emptyList())
            },
        analysis = AnalysisSummary(findings, emptyList(), emptyList(), architecture),
    )

private fun graphArchitectureComponent(symbol: WorkspaceSymbol): ArchitectureComponent =
    ArchitectureComponent(
        id = symbol.qualifiedName,
        name = symbol.name,
        packageName = symbol.qualifiedName.substringBeforeLast('.', missingDelimiterValue = ""),
        packageGroup = symbol.qualifiedName.substringBefore('.', missingDelimiterValue = symbol.qualifiedName),
        role = "domain",
        layer = ArchitectureLayer.DOMAIN,
        filePath = symbol.projectRelativeFile,
        line = symbol.declarationLine,
        isTest = false,
    )
