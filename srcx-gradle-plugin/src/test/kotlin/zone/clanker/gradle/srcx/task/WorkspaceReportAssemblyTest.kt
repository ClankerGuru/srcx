package zone.clanker.gradle.srcx.task

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import zone.clanker.gradle.srcx.model.AnalysisSummary
import zone.clanker.gradle.srcx.model.ArchitectureComponent
import zone.clanker.gradle.srcx.model.ArchitectureComponentCycle
import zone.clanker.gradle.srcx.model.ArchitectureDependency
import zone.clanker.gradle.srcx.model.ArchitectureEntryPoint
import zone.clanker.gradle.srcx.model.ArchitectureEntryPointKind
import zone.clanker.gradle.srcx.model.ArchitectureLayer
import zone.clanker.gradle.srcx.model.ArchitectureSummary
import zone.clanker.gradle.srcx.model.BuildEdge
import zone.clanker.gradle.srcx.model.Finding
import zone.clanker.gradle.srcx.model.FindingSeverity
import zone.clanker.gradle.srcx.model.ProjectPath
import zone.clanker.gradle.srcx.model.ProjectSummary
import zone.clanker.gradle.srcx.model.ReferenceEvidence
import zone.clanker.gradle.srcx.model.ReferenceKind
import zone.clanker.gradle.srcx.model.SourceSetName
import zone.clanker.gradle.srcx.model.SymbolDetailKind
import zone.clanker.gradle.srcx.model.WorkspaceIndex
import zone.clanker.gradle.srcx.model.WorkspaceReference
import zone.clanker.gradle.srcx.model.WorkspaceRelationship
import zone.clanker.gradle.srcx.model.WorkspaceRelationshipKind
import zone.clanker.gradle.srcx.model.WorkspaceSymbol
import zone.clanker.gradle.srcx.scan.ProjectFileScan
import zone.clanker.gradle.srcx.scan.ProjectScan

class WorkspaceReportAssemblyTest :
    BehaviorSpec({
        given("scans assembled for a workspace") {
            val root = projectScan("workspace", ":app")
            val library = projectScan("library", ":core")
            val scans =
                WorkspaceScans(
                    rootBuild = "workspace",
                    rootProjectScans = listOf(root),
                    includedProjectScans = linkedMapOf("library" to listOf(library), "empty" to emptyList()),
                )

            `when`("scans and compatible summaries are read") {
                then("build names and project paths remain scoped and ordered") {
                    scans.allScans.map { it.build to it.projectPath.value } shouldContainExactly
                        listOf("workspace" to ":app", "library" to ":core")
                }

                then("legacy report inputs are the summaries from those scans") {
                    (scans.rootSummaries.single() === root.summary) shouldBe true
                    (scans.includedSummaries.getValue("library").single() === library.summary) shouldBe true
                    scans.includedSummaries.getValue("empty").shouldBeEmpty()
                }
            }
        }

        given("root and included scans with exact same-path source files") {
            val relativePath = "src/main/kotlin/sample/Shared.kt"
            val root =
                projectScan(
                    build = "workspace",
                    project = ":app",
                    files =
                        listOf(
                            projectFileScan(
                                sourceSet = "test",
                                relativePath = relativePath,
                                sourceText = "// root test",
                            ),
                            projectFileScan(
                                sourceSet = "main",
                                relativePath = relativePath,
                                sourceText = "// root café 雪 🚀",
                            ),
                        ),
                )
            val library =
                projectScan(
                    build = "library",
                    project = ":app",
                    files =
                        listOf(
                            projectFileScan(
                                sourceSet = "main",
                                relativePath = relativePath,
                                sourceText = "// included library",
                            ),
                        ),
                )
            val tools =
                projectScan(
                    build = "tools",
                    project = ":tooling",
                    files =
                        listOf(
                            projectFileScan(
                                sourceSet = "main",
                                relativePath = "src/main/kotlin/tools/Tool.kt",
                                sourceText = "// included tools",
                            ),
                        ),
                )
            val scans =
                WorkspaceScans(
                    rootBuild = "workspace",
                    rootProjectScans = listOf(root),
                    includedProjectScans = linkedMapOf("tools" to listOf(tools), "library" to listOf(library)),
                )

            `when`("source files are assembled into report data") {
                val sourceFiles = buildWorkspaceSourceFiles(scans)

                then("root and every included build retain complete exact source text") {
                    sourceFiles.map { it.content } shouldContainExactly
                        listOf(
                            "// included library",
                            "// included tools",
                            "// root café 雪 🚀",
                            "// root test",
                        )
                }

                then("same paths remain distinct by build and source-set scope") {
                    sourceFiles.map { it.identity.value } shouldContainExactly
                        listOf(
                            "library:::app::main::$relativePath",
                            "tools:::tooling::main::src/main/kotlin/tools/Tool.kt",
                            "workspace:::app::main::$relativePath",
                            "workspace:::app::test::$relativePath",
                        )
                }

                then("ordering is deterministic across input map and file order") {
                    val reordered =
                        WorkspaceScans(
                            rootBuild = "workspace",
                            rootProjectScans = listOf(root.copy(files = root.files.reversed())),
                            includedProjectScans = linkedMapOf("library" to listOf(library), "tools" to listOf(tools)),
                        )
                    buildWorkspaceSourceFiles(reordered) shouldContainExactly sourceFiles
                }
            }
        }

        given("architecture and cycle signals in duplicate workspace scopes") {
            val app = workspaceSymbol("workspace", ":app", "sample.App")
            val appInIncludedBuild = workspaceSymbol("included", ":app", "sample.App")
            val graphRoot = workspaceSymbol("workspace", ":app", "sample.GraphRoot")
            val uniqueCycle = workspaceSymbol("workspace", ":app", "sample.UniqueCycle")
            val firstDuplicateCycle = workspaceSymbol("workspace", ":app", "alpha.DuplicateCycle")
            val secondDuplicateCycle = workspaceSymbol("workspace", ":app", "beta.DuplicateCycle")
            val firstAmbiguousEntry = workspaceSymbol("workspace", ":app", "sample.AmbiguousEntry")
            val secondAmbiguousEntry =
                workspaceSymbol(
                    "workspace",
                    ":app",
                    "sample.AmbiguousEntry",
                    sourceSet = "generated",
                    line = 2,
                )
            val architecture =
                ArchitectureSummary(
                    components =
                        listOf(
                            component("sample.App"),
                            component("sample.GraphRoot"),
                            component("sample.AmbiguousEntry"),
                            component("sample.UniqueCycle"),
                            component("alpha.DuplicateCycle"),
                        ),
                    dependencies =
                        listOf(
                            ArchitectureDependency("sample.UniqueCycle", "alpha.DuplicateCycle"),
                            ArchitectureDependency("alpha.DuplicateCycle", "sample.UniqueCycle"),
                        ),
                    entryPoints =
                        listOf(
                            ArchitectureEntryPoint(
                                "sample.App",
                                "Declares main()",
                                ArchitectureEntryPointKind.EXPLICIT,
                            ),
                            ArchitectureEntryPoint(
                                "sample.GraphRoot",
                                "Dependency graph root",
                                ArchitectureEntryPointKind.GRAPH_ROOT,
                            ),
                            ArchitectureEntryPoint(
                                "sample.AmbiguousEntry",
                                "Framework entry point",
                                ArchitectureEntryPointKind.EXPLICIT,
                            ),
                        ),
                    cycles =
                        listOf(
                            ArchitectureComponentCycle(
                                listOf(
                                    "sample.UniqueCycle",
                                    "alpha.DuplicateCycle",
                                    "sample.UniqueCycle",
                                ),
                            ),
                        ),
                )
            val analysis =
                AnalysisSummary(
                    findings =
                        listOf(
                            Finding(
                                FindingSeverity.WARNING,
                                "sample.App appears in an anti-pattern finding",
                                "Review it",
                            ),
                            Finding(
                                FindingSeverity.WARNING,
                                "UniqueCycle has exact analyzer evidence",
                                "Review it",
                                componentIds = listOf("sample.UniqueCycle"),
                            ),
                            Finding(
                                FindingSeverity.WARNING,
                                "AmbiguousEntry has exact file evidence",
                                "Review it",
                                filePath = firstAmbiguousEntry.projectRelativeFile,
                                line = firstAmbiguousEntry.declarationLine,
                                componentIds = listOf(firstAmbiguousEntry.qualifiedName),
                            ),
                        ),
                    hubs = emptyList(),
                    cycles = listOf(listOf("UniqueCycle", "DuplicateCycle", "UniqueCycle")),
                    architecture = architecture,
                )
            val rootScan = projectScan("workspace", ":app", analysis)
            val includedScan = projectScan("included", ":app")
            val index =
                WorkspaceIndex(
                    symbols =
                        listOf(
                            app,
                            appInIncludedBuild,
                            graphRoot,
                            uniqueCycle,
                            firstDuplicateCycle,
                            secondDuplicateCycle,
                            firstAmbiguousEntry,
                            secondAmbiguousEntry,
                        ),
                )

            `when`("important-symbol signals are assembled") {
                val signals = buildImportantSymbolSignals(listOf(rootScan, includedScan), index)

                then("component source evidence disambiguates explicit entry points") {
                    signals.entryPoints shouldContainExactly setOf(app.identity, firstAmbiguousEntry.identity)
                }

                then("typed cycle IDs select exact participants without simple-name ambiguity") {
                    signals.cycleParticipants shouldContainExactly
                        setOf(uniqueCycle.identity, firstDuplicateCycle.identity)
                }

                then("only typed finding components produce anti-pattern signals") {
                    signals.antiPatternSymbols shouldContainExactly
                        setOf(uniqueCycle.identity, firstAmbiguousEntry.identity)
                }
            }
        }

        given("resolved and artifact-level cross-build dependencies") {
            val rootSource = workspaceSymbol("workspace", ":app", "app.Application")
            val rootTarget = workspaceSymbol("workspace", ":api", "api.RootApi")
            val librarySource = workspaceSymbol("library", ":core", "lib.Client")
            val libraryTarget = workspaceSymbol("library", ":api", "lib.Api")
            val toolsTarget = workspaceSymbol("tools", ":tools", "tools.Api")
            val relationships =
                listOf(
                    relationship(rootSource, libraryTarget, WorkspaceRelationshipKind.TYPE_REFERENCE),
                    relationship(librarySource, rootTarget, WorkspaceRelationshipKind.CALL),
                    relationship(rootSource, rootTarget, WorkspaceRelationshipKind.CONSTRUCTOR),
                    relationship(null, toolsTarget, WorkspaceRelationshipKind.IMPORT),
                )
            val index = WorkspaceIndex(relationships = relationships)
            val artifactEdges =
                listOf(
                    BuildEdge("workspace", "library"),
                    BuildEdge("library", "tools"),
                )

            `when`("workspace build edges are assembled") {
                val edges = buildWorkspaceBuildEdges(index, artifactEdges)

                then("source edges include both root directions and deterministic fallbacks once") {
                    edges shouldContainExactly
                        listOf(
                            BuildEdge("library", "tools"),
                            BuildEdge("library", "workspace"),
                            BuildEdge("workspace", "library"),
                        )
                }

                then("imports and same-build relationships are not source-level build edges") {
                    edges.none { it.to == "tools" && it.from == "workspace" } shouldBe true
                    edges.none { it.from == it.to } shouldBe true
                }
            }
        }

        given("an exact workspace interface") {
            val repository =
                workspaceSymbol(
                    build = "contracts",
                    project = ":api",
                    qualifiedName = "api.Repository",
                ).copy(kind = SymbolDetailKind.INTERFACE)
            val implementation = workspaceSymbol("runtime", ":data", "data.SqlRepository")
            val index =
                WorkspaceIndex(
                    symbols = listOf(implementation, repository),
                    relationships =
                        listOf(
                            relationship(
                                implementation,
                                repository,
                                WorkspaceRelationshipKind.IMPLEMENTS,
                            ),
                        ),
                )

            `when`("interface renderer facts are assembled into the workspace model") {
                val interfaces = buildInterfaceSummaries(emptyList(), index)

                then("scope, qualified name, identity, and exact implementation count survive conversion") {
                    interfaces.single().apply {
                        name shouldBe "Repository"
                        packageName shouldBe null
                        implementationCount shouldBe 1
                        build shouldBe "contracts"
                        project shouldBe ":api"
                        sourceSet shouldBe "main"
                        qualifiedName shouldBe "api.Repository"
                        identity shouldBe repository.identity
                    }
                }
            }
        }
    })

private fun projectScan(
    build: String,
    project: String,
    analysis: AnalysisSummary? = null,
    files: List<ProjectFileScan> = emptyList(),
): ProjectScan {
    val path = ProjectPath(project)
    val summary =
        ProjectSummary(
            projectPath = path,
            symbols = emptyList(),
            dependencies = emptyList(),
            buildFile = "build.gradle.kts",
            sourceDirs = emptyList(),
            subprojects = emptyList(),
            analysis = analysis,
        )
    return ProjectScan(
        build = build,
        projectPath = path,
        files = files,
        summary = summary,
    )
}

private fun projectFileScan(
    sourceSet: String,
    relativePath: String,
    sourceText: String,
): ProjectFileScan =
    ProjectFileScan(
        sourceSet = SourceSetName(sourceSet),
        projectRelativeFile = relativePath,
        declarations = emptyList(),
        references = emptyList(),
        sourceText = sourceText,
    )

private fun workspaceSymbol(
    build: String,
    project: String,
    qualifiedName: String,
    sourceSet: String = "main",
    line: Int = 1,
): WorkspaceSymbol =
    WorkspaceSymbol(
        build = build,
        project = project,
        sourceSet = sourceSet,
        name = qualifiedName.substringAfterLast('.'),
        qualifiedName = qualifiedName,
        kind = SymbolDetailKind.CLASS,
        projectRelativeFile = "src/$sourceSet/kotlin/${qualifiedName.replace('.', '/')}.kt",
        declarationLine = line,
    )

private fun component(qualifiedName: String): ArchitectureComponent =
    ArchitectureComponent(
        id = qualifiedName,
        name = qualifiedName.substringAfterLast('.'),
        packageName = qualifiedName.substringBeforeLast('.'),
        packageGroup = "sample",
        role = "Application",
        layer = ArchitectureLayer.PRESENTATION,
        filePath = "src/main/kotlin/${qualifiedName.replace('.', '/')}.kt",
        line = 1,
        isTest = false,
    )

private fun relationship(
    source: WorkspaceSymbol?,
    target: WorkspaceSymbol,
    kind: WorkspaceRelationshipKind,
): WorkspaceRelationship {
    val owner = source ?: target
    val referenceKind =
        when (kind) {
            WorkspaceRelationshipKind.IMPORT -> ReferenceKind.IMPORT
            WorkspaceRelationshipKind.CALL -> ReferenceKind.CALL
            WorkspaceRelationshipKind.CONSTRUCTOR -> ReferenceKind.CONSTRUCTOR
            else -> ReferenceKind.TYPE_REF
        }
    val reference =
        WorkspaceReference(
            build = owner.build,
            project = owner.project,
            sourceSet = owner.sourceSet,
            sourceSymbol = source,
            targetName = target.name,
            targetQualifiedName = target.qualifiedName,
            kind = referenceKind,
            projectRelativeFile = owner.projectRelativeFile,
            line = 1,
            context = target.name,
            evidence = ReferenceEvidence.DIRECT,
        )
    return WorkspaceRelationship(source, target, kind, reference, ReferenceEvidence.DIRECT)
}
