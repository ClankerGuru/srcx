@file:Suppress("LongMethod")

package zone.clanker.gradle.srcx.snapshot

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
import zone.clanker.gradle.srcx.model.DeclarationSemantic
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

internal fun completeWorkspaceReport(): WorkspaceReport {
    val consumer = workspaceSymbol("fixture", ":app", "Consumer", "fixture.Consumer", "Consumer.kt", 2)
    val helper = workspaceSymbol("fixture", ":app", "Helper", "fixture.Helper", "Helper.kt", 1)
    val contract =
        workspaceSymbol(
            "included",
            ":library",
            "Contract",
            "library.Contract",
            "Contract.kt",
            1,
            SymbolDetailKind.INTERFACE,
            DeclarationSemantic.INTERFACE,
        )
    val implementation =
        workspaceSymbol(
            "included",
            ":library",
            "Implementation",
            "library.Implementation",
            "Implementation.kt",
            1,
            SymbolDetailKind.OBJECT,
            DeclarationSemantic.SINGLETON_OBJECT,
        )
    val constructorReference =
        WorkspaceReference(
            build = "fixture",
            project = ":app",
            sourceSet = "main",
            sourceSymbol = consumer,
            targetName = "Implementation",
            targetQualifiedName = "library.Implementation",
            kind = ReferenceKind.CONSTRUCTOR,
            projectRelativeFile = consumer.projectRelativeFile,
            line = 3,
            context = "fun create() = Implementation()",
            evidence = ReferenceEvidence.DIRECT,
        )
    val importReference =
        WorkspaceReference(
            build = "fixture",
            project = ":app",
            sourceSet = "main",
            sourceSymbol = null,
            targetName = "Contract",
            targetQualifiedName = "library.Contract",
            kind = ReferenceKind.IMPORT,
            projectRelativeFile = consumer.projectRelativeFile,
            line = 1,
            context = "import library.Contract",
            evidence = ReferenceEvidence.DIRECT,
        )
    val helperConsumerReference =
        WorkspaceReference(
            build = "fixture",
            project = ":app",
            sourceSet = "main",
            sourceSymbol = helper,
            targetName = "Consumer",
            targetQualifiedName = "fixture.Consumer",
            kind = ReferenceKind.TYPE_REF,
            projectRelativeFile = helper.projectRelativeFile,
            line = 1,
            context = "class Helper",
            evidence = ReferenceEvidence.DIRECT,
        )
    val helperImportConsumerReference =
        helperConsumerReference.copy(
            sourceSymbol = null,
            kind = ReferenceKind.IMPORT,
        )
    val implementationHelperReference =
        WorkspaceReference(
            build = "included",
            project = ":library",
            sourceSet = "main",
            sourceSymbol = implementation,
            targetName = "Helper",
            targetQualifiedName = "fixture.Helper",
            kind = ReferenceKind.TYPE_REF,
            projectRelativeFile = implementation.projectRelativeFile,
            line = 1,
            context = "object Implementation : Contract",
            evidence = ReferenceEvidence.DIRECT,
        )
    val unresolvedReference =
        WorkspaceReference(
            build = "fixture",
            project = ":app",
            sourceSet = "main",
            sourceSymbol = consumer,
            targetName = "MissingPolicy",
            targetQualifiedName = null,
            kind = ReferenceKind.TYPE_REF,
            projectRelativeFile = consumer.projectRelativeFile,
            line = 2,
            context = "class Consumer",
            evidence = ReferenceEvidence.HEURISTIC,
        )
    val relationships =
        listOf(
            WorkspaceRelationship(
                source = consumer,
                target = implementation,
                kind = WorkspaceRelationshipKind.CONSTRUCTOR,
                sourceEvidence = constructorReference,
                evidence = ReferenceEvidence.DERIVED,
            ),
            WorkspaceRelationship(
                source = helper,
                target = consumer,
                kind = WorkspaceRelationshipKind.TYPE_REFERENCE,
                sourceEvidence = helperConsumerReference,
                evidence = ReferenceEvidence.DIRECT,
            ),
            WorkspaceRelationship(
                source = null,
                target = consumer,
                kind = WorkspaceRelationshipKind.IMPORT,
                sourceEvidence = helperImportConsumerReference,
                evidence = ReferenceEvidence.DIRECT,
            ),
            WorkspaceRelationship(
                source = implementation,
                target = helper,
                kind = WorkspaceRelationshipKind.TYPE_REFERENCE,
                sourceEvidence = implementationHelperReference,
                evidence = ReferenceEvidence.DIRECT,
            ),
            WorkspaceRelationship(
                source = null,
                target = contract,
                kind = WorkspaceRelationshipKind.IMPORT,
                sourceEvidence = importReference,
                evidence = ReferenceEvidence.DIRECT,
            ),
        )
    val consumerUsage =
        WorkspaceSymbolUsage(
            symbol = consumer,
            incoming = relationships.filter { it.targetIdentity == consumer.identity },
            outgoing = relationships.filter { it.sourceIdentity == consumer.identity },
        )
    return WorkspaceReport(
        name = "fixture",
        rootProjects = listOf(rootProject()),
        includedBuilds =
            listOf(
                IncludedBuildSummary(
                    name = "included",
                    relativePath = "../included",
                    projects = listOf(includedProject()),
                ),
            ),
        buildEdges = listOf(BuildEdge("fixture", "included")),
        aggregateAnalysis = rootAnalysis(),
        entryPoints = listOf(EntryPointSummary("Consumer", "fixture", EntryPointKind.APP)),
        interfaces =
            listOf(
                InterfaceSummary(
                    name = "Contract",
                    packageName = "library",
                    implementationCount = 1,
                    hasMock = false,
                    sourceSet = "main",
                    build = "included",
                    project = ":library",
                    qualifiedName = "library.Contract",
                    identity = contract.identity,
                ),
            ),
        workspaceIndex =
            WorkspaceIndex(
                symbols = listOf(consumer, helper, contract, implementation),
                references =
                    listOf(
                        unresolvedReference,
                        importReference,
                        constructorReference,
                        helperConsumerReference,
                        helperImportConsumerReference,
                        implementationHelperReference,
                    ),
                relationships = relationships,
                usages = listOf(consumerUsage),
            ),
        importantSymbols =
            listOf(
                ImportantSymbol(
                    symbol = consumer,
                    reasons =
                        listOf(
                            ImportantSymbolReason.HIGH_WORKSPACE_OUTBOUND,
                            ImportantSymbolReason.ENTRY_POINT,
                        ),
                    score =
                        ImportantSymbolReason.HIGH_WORKSPACE_OUTBOUND.score +
                            ImportantSymbolReason.ENTRY_POINT.score,
                    usage = consumerUsage,
                ),
            ),
        sourceFiles = workspaceSourceFiles(),
    )
}

private fun rootProject(): ProjectSummary =
    projectSummary(
        path = ":app",
        symbols =
            listOf(
                symbolEntry("Consumer", "fixture", "fixture/Consumer.kt", 2),
                symbolEntry("Helper", "fixture", "fixture/Helper.kt", 1),
            ),
        sourceDirectories = listOf("app/src/main/kotlin", "app/src/main/java"),
        analysis = rootAnalysis(),
        dependencies =
            listOf(
                DependencyEntry(
                    ArtifactGroup("org.jetbrains.kotlin"),
                    ArtifactName("kotlin-stdlib"),
                    ArtifactVersion("2.3.0"),
                    "implementation",
                ),
            ),
    )

private fun includedProject(): ProjectSummary =
    projectSummary(
        path = ":library",
        symbols =
            listOf(
                symbolEntry("Contract", "library", "library/Contract.kt", 1),
                symbolEntry("Implementation", "library", "library/Implementation.kt", 1),
            ),
        sourceDirectories = listOf("src/main/kotlin"),
    )

private fun projectSummary(
    path: String,
    symbols: List<SymbolEntry>,
    sourceDirectories: List<String>,
    analysis: AnalysisSummary? = null,
    dependencies: List<DependencyEntry> = emptyList(),
): ProjectSummary =
    ProjectSummary(
        projectPath = ProjectPath(path),
        symbols = symbols,
        dependencies = dependencies,
        buildFile = "build.gradle.kts",
        sourceDirs = sourceDirectories,
        subprojects = emptyList(),
        sourceSets = listOf(SourceSetSummary(SourceSetName("main"), symbols, sourceDirectories)),
        analysis = analysis,
    )

private fun symbolEntry(
    name: String,
    packageName: String,
    path: String,
    line: Int,
): SymbolEntry =
    SymbolEntry(
        SymbolName(name),
        SymbolKind.CLASS,
        PackageName(packageName),
        FilePath(path),
        line,
    )

@Suppress("LongParameterList")
private fun workspaceSymbol(
    build: String,
    project: String,
    name: String,
    qualifiedName: String,
    fileName: String,
    line: Int,
    kind: SymbolDetailKind = SymbolDetailKind.CLASS,
    semantic: DeclarationSemantic = DeclarationSemantic.CONCRETE_CLASS,
): WorkspaceSymbol =
    WorkspaceSymbol(
        build = build,
        project = project,
        sourceSet = "main",
        name = name,
        qualifiedName = qualifiedName,
        kind = kind,
        projectRelativeFile =
            if (build == "fixture") {
                "app/src/main/kotlin/fixture/$fileName"
            } else {
                "src/main/kotlin/library/$fileName"
            },
        declarationLine = line,
        declarationSemantic = semantic,
    )

private fun rootAnalysis(): AnalysisSummary {
    val consumer = architectureComponent("Consumer", "coordinator", 2)
    val helper = architectureComponent("Helper", "support", 1)
    val cycle = ArchitectureComponentCycle(listOf(consumer.id, helper.id, consumer.id))
    return AnalysisSummary(
        findings =
            listOf(
                Finding(
                    severity = FindingSeverity.WARNING,
                    message = "Consumer and Helper form a directed cycle",
                    suggestion = "Move shared policy to an acyclic owner.",
                    filePath = "fixture/Consumer.kt",
                    line = 2,
                    componentIds = listOf(consumer.id, helper.id),
                    componentCycle = cycle,
                ),
            ),
        hubs =
            listOf(
                HubClass(
                    name = "Consumer",
                    dependentCount = 1,
                    role = "coordinator",
                    filePath = "fixture/Consumer.kt",
                    line = 2,
                    dependents = listOf(HubDependentRef("Helper", "fixture/Helper.kt", 1)),
                ),
            ),
        cycles = listOf(listOf("Consumer", "Helper", "Consumer")),
        architecture =
            ArchitectureSummary(
                components = listOf(consumer, helper),
                dependencies =
                    listOf(
                        ArchitectureDependency(consumer.id, helper.id),
                        ArchitectureDependency(helper.id, consumer.id),
                    ),
                entryPoints =
                    listOf(
                        ArchitectureEntryPoint(
                            consumer.id,
                            "Application start",
                            ArchitectureEntryPointKind.EXPLICIT,
                        ),
                    ),
                cycles = listOf(cycle),
            ),
    )
}

private fun architectureComponent(
    name: String,
    role: String,
    line: Int,
): ArchitectureComponent =
    ArchitectureComponent(
        id = "fixture.$name",
        name = name,
        packageName = "fixture",
        packageGroup = "fixture",
        role = role,
        layer = ArchitectureLayer.DOMAIN,
        filePath = "fixture/$name.kt",
        line = line,
        isTest = false,
    )

private fun workspaceSourceFiles(): List<WorkspaceSourceFile> =
    listOf(
        sourceFile("fixture", ":app", "app/src/main/java/fixture/JavaThing.java", "class JavaThing\n"),
        sourceFile(
            "fixture",
            ":app",
            "app/src/main/kotlin/fixture/Consumer.kt",
            "import library.Contract\nclass Consumer {\n  fun create() = Implementation()\n}\n",
        ),
        sourceFile("fixture", ":app", "app/src/main/kotlin/fixture/Helper.kt", "class Helper\n"),
        sourceFile("fixture", ":app", "app/src/main/resources/config.json", "{}\n"),
        sourceFile("fixture", ":app", "app/src/main/resources/config.yaml", "enabled: true\n"),
        sourceFile("fixture", ":app", "app/src/main/resources/readme.md", "# Fixture\n"),
        sourceFile("fixture", ":app", "app/src/main/resources/settings.toml", "enabled = true\n"),
        sourceFile("fixture", ":app", "app/src/main/scripts/tool.groovy", "class Tool {}\n"),
        sourceFile("fixture", ":app", "app/src/main/scripts/tool.kts", "println(\"tool\")\n"),
        sourceFile("included", ":library", "src/main/kotlin/library/Contract.kt", "interface Contract\n"),
        sourceFile(
            "included",
            ":library",
            "src/main/kotlin/library/Implementation.kt",
            "object Implementation : Contract\n",
        ),
    )

private fun sourceFile(
    build: String,
    project: String,
    path: String,
    content: String,
): WorkspaceSourceFile = WorkspaceSourceFile(build, project, "main", path, content)

internal fun WorkspaceReport.reorderedForSnapshotTest(): WorkspaceReport =
    copy(
        rootProjects = rootProjects.reversed().map(ProjectSummary::reorderedForSnapshotTest),
        includedBuilds =
            includedBuilds.reversed().map { build ->
                build.copy(projects = build.projects.reversed().map(ProjectSummary::reorderedForSnapshotTest))
            },
        buildEdges = buildEdges.reversed(),
        aggregateAnalysis = aggregateAnalysis?.reorderedForSnapshotTest(),
        entryPoints = entryPoints.reversed(),
        interfaces = interfaces.reversed(),
        workspaceIndex =
            workspaceIndex.copy(
                symbols = workspaceIndex.symbols.reversed(),
                references = workspaceIndex.references.reversed(),
                relationships = workspaceIndex.relationships.reversed(),
                usages = workspaceIndex.usages.reversed(),
            ),
        importantSymbols =
            importantSymbols.reversed().map { important ->
                important.copy(reasons = important.reasons.reversed())
            },
    )

private fun ProjectSummary.reorderedForSnapshotTest(): ProjectSummary =
    copy(
        symbols = symbols.reversed(),
        dependencies = dependencies.reversed(),
        sourceDirs = sourceDirs.reversed(),
        subprojects = subprojects.reversed(),
        sourceSets = sourceSets.reversed(),
        analysis = analysis?.reorderedForSnapshotTest(),
    )

private fun AnalysisSummary.reorderedForSnapshotTest(): AnalysisSummary =
    copy(
        findings = findings.reversed().map { finding -> finding.copy(componentIds = finding.componentIds.reversed()) },
        hubs = hubs.reversed().map { hub -> hub.copy(dependents = hub.dependents.reversed()) },
        cycles = cycles.reversed(),
        architecture =
            architecture.copy(
                components = architecture.components.reversed(),
                dependencies = architecture.dependencies.reversed(),
                entryPoints = architecture.entryPoints.reversed(),
                cycles = architecture.cycles.reversed(),
            ),
    )
