@file:Suppress("LongMethod")

package zone.clanker.report.model

internal fun completeWorkspaceSnapshot(): WorkspaceSnapshot =
    WorkspaceSnapshot(
        workspace = WorkspaceIdentity("workspace:fixture", "fixture"),
        builds =
            listOf(
                BuildSnapshot("build:included", "included", BuildKind.INCLUDED, "../included"),
                BuildSnapshot("build:root", "fixture", BuildKind.ROOT, "."),
            ),
        buildEdges = listOf(BuildEdgeSnapshot("build-edge:root-included", "build:root", "build:included")),
        projects =
            listOf(
                ProjectSnapshot(
                    id = "project:included",
                    buildId = "build:included",
                    path = ":library",
                    buildFile = "build.gradle.kts",
                    sourceDirectories = listOf("src/main/kotlin"),
                ),
                ProjectSnapshot(
                    id = "project:root",
                    buildId = "build:root",
                    path = ":app",
                    buildFile = "app/build.gradle.kts",
                    sourceDirectories = listOf("app/src/main/kotlin"),
                    subprojectPaths = listOf(":app:feature"),
                ),
            ),
        projectDependencies =
            listOf(
                ProjectDependencySnapshot(
                    id = "project-dependency:kotlin",
                    projectId = "project:root",
                    group = "org.jetbrains.kotlin",
                    artifact = "kotlin-stdlib",
                    version = "2.3.0",
                    scope = "implementation",
                ),
            ),
        sourceSets =
            listOf(
                SourceSetSnapshot(
                    "source-set:included",
                    "project:included",
                    "main",
                    listOf("src/main/kotlin"),
                ),
                SourceSetSnapshot(
                    "source-set:root",
                    "project:root",
                    "main",
                    listOf("app/src/main/kotlin"),
                ),
            ),
        files =
            listOf(
                SourceFileSnapshot(
                    "file:consumer",
                    "source-set:root",
                    "app/src/main/kotlin/fixture/Consumer.kt",
                    SourceLanguage.KOTLIN,
                    "import library.Contract\nclass Consumer {\n  fun create() = Implementation()\n}\n",
                ),
                SourceFileSnapshot(
                    "file:contract",
                    "source-set:included",
                    "src/main/kotlin/library/Contract.kt",
                    SourceLanguage.KOTLIN,
                    "interface Contract\n",
                ),
                SourceFileSnapshot(
                    "file:helper",
                    "source-set:root",
                    "app/src/main/kotlin/fixture/Helper.kt",
                    SourceLanguage.KOTLIN,
                    "import fixture.Consumer\nclass Helper(val consumer: Consumer)\n",
                ),
                SourceFileSnapshot(
                    "file:implementation",
                    "source-set:included",
                    "src/main/kotlin/library/Implementation.kt",
                    SourceLanguage.KOTLIN,
                    "object Implementation : Contract\n",
                ),
            ),
        symbols =
            listOf(
                SymbolSnapshot(
                    "symbol:consumer",
                    "file:consumer",
                    "Consumer",
                    "fixture.Consumer",
                    "fixture",
                    SymbolKind.CLASS,
                    DeclarationSemantic.CONCRETE_CLASS,
                    2,
                ),
                SymbolSnapshot(
                    "symbol:contract",
                    "file:contract",
                    "Contract",
                    "library.Contract",
                    "library",
                    SymbolKind.INTERFACE,
                    DeclarationSemantic.INTERFACE,
                    1,
                ),
                SymbolSnapshot(
                    "symbol:helper",
                    "file:helper",
                    "Helper",
                    "fixture.Helper",
                    "fixture",
                    SymbolKind.CLASS,
                    DeclarationSemantic.CONCRETE_CLASS,
                    2,
                ),
                SymbolSnapshot(
                    "symbol:implementation",
                    "file:implementation",
                    "Implementation",
                    "library.Implementation",
                    "library",
                    SymbolKind.OBJECT,
                    DeclarationSemantic.SINGLETON_OBJECT,
                    1,
                ),
            ),
        references =
            listOf(
                ReferenceSnapshot(
                    "reference:constructor",
                    "file:consumer",
                    "symbol:consumer",
                    3,
                    "fun create() = Implementation()",
                    "Implementation",
                    "library.Implementation",
                    ReferenceKind.CONSTRUCTOR,
                    RelationshipEvidence.DIRECT,
                ),
                ReferenceSnapshot(
                    "reference:helper-consumer",
                    "file:helper",
                    "symbol:helper",
                    2,
                    "class Helper(val consumer: Consumer)",
                    "Consumer",
                    "fixture.Consumer",
                    ReferenceKind.TYPE_REFERENCE,
                    RelationshipEvidence.DIRECT,
                ),
                ReferenceSnapshot(
                    "reference:helper-import-consumer",
                    "file:helper",
                    null,
                    1,
                    "import fixture.Consumer",
                    "Consumer",
                    "fixture.Consumer",
                    ReferenceKind.IMPORT,
                    RelationshipEvidence.DIRECT,
                ),
                ReferenceSnapshot(
                    "reference:implementation-helper",
                    "file:implementation",
                    "symbol:implementation",
                    1,
                    "object Implementation : Contract",
                    "Helper",
                    "fixture.Helper",
                    ReferenceKind.TYPE_REFERENCE,
                    RelationshipEvidence.DIRECT,
                ),
                ReferenceSnapshot(
                    "reference:import",
                    "file:consumer",
                    null,
                    1,
                    "import library.Contract",
                    "Contract",
                    "library.Contract",
                    ReferenceKind.IMPORT,
                    RelationshipEvidence.DIRECT,
                ),
                ReferenceSnapshot(
                    "reference:unresolved",
                    "file:consumer",
                    "symbol:consumer",
                    2,
                    "class Consumer",
                    "MissingPolicy",
                    null,
                    ReferenceKind.TYPE_REFERENCE,
                    RelationshipEvidence.HEURISTIC,
                ),
            ),
        relationships =
            listOf(
                RelationshipSnapshot(
                    "relationship:constructor",
                    "reference:constructor",
                    "symbol:consumer",
                    "symbol:implementation",
                    RelationshipKind.CONSTRUCTOR,
                    RelationshipEvidence.DERIVED,
                ),
                RelationshipSnapshot(
                    "relationship:helper-consumer",
                    "reference:helper-consumer",
                    "symbol:helper",
                    "symbol:consumer",
                    RelationshipKind.TYPE_REFERENCE,
                    RelationshipEvidence.DIRECT,
                ),
                RelationshipSnapshot(
                    "relationship:helper-import-consumer",
                    "reference:helper-import-consumer",
                    null,
                    "symbol:consumer",
                    RelationshipKind.IMPORT,
                    RelationshipEvidence.DIRECT,
                ),
                RelationshipSnapshot(
                    "relationship:implementation-helper",
                    "reference:implementation-helper",
                    "symbol:implementation",
                    "symbol:helper",
                    RelationshipKind.TYPE_REFERENCE,
                    RelationshipEvidence.DIRECT,
                ),
                RelationshipSnapshot(
                    "relationship:import",
                    "reference:import",
                    null,
                    "symbol:contract",
                    RelationshipKind.IMPORT,
                    RelationshipEvidence.DIRECT,
                ),
            ),
        relationshipCycles =
            listOf(
                CycleSnapshot(
                    "relationship-cycle:consumer-implementation-helper",
                    listOf(
                        "symbol:consumer",
                        "symbol:implementation",
                        "symbol:helper",
                        "symbol:consumer",
                    ),
                    listOf(
                        "relationship:constructor",
                        "relationship:implementation-helper",
                        "relationship:helper-consumer",
                    ),
                ),
            ),
        projectAnalyses = listOf(rootProjectAnalysis()),
        aggregateAnalysisPresent = true,
        aggregateFindings =
            listOf(
                FindingSnapshot(
                    id = "aggregate-finding:cycle",
                    severity = FindingSeverity.WARNING,
                    message = "Consumer and Helper form a directed cycle",
                    suggestion = "Move shared policy to an acyclic owner.",
                    filePath = "app/src/main/kotlin/fixture/Consumer.kt",
                    line = 2,
                    componentIds = listOf("fixture.Consumer", "fixture.Helper"),
                    componentCycle = listOf("fixture.Consumer", "fixture.Helper", "fixture.Consumer"),
                ),
            ),
        aggregateHubs =
            listOf(
                HubSnapshot(
                    id = "aggregate-hub:consumer",
                    name = "Consumer",
                    dependentCount = 1,
                    role = "coordinator",
                    filePath = "app/src/main/kotlin/fixture/Consumer.kt",
                    line = 2,
                    sourceFileId = "file:consumer",
                    dependents =
                        listOf(
                            HubDependentSnapshot(
                                "Helper",
                                "app/src/main/kotlin/fixture/Helper.kt",
                                1,
                            ),
                        ),
                ),
            ),
        aggregateNamedCycles =
            listOf(
                NamedCycleSnapshot(
                    "aggregate-named-cycle:consumer-helper",
                    listOf("Consumer", "Helper", "Consumer"),
                ),
            ),
        entryPoints =
            listOf(
                WorkspaceEntryPointSnapshot(
                    "entry-point:consumer",
                    "Consumer",
                    "fixture",
                    WorkspaceEntryPointKind.APP,
                    "project:root",
                    "symbol:consumer",
                ),
            ),
        interfaces =
            listOf(
                InterfaceSnapshot(
                    "interface:contract",
                    "Contract",
                    "library",
                    1,
                    false,
                    "main",
                    "library.Contract",
                    "build:included",
                    "project:included",
                    "symbol:contract",
                ),
            ),
        importantSymbols =
            listOf(
                ImportantSymbolSnapshot(
                    "important:consumer",
                    "symbol:consumer",
                    listOf(
                        ImportantReasonSnapshot(
                            ImportantSymbolReason.ENTRY_POINT,
                            140,
                            "Entry point",
                            "An exact project analysis identifies the declaration as an entry point.",
                        ),
                    ),
                    140,
                    ImportantSymbolUsageSnapshot(
                        incomingRelationshipIds =
                            listOf(
                                "relationship:helper-consumer",
                                "relationship:helper-import-consumer",
                            ),
                        outgoingRelationshipIds = listOf("relationship:constructor"),
                        localInboundCount = 1,
                        workspaceInboundCount = 1,
                        crossBuildInboundCount = 0,
                        isWorkspaceUsed = true,
                    ),
                ),
            ),
        dependencyInjection =
            DependencyInjectionSnapshot(
                bindings =
                    listOf(
                        DependencyInjectionBindingSnapshot(
                            "binding:consumer",
                            DependencyInjectionFramework.DAGGER,
                            "fixture.Consumer",
                            "symbol:consumer",
                            null,
                        ),
                        DependencyInjectionBindingSnapshot(
                            "binding:contract",
                            DependencyInjectionFramework.HILT,
                            "library.Contract",
                            "symbol:contract",
                            "Singleton",
                        ),
                    ),
                edges =
                    listOf(
                        DependencyInjectionEdgeSnapshot(
                            "binding-edge:consumer-contract",
                            "binding:consumer",
                            "binding:contract",
                        ),
                    ),
            ),
    )

private fun rootProjectAnalysis(): ProjectAnalysisSnapshot =
    ProjectAnalysisSnapshot(
        id = "analysis:root",
        projectId = "project:root",
        findings =
            listOf(
                FindingSnapshot(
                    id = "finding:cycle",
                    severity = FindingSeverity.WARNING,
                    message = "Consumer and Helper form a directed cycle",
                    suggestion = "Move shared policy to an acyclic owner.",
                    fileId = "file:consumer",
                    filePath = "app/src/main/kotlin/fixture/Consumer.kt",
                    line = 2,
                    componentIds = listOf("fixture.Consumer", "fixture.Helper"),
                    resolvedComponentIds = listOf("component:consumer", "component:helper"),
                    symbolIds = listOf("symbol:consumer", "symbol:helper"),
                    componentCycle = listOf("fixture.Consumer", "fixture.Helper", "fixture.Consumer"),
                ),
            ),
        hubs =
            listOf(
                HubSnapshot(
                    "hub:consumer",
                    "Consumer",
                    1,
                    "coordinator",
                    "app/src/main/kotlin/fixture/Consumer.kt",
                    2,
                    "file:consumer",
                    listOf(HubDependentSnapshot("Helper", "app/src/main/kotlin/fixture/Helper.kt", 1)),
                ),
            ),
        components =
            listOf(
                ArchitectureComponentSnapshot(
                    "component:consumer",
                    "fixture.Consumer",
                    "Consumer",
                    "fixture",
                    "fixture",
                    "coordinator",
                    ArchitectureLayer.DOMAIN,
                    "app/src/main/kotlin/fixture/Consumer.kt",
                    2,
                    false,
                    "file:consumer",
                    "symbol:consumer",
                ),
                ArchitectureComponentSnapshot(
                    "component:helper",
                    "fixture.Helper",
                    "Helper",
                    "fixture",
                    "fixture",
                    "support",
                    ArchitectureLayer.DOMAIN,
                    "app/src/main/kotlin/fixture/Helper.kt",
                    1,
                    false,
                    "file:helper",
                    "symbol:helper",
                ),
            ),
        dependencies =
            listOf(
                ArchitectureDependencySnapshot(
                    "architecture-dependency:consumer-helper",
                    "component:consumer",
                    "component:helper",
                ),
                ArchitectureDependencySnapshot(
                    "architecture-dependency:helper-consumer",
                    "component:helper",
                    "component:consumer",
                ),
            ),
        entryPoints =
            listOf(
                ArchitectureEntryPointSnapshot(
                    "architecture-entry-point:consumer",
                    "component:consumer",
                    "Application start",
                    ArchitectureEntryPointKind.EXPLICIT,
                ),
            ),
        cycles =
            listOf(
                ArchitectureCycleSnapshot(
                    "architecture-cycle:consumer-helper",
                    listOf("component:consumer", "component:helper", "component:consumer"),
                ),
            ),
        legacyNameCycles =
            listOf(
                NamedCycleSnapshot(
                    "named-cycle:consumer-helper",
                    listOf("Consumer", "Helper", "Consumer"),
                ),
            ),
    )

internal fun goldenWorkspaceSnapshot(): WorkspaceSnapshot =
    WorkspaceSnapshot(
        workspace = WorkspaceIdentity("workspace:golden", "golden"),
        builds = listOf(BuildSnapshot("build:root", "golden", BuildKind.ROOT, ".")),
        projects = listOf(ProjectSnapshot("project:root", "build:root", ":", "build.gradle.kts")),
        sourceSets = listOf(SourceSetSnapshot("source-set:main", "project:root", "main")),
        files =
            listOf(
                SourceFileSnapshot(
                    "file:app",
                    "source-set:main",
                    "src/main/kotlin/App.kt",
                    SourceLanguage.KOTLIN,
                    "class App\n",
                ),
            ),
        symbols =
            listOf(
                SymbolSnapshot(
                    "symbol:app",
                    "file:app",
                    "App",
                    "fixture.App",
                    "fixture",
                    SymbolKind.CLASS,
                    DeclarationSemantic.CONCRETE_CLASS,
                    1,
                ),
            ),
        relationships = emptyList(),
    )
