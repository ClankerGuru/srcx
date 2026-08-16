package zone.clanker.gradle.docx.site

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.gradle.testfixtures.ProjectBuilder
import zone.clanker.gradle.docx.DependencyInjectionFrameworkSelection
import zone.clanker.gradle.docx.DocxFeature
import zone.clanker.gradle.docx.DocxFeaturePlan
import zone.clanker.gradle.docx.DocxScopePlan
import zone.clanker.gradle.docx.DocxSettingsExtension
import zone.clanker.gradle.docx.GeneratedSourcePolicy
import zone.clanker.gradle.docx.GraphDepthMode
import zone.clanker.gradle.docx.GraphDepthPlan
import zone.clanker.gradle.docx.MissingCapabilityPolicy
import zone.clanker.gradle.docx.NameSelectionPlan
import zone.clanker.gradle.docx.RelationshipKindSelection
import zone.clanker.gradle.docx.UnknownSelectionPolicy
import zone.clanker.gradle.docx.allNames
import zone.clanker.gradle.docx.crossProjectCycleWorkspaceFixture
import zone.clanker.gradle.docx.docxTestPlan
import zone.clanker.gradle.docx.workspaceFixture
import zone.clanker.report.model.ArchitectureComponentSnapshot
import zone.clanker.report.model.ArchitectureCycleSnapshot
import zone.clanker.report.model.ArchitectureDependencySnapshot
import zone.clanker.report.model.ArchitectureEntryPointKind
import zone.clanker.report.model.ArchitectureEntryPointSnapshot
import zone.clanker.report.model.ArchitectureLayer
import zone.clanker.report.model.BuildKind
import zone.clanker.report.model.BuildSnapshot
import zone.clanker.report.model.CycleSnapshot
import zone.clanker.report.model.DeclarationSemantic
import zone.clanker.report.model.DependencyInjectionSnapshot
import zone.clanker.report.model.FindingSeverity
import zone.clanker.report.model.FindingSnapshot
import zone.clanker.report.model.HubDependentSnapshot
import zone.clanker.report.model.HubSnapshot
import zone.clanker.report.model.NamedCycleSnapshot
import zone.clanker.report.model.ProjectAnalysisSnapshot
import zone.clanker.report.model.ProjectGraphShard
import zone.clanker.report.model.ProjectSnapshot
import zone.clanker.report.model.ReferenceSnapshot
import zone.clanker.report.model.RelationshipSnapshot
import zone.clanker.report.model.SourceFileSnapshot
import zone.clanker.report.model.SourceLanguage
import zone.clanker.report.model.SourceSetSnapshot
import zone.clanker.report.model.SymbolKind
import zone.clanker.report.model.SymbolSnapshot
import zone.clanker.report.model.WorkspaceIdentity
import zone.clanker.report.model.WorkspaceSiteJson
import zone.clanker.report.model.WorkspaceSnapshot
import zone.clanker.report.model.WorkspaceSourceSetSummary

class DocxSiteProjectionTest :
    BehaviorSpec({
        given("the default FULL plan and a snapshot without dependency-injection facts") {
            val plan =
                ProjectBuilder
                    .builder()
                    .build()
                    .objects
                    .newInstance(DocxSettingsExtension::class.java)
                    .analysisPlan()

            `when`("missing capabilities use the default policy") {
                val projection = DocxSiteProjection.apply(workspaceFixture(), plan)

                then("the unavailable view is omitted with a truthful warning") {
                    projection.projectGraphs.map { it.projectId } shouldContainExactly listOf("project:a")
                    projection.sourceSets shouldContainExactly
                        listOf(WorkspaceSourceSetSummary("source-set:a", "project:a", "main", 2))
                    projection.warnings.single() shouldContain "dependency-injection view was omitted"
                }

                then("source bodies are separated from graph metadata") {
                    projection.projectGraphs
                        .single()
                        .files
                        .all { file -> file.content == null } shouldBe true
                    projection.sourceContents.map { source -> source.fileId } shouldContainExactly
                        listOf("file:a", "file:b")
                    projection.sourceContents.map { source -> source.content } shouldContainExactly
                        workspaceFixture().files.mapNotNull(SourceFileSnapshot::content)
                }
            }

            `when`("the missing-capability policy is strict") {
                then("projection fails instead of fabricating dependency-injection data") {
                    shouldThrow<IllegalArgumentException> {
                        DocxSiteProjection.apply(
                            workspaceFixture(),
                            plan.copy(missingCapabilities = MissingCapabilityPolicy.FAIL),
                        )
                    }.message shouldContain "unavailable"
                }
            }

            `when`("dependency-injection facts are available before the viewer supports them") {
                val snapshot =
                    workspaceFixture().copy(
                        dependencyInjection = DependencyInjectionSnapshot(bindings = emptyList(), edges = emptyList()),
                    )
                val projection = DocxSiteProjection.apply(snapshot, plan)

                then("the transport omission remains explicit") {
                    projection.warnings.single() shouldContain "facts are present but omitted"
                    projection.projectGraphs.single().analysis shouldBe null
                }
            }
        }

        given("an included file whose snapshot has no source body") {
            val snapshot =
                workspaceFixture().let { fixture ->
                    fixture.copy(
                        files =
                            fixture.files.map { file ->
                                if (file.id == "file:b") file.copy(content = null) else file
                            },
                    )
                }

            `when`("the static transport is projected") {
                val projection = DocxSiteProjection.apply(snapshot, docxTestPlan())

                then("metadata remains available without fabricating a source reference") {
                    projection.projectGraphs
                        .single()
                        .files
                        .map { file -> file.id } shouldContainExactly
                        listOf("file:a", "file:b")
                    projection.projectGraphs
                        .single()
                        .files
                        .all { file -> file.content == null } shouldBe true
                    projection.sourceContents.map { source -> source.fileId } shouldContainExactly listOf("file:a")
                }
            }
        }

        given("first-checkpoint graph controls") {
            `when`("an enabled graph requests a hop limit") {
                val plan =
                    docxTestPlan().withFeature(DocxFeature.SYMBOLS) { feature ->
                        feature.copy(depth = GraphDepthPlan(GraphDepthMode.HOPS, 2))
                    }

                then("the unsupported limit is rejected explicitly") {
                    shouldThrow<IllegalArgumentException> {
                        DocxSiteProjection.apply(workspaceFixture(), plan)
                    }.message shouldContain "hop limits"
                }
            }

            `when`("dependency-injection graph controls are changed from their defaults") {
                val defaults = defaultPlan()

                then("every unsupported option is rejected explicitly") {
                    listOf(
                        "includeDisconnected" to
                            { feature: DocxFeaturePlan -> feature.copy(includeDisconnected = false) },
                        "relationship-kind" to
                            { feature: DocxFeaturePlan ->
                                feature.copy(relationshipKinds = listOf(RelationshipKindSelection.CALL))
                            },
                        "framework" to
                            { feature: DocxFeaturePlan ->
                                feature.copy(
                                    dependencyInjectionFrameworks =
                                        listOf(DependencyInjectionFrameworkSelection.HILT),
                                )
                            },
                        "generated-source" to
                            { feature: DocxFeaturePlan ->
                                feature.copy(generatedSources = GeneratedSourcePolicy.ONLY)
                            },
                    ).forEach { (expected, mutation) ->
                        val plan = defaults.withFeature(DocxFeature.DEPENDENCY_INJECTION, mutation)
                        shouldThrow<IllegalArgumentException> {
                            DocxSiteProjection.apply(workspaceFixture(), plan)
                        }.message shouldContain expected
                    }
                }
            }
        }

        given("an unknown source-set selection") {
            val selection = NameSelectionPlan(all = false, included = listOf("desktopMain"), excluded = emptyList())

            `when`("unknown selections are warnings") {
                val projection =
                    DocxSiteProjection.apply(
                        workspaceFixture(),
                        docxTestPlan(scope = scope(sourceSets = selection, policy = UnknownSelectionPolicy.WARN)),
                    )

                then("the empty project is omitted and both facts are reported") {
                    projection.projects shouldBe emptyList()
                    projection.projectGraphs shouldBe emptyList()
                    projection.sourceSets shouldBe emptyList()
                    projection.warnings.size shouldBe 2
                    projection.warnings.joinToString() shouldContain "Unknown DOCX source-set selection"
                    projection.warnings.joinToString() shouldContain "no source sets matched"
                }
            }

            `when`("unknown selections are failures") {
                then("projection rejects the scope") {
                    shouldThrow<IllegalArgumentException> {
                        DocxSiteProjection.apply(
                            workspaceFixture(),
                            docxTestPlan(scope = scope(sourceSets = selection, policy = UnknownSelectionPolicy.FAIL)),
                        )
                    }.message shouldContain "desktopMain"
                }
            }
        }

        given("a relationship cycle spanning two projects") {
            val snapshot = crossProjectCycleWorkspaceFixture()

            `when`("either participating project is projected from the shared catalog") {
                val graphs = DocxSiteProjection.apply(snapshot, docxTestPlan()).projectGraphs

                then("each shard retains the whole cycle and its external evidence closure") {
                    graphs.forEach { graph ->
                        graph.cycles.map { it.id } shouldContainExactly listOf("cycle:cross-project")
                        graph.relationships.map { it.id }.containsAll(
                            listOf("relationship:cycle-out", "relationship:cycle-back"),
                        ) shouldBe true
                        graph.references.map { it.id }.containsAll(
                            listOf("reference:cycle-out", "reference:cycle-back"),
                        ) shouldBe true
                        graph.symbols.map { it.id }.containsAll(listOf("symbol:a", "symbol:c")) shouldBe true
                    }
                }

                then("the serialized shards exactly match the canonical pre-index projection") {
                    graphs.map(WorkspaceSiteJson::encodeProject) shouldContainExactly
                        canonicalCrossProjectShards(snapshot).map(WorkspaceSiteJson::encodeProject)
                }
            }
        }

        given("a graph with no selected relationship kinds or disconnected symbols") {
            val plan =
                docxTestPlan()
                    .withFeature(DocxFeature.RELATIONSHIPS) { feature ->
                        feature.copy(relationshipKinds = listOf(RelationshipKindSelection.CALL))
                    }.withFeature(DocxFeature.SYMBOLS) { feature ->
                        feature.copy(includeDisconnected = false)
                    }.withFeature(DocxFeature.FILES) { feature -> feature.copy(enabled = false) }
                    .withFeature(DocxFeature.SOURCES) { feature -> feature.copy(enabled = false) }

            `when`("the project shard is built") {
                val graph = DocxSiteProjection.apply(workspaceFixture(), plan).projectGraphs.single()

                then("unselected evidence and disconnected declarations are absent") {
                    graph.relationships shouldBe emptyList()
                    graph.references shouldBe emptyList()
                    graph.symbols shouldBe emptyList()
                    graph.files shouldBe emptyList()
                }
            }
        }

        given("a project snapshot with complete analyzer facts") {
            val snapshot = analysisWorkspaceFixture()
            val sourceAnalysis = snapshot.projectAnalyses.single()

            `when`("all analysis-backed features are enabled") {
                val graph = DocxSiteProjection.apply(snapshot, docxTestPlan()).projectGraphs.single()

                then("the shard preserves every project-analysis fact") {
                    graph.analysis shouldBe sourceAnalysis
                    graph.findings shouldBe sourceAnalysis.findings
                }
            }

            `when`("only findings are enabled") {
                val graph =
                    DocxSiteProjection
                        .apply(snapshot, docxTestPlan().withOnlyAnalysisFeatures(DocxFeature.FINDINGS))
                        .projectGraphs
                        .single()

                then("findings retain only the components required by their resolved evidence") {
                    graph.analysis?.findings shouldBe sourceAnalysis.findings
                    graph.analysis?.components shouldBe sourceAnalysis.components
                    graph.analysis?.dependencies shouldBe emptyList()
                    graph.analysis?.entryPoints shouldBe emptyList()
                    graph.analysis?.hubs shouldBe emptyList()
                    graph.analysis?.cycles shouldBe emptyList()
                    graph.analysis?.legacyNameCycles shouldBe emptyList()
                }
            }

            `when`("only symbols are enabled") {
                val graph =
                    DocxSiteProjection
                        .apply(snapshot, docxTestPlan().withOnlyAnalysisFeatures(DocxFeature.SYMBOLS))
                        .projectGraphs
                        .single()

                then("components and entry points survive without relationship-owned facts") {
                    graph.analysis?.components shouldBe sourceAnalysis.components
                    graph.analysis?.entryPoints shouldBe sourceAnalysis.entryPoints
                    graph.analysis?.findings shouldBe emptyList()
                    graph.analysis?.dependencies shouldBe emptyList()
                    graph.analysis?.hubs shouldBe emptyList()
                    graph.analysis?.cycles shouldBe emptyList()
                    graph.analysis?.legacyNameCycles shouldBe emptyList()
                }
            }

            `when`("only relationships are enabled") {
                val graph =
                    DocxSiteProjection
                        .apply(snapshot, docxTestPlan().withOnlyAnalysisFeatures(DocxFeature.RELATIONSHIPS))
                        .projectGraphs
                        .single()

                then("dependencies and hubs retain their component and file closure") {
                    graph.analysis?.components shouldBe sourceAnalysis.components
                    graph.analysis?.dependencies shouldBe sourceAnalysis.dependencies
                    graph.analysis?.hubs shouldBe sourceAnalysis.hubs
                    graph.analysis?.findings shouldBe emptyList()
                    graph.analysis?.entryPoints shouldBe emptyList()
                    graph.analysis?.cycles shouldBe emptyList()
                    graph.analysis?.legacyNameCycles shouldBe emptyList()
                }
            }

            `when`("only cycles are enabled") {
                val graph =
                    DocxSiteProjection
                        .apply(snapshot, docxTestPlan().withOnlyAnalysisFeatures(DocxFeature.CYCLES))
                        .projectGraphs
                        .single()

                then("cycles retain the components and directed dependencies needed to close their routes") {
                    graph.analysis?.components shouldBe sourceAnalysis.components
                    graph.analysis?.dependencies shouldBe sourceAnalysis.dependencies
                    graph.analysis?.cycles shouldBe sourceAnalysis.cycles
                    graph.analysis?.legacyNameCycles shouldBe sourceAnalysis.legacyNameCycles
                    graph.analysis?.findings shouldBe emptyList()
                    graph.analysis?.entryPoints shouldBe emptyList()
                    graph.analysis?.hubs shouldBe emptyList()
                }
            }

            `when`("every analysis-backed feature is disabled") {
                val graph =
                    DocxSiteProjection
                        .apply(snapshot, docxTestPlan().withOnlyAnalysisFeatures())
                        .projectGraphs
                        .single()

                then("the shard does not leak dormant analyzer facts") {
                    graph.findings shouldBe emptyList()
                    graph.analysis shouldBe null
                }
            }
        }

        given("many independently owned projects") {
            val fixture = manyProjectTraversalFixture(projectCount = 64)

            `when`("all project shards are projected") {
                val projection = DocxSiteProjection.apply(fixture.snapshot, docxTestPlan())

                then("every project retains its one owned source closure") {
                    projection.projectGraphs.size shouldBe 64
                    projection.projectGraphs.forEach { graph ->
                        graph.ownedSourceSetIds.size shouldBe 1
                        graph.ownedFileIds.size shouldBe 1
                        graph.ownedSymbolIds.size shouldBe 1
                    }
                }

                then("workspace fact collections are traversed a constant number of times") {
                    fixture.traversalLists.forEach { facts ->
                        (facts.traversals in 1..4) shouldBe true
                    }
                }
            }
        }
    })

private fun canonicalCrossProjectShards(snapshot: WorkspaceSnapshot): List<ProjectGraphShard> {
    val sourceSetA = snapshot.sourceSets.single { sourceSet -> sourceSet.id == "source-set:a" }
    val sourceSetB = snapshot.sourceSets.single { sourceSet -> sourceSet.id == "source-set:b" }
    val fileA = snapshot.files.single { file -> file.id == "file:a" }
    val fileB = snapshot.files.single { file -> file.id == "file:b" }
    val fileC = snapshot.files.single { file -> file.id == "file:c" }
    val symbolA = snapshot.symbols.single { symbol -> symbol.id == "symbol:a" }
    val symbolB = snapshot.symbols.single { symbol -> symbol.id == "symbol:b" }
    val symbolC = snapshot.symbols.single { symbol -> symbol.id == "symbol:c" }
    val cycleReferenceIds = setOf("reference:cycle-back", "reference:cycle-out")
    val cycleRelationshipIds = setOf("relationship:cycle-back", "relationship:cycle-out")
    return listOf(
        ProjectGraphShard(
            projectId = "project:a",
            sourceSets = listOf(sourceSetA, sourceSetB),
            files = listOf(fileA.copy(content = null), fileB.copy(content = null), fileC.copy(content = null)),
            symbols = listOf(symbolA, symbolB, symbolC),
            ownedSourceSetIds = listOf(sourceSetA.id),
            ownedFileIds = listOf(fileA.id, fileB.id),
            ownedSymbolIds = listOf(symbolA.id, symbolB.id),
            references = snapshot.references,
            relationships = snapshot.relationships,
            cycles = snapshot.relationshipCycles,
        ),
        ProjectGraphShard(
            projectId = "project:b",
            sourceSets = listOf(sourceSetA, sourceSetB),
            files = listOf(fileA.copy(content = null), fileC.copy(content = null)),
            symbols = listOf(symbolA, symbolC),
            ownedSourceSetIds = listOf(sourceSetB.id),
            ownedFileIds = listOf(fileC.id),
            ownedSymbolIds = listOf(symbolC.id),
            references = snapshot.references.filter { reference -> reference.id in cycleReferenceIds },
            relationships = snapshot.relationships.filter { relationship -> relationship.id in cycleRelationshipIds },
            cycles = snapshot.relationshipCycles,
        ),
    )
}

private data class ManyProjectTraversalFixture(
    val snapshot: WorkspaceSnapshot,
    val traversalLists: List<TraversalCountingList<*>>,
)

private fun manyProjectTraversalFixture(projectCount: Int): ManyProjectTraversalFixture {
    val indexes = (0 until projectCount).map { index -> index.toString().padStart(3, '0') }
    val projects = indexes.map(::manyProject)
    val sourceSets = TraversalCountingList(indexes.map(::manyProjectSourceSet))
    val files = TraversalCountingList(indexes.map(::manyProjectFile))
    val symbols = TraversalCountingList(indexes.map(::manyProjectSymbol))
    val references = TraversalCountingList(emptyList<ReferenceSnapshot>())
    val relationships = TraversalCountingList(emptyList<RelationshipSnapshot>())
    val cycles = TraversalCountingList(emptyList<CycleSnapshot>())
    val analyses = TraversalCountingList(emptyList<ProjectAnalysisSnapshot>())
    val snapshot =
        WorkspaceSnapshot(
            workspace = WorkspaceIdentity("workspace:many-projects", "many-projects"),
            builds = listOf(BuildSnapshot("build:root", "many-projects", BuildKind.ROOT, ".")),
            projects = projects,
            sourceSets = sourceSets,
            files = files,
            symbols = symbols,
            references = references,
            relationships = relationships,
            relationshipCycles = cycles,
            projectAnalyses = analyses,
        )
    val traversalLists = listOf(sourceSets, files, symbols, references, relationships, cycles, analyses)
    traversalLists.forEach(TraversalCountingList<*>::reset)
    return ManyProjectTraversalFixture(snapshot, traversalLists)
}

private fun manyProject(index: String): ProjectSnapshot =
    ProjectSnapshot("project:$index", "build:root", ":project-$index", "project-$index/build.gradle.kts")

private fun manyProjectSourceSet(index: String): SourceSetSnapshot =
    SourceSetSnapshot("source-set:$index", "project:$index", "main")

private fun manyProjectFile(index: String): SourceFileSnapshot =
    SourceFileSnapshot(
        id = "file:$index",
        sourceSetId = "source-set:$index",
        projectRelativePath = "src/main/kotlin/Type$index.kt",
        language = SourceLanguage.KOTLIN,
        content = "package fixture\nclass Type$index\n",
    )

private fun manyProjectSymbol(index: String): SymbolSnapshot =
    SymbolSnapshot(
        id = "symbol:$index",
        fileId = "file:$index",
        name = "Type$index",
        qualifiedName = "fixture.Type$index",
        packageName = "fixture",
        kind = SymbolKind.CLASS,
        declarationSemantic = DeclarationSemantic.CONCRETE_CLASS,
        declarationLine = 2,
    )

private class TraversalCountingList<Value>(
    private val values: List<Value>,
) : List<Value> by values {
    var traversals: Int = 0
        private set

    override fun iterator(): Iterator<Value> {
        traversals += 1
        return values.iterator()
    }

    fun reset() {
        traversals = 0
    }
}

@Suppress("LongMethod")
private fun analysisWorkspaceFixture(): WorkspaceSnapshot {
    val snapshot = workspaceFixture()
    val components =
        listOf(
            ArchitectureComponentSnapshot(
                id = "analysis-component:a",
                componentId = "fixture.Consumer",
                name = "Consumer",
                packageName = "fixture",
                packageGroup = "fixture",
                role = "consumer",
                layer = ArchitectureLayer.DOMAIN,
                filePath = "src/main/kotlin/Consumer.kt",
                line = 1,
                isTest = false,
                sourceFileId = "file:a",
                symbolId = "symbol:a",
            ),
            ArchitectureComponentSnapshot(
                id = "analysis-component:b",
                componentId = "fixture.Target",
                name = "Target",
                packageName = "fixture",
                packageGroup = "fixture",
                role = "target",
                layer = ArchitectureLayer.DOMAIN,
                filePath = "src/main/kotlin/Target.kt",
                line = 1,
                isTest = false,
                sourceFileId = "file:b",
                symbolId = "symbol:b",
            ),
        )
    val dependencies =
        listOf(
            ArchitectureDependencySnapshot(
                id = "analysis-dependency:a-b",
                sourceComponentId = "analysis-component:a",
                targetComponentId = "analysis-component:b",
            ),
            ArchitectureDependencySnapshot(
                id = "analysis-dependency:b-a",
                sourceComponentId = "analysis-component:b",
                targetComponentId = "analysis-component:a",
            ),
        )
    val analysis =
        ProjectAnalysisSnapshot(
            id = "analysis:a",
            projectId = "project:a",
            findings =
                listOf(
                    FindingSnapshot(
                        id = "analysis-finding:a",
                        severity = FindingSeverity.WARNING,
                        message = "Consumer and Target form a cycle",
                        suggestion = "Introduce an acyclic boundary.",
                        fileId = "file:a",
                        filePath = "src/main/kotlin/Consumer.kt",
                        line = 1,
                        componentIds = listOf("fixture.Consumer", "fixture.Target"),
                        resolvedComponentIds = listOf("analysis-component:a", "analysis-component:b"),
                        symbolIds = listOf("symbol:a", "symbol:b"),
                        componentCycle = listOf("fixture.Consumer", "fixture.Target", "fixture.Consumer"),
                    ),
                ),
            hubs =
                listOf(
                    HubSnapshot(
                        id = "analysis-hub:a",
                        name = "Target",
                        dependentCount = 1,
                        role = "target",
                        filePath = "src/main/kotlin/Target.kt",
                        line = 1,
                        sourceFileId = "file:b",
                        dependents =
                            listOf(
                                HubDependentSnapshot(
                                    name = "Consumer",
                                    filePath = "src/main/kotlin/Consumer.kt",
                                    line = 1,
                                ),
                            ),
                    ),
                ),
            components = components,
            dependencies = dependencies,
            entryPoints =
                listOf(
                    ArchitectureEntryPointSnapshot(
                        id = "analysis-entry-point:a",
                        componentId = "analysis-component:a",
                        reason = "Application start",
                        kind = ArchitectureEntryPointKind.EXPLICIT,
                    ),
                ),
            cycles =
                listOf(
                    ArchitectureCycleSnapshot(
                        id = "analysis-cycle:a",
                        componentIds =
                            listOf("analysis-component:a", "analysis-component:b", "analysis-component:a"),
                    ),
                ),
            legacyNameCycles =
                listOf(
                    NamedCycleSnapshot(
                        id = "analysis-name-cycle:a",
                        names = listOf("Consumer", "Target", "Consumer"),
                    ),
                ),
        )
    return snapshot.copy(projectAnalyses = listOf(analysis))
}

private fun defaultPlan() =
    ProjectBuilder
        .builder()
        .build()
        .objects
        .newInstance(DocxSettingsExtension::class.java)
        .analysisPlan()

private fun zone.clanker.gradle.docx.DocxAnalysisPlan.withFeature(
    selected: DocxFeature,
    transform: (DocxFeaturePlan) -> DocxFeaturePlan,
) = copy(features = features.map { feature -> if (feature.feature == selected) transform(feature) else feature })

private fun zone.clanker.gradle.docx.DocxAnalysisPlan.withOnlyAnalysisFeatures(
    vararg enabled: DocxFeature,
): zone.clanker.gradle.docx.DocxAnalysisPlan {
    val analysisFeatures =
        setOf(
            DocxFeature.FINDINGS,
            DocxFeature.SYMBOLS,
            DocxFeature.RELATIONSHIPS,
            DocxFeature.CYCLES,
        )
    val enabledFeatures = enabled.toSet()
    return copy(
        features =
            features.map { feature ->
                if (feature.feature in analysisFeatures) {
                    feature.copy(enabled = feature.feature in enabledFeatures)
                } else {
                    feature
                }
            },
    )
}

private fun scope(
    sourceSets: NameSelectionPlan,
    policy: UnknownSelectionPolicy,
): DocxScopePlan =
    DocxScopePlan(
        builds = allNames(),
        projects = allNames(),
        sourceSets = sourceSets,
        includeTests = true,
        configuredBuilds = emptyList(),
        unknownSelections = policy,
    )
