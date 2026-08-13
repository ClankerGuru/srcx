package zone.clanker.gradle.srcx.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class WorkspaceReportTest :
    BehaviorSpec({
        given("a fully populated WorkspaceReport") {
            val rootFinding = Finding(FindingSeverity.WARNING, "root warning", "fix root")
            val includedFinding = Finding(FindingSeverity.INFO, "included note", "review included")
            val productionHub = HubClass("RuntimeHub", 3, "service")
            val testHub = HubClass("RuntimeHubTest", 2, "test", isTest = true)
            val rootProject = projectSummary(":app", "App", rootFinding, withDependency = true)
            val includedProject = projectSummary(":lib", "Library", includedFinding)
            val includedBuild = IncludedBuildSummary("library-build", "../library", listOf(includedProject))
            val edge = BuildEdge("workspace", "library-build")
            val entryPoint = EntryPointSummary("Application", "com.example.app", EntryPointKind.APP)
            val interfaceIdentity =
                WorkspaceSymbolIdentity(
                    build = "example-workspace",
                    project = ":api",
                    sourceSet = "main",
                    qualifiedName = "com.example.api.Repository",
                    projectRelativeFile = "src/main/kotlin/com/example/api/Repository.kt",
                    declarationLine = 1,
                )
            val candidate =
                InterfaceSummary(
                    name = "Repository",
                    packageName = "com.example.api",
                    implementationCount = 2,
                    hasMock = true,
                    sourceSet = "main",
                    build = "example-workspace",
                    project = ":api",
                    qualifiedName = "com.example.api.Repository",
                    identity = interfaceIdentity,
                )
            val indexedSymbol = workspaceSymbol("com.example.app.Application")
            val usage = WorkspaceSymbolUsage(indexedSymbol, emptyList(), emptyList())
            val workspaceIndex = WorkspaceIndex(symbols = listOf(indexedSymbol), usages = listOf(usage))
            val rootSource =
                workspaceSourceFile(
                    build = "example-workspace",
                    project = ":app",
                    sourceSet = "main",
                    content = "package com.example.app\nclass Application",
                )
            val includedSource =
                workspaceSourceFile(
                    build = "library-build",
                    project = ":lib",
                    sourceSet = "main",
                    content = "package com.example.library\nclass Application",
                )
            val importantSymbol =
                ImportantSymbol(
                    indexedSymbol,
                    listOf(ImportantSymbolReason.ENTRY_POINT),
                    ImportantSymbolReason.ENTRY_POINT.score,
                    usage,
                )
            val aggregate =
                AnalysisSummary(
                    findings = listOf(rootFinding, includedFinding),
                    hubs = listOf(productionHub, testHub),
                    cycles = emptyList(),
                )

            `when`("its constructor and computed properties are read") {
                val report =
                    WorkspaceReport(
                        name = "example-workspace",
                        rootProjects = listOf(rootProject),
                        includedBuilds = listOf(includedBuild),
                        buildEdges = listOf(edge),
                        aggregateAnalysis = aggregate,
                        entryPoints = listOf(entryPoint),
                        interfaces = listOf(candidate),
                        workspaceIndex = workspaceIndex,
                        importantSymbols = listOf(importantSymbol),
                        sourceFiles = listOf(rootSource, includedSource),
                    )

                then("all constructor fields remain accessible") {
                    report.name shouldBe "example-workspace"
                    report.rootProjects shouldContainExactly listOf(rootProject)
                    report.includedBuilds shouldContainExactly listOf(includedBuild)
                    report.buildEdges shouldContainExactly listOf(edge)
                    report.aggregateAnalysis shouldBe aggregate
                    report.entryPoints shouldContainExactly listOf(entryPoint)
                    report.interfaces shouldContainExactly listOf(candidate)
                    report.workspaceIndex shouldBe workspaceIndex
                    report.importantSymbols shouldContainExactly listOf(importantSymbol)
                    report.sourceFiles shouldContainExactly listOf(rootSource, includedSource)
                }

                then("workspace totals include root and included projects") {
                    report.allProjects shouldContainExactly listOf(rootProject, includedProject)
                    report.projectCount shouldBe 2
                    report.symbolCount shouldBe 2
                    report.dependencyCount shouldBe 1
                    report.sourceSetCount shouldBe 2
                    report.indexedSymbolCount shouldBe 1
                    report.relationshipCount shouldBe 0
                    report.importantSymbolCount shouldBe 1
                }

                then("aggregate findings and production hubs are exposed") {
                    report.findings shouldContainExactly listOf(rootFinding, includedFinding)
                    report.productionHubs shouldContainExactly listOf(productionHub)
                }

                then("included-build totals are computed from its projects") {
                    includedBuild.symbolCount shouldBe 1
                    includedBuild.dependencyCount shouldBe 0
                    includedBuild.sourceSetCount shouldBe 1
                    includedBuild.findings shouldContainExactly listOf(includedFinding)
                }

                then("entry-point and interface domain data are accessible") {
                    entryPoint.name shouldBe "Application"
                    entryPoint.packageName shouldBe "com.example.app"
                    entryPoint.kind.label shouldBe "Application"
                    candidate.implementationCount shouldBe 2
                    candidate.hasMock shouldBe true
                    candidate.sourceSet shouldBe "main"
                    candidate.build shouldBe "example-workspace"
                    candidate.project shouldBe ":api"
                    candidate.qualifiedName shouldBe "com.example.api.Repository"
                    candidate.identity shouldBe interfaceIdentity
                }
            }
        }

        given("a WorkspaceReport without aggregate analysis") {
            val finding = Finding(FindingSeverity.INFO, "project note", "keep it")
            val hub = HubClass("ProjectHub", 1, "repository")
            val project =
                projectSummary(
                    path = ":",
                    symbolName = "Root",
                    finding = finding,
                    hubs = listOf(hub),
                )

            `when`("computed analysis data is read") {
                val report =
                    WorkspaceReport(
                        "fallback",
                        listOf(project),
                        emptyList(),
                        emptyList(),
                        null,
                        emptyList(),
                        emptyList(),
                    )

                then("project analysis is used as the fallback") {
                    report.findings shouldContainExactly listOf(finding)
                    report.productionHubs shouldContainExactly listOf(hub)
                    report.workspaceIndex shouldBe WorkspaceIndex()
                    report.importantSymbols shouldBe emptyList()
                    report.sourceFiles shouldBe emptyList()
                }
            }
        }

        given("source files whose paths collide in different workspace scopes") {
            val files =
                listOf(
                    workspaceSourceFile(
                        build = "included",
                        project = ":app",
                        sourceSet = "main",
                        content = "included main",
                    ),
                    workspaceSourceFile(
                        build = "workspace",
                        project = ":app",
                        sourceSet = "main",
                        content = "root main",
                    ),
                    workspaceSourceFile(
                        build = "workspace",
                        project = ":app",
                        sourceSet = "test",
                        content = "root test",
                    ),
                    workspaceSourceFile(
                        build = "workspace",
                        project = ":feature",
                        sourceSet = "main",
                        content = "root feature",
                    ),
                )

            `when`("the report validates their full scoped identities") {
                val report = emptyWorkspace(name = "scoped", sourceFiles = files)

                then("build, project, and source set keep every same-path file distinct") {
                    report.sourceFiles
                        .map { it.identity }
                        .distinct()
                        .size shouldBe files.size
                    report.sourceFiles
                        .map { it.projectRelativeFile }
                        .distinct() shouldContainExactly
                        listOf("src/main/kotlin/sample/Shared.kt")
                }

                then("identity values contain every ownership component") {
                    report.sourceFiles
                        .first()
                        .identity
                        .value shouldBe
                        "included:::app::main::src/main/kotlin/sample/Shared.kt"
                }
            }
        }

        given("two source files with the same workspace identity") {
            val first =
                workspaceSourceFile(
                    build = "workspace",
                    project = ":app",
                    sourceSet = "main",
                    content = "first",
                )
            val duplicate = first.copy(content = "second")

            `when`("they are added to one report") {
                then("the report rejects the ambiguous identity even when content differs") {
                    shouldThrow<IllegalArgumentException> {
                        emptyWorkspace(
                            name = "duplicate",
                            sourceFiles = listOf(first, duplicate),
                        )
                    }
                }
            }
        }

        given("source files outside canonical workspace scope order") {
            val later =
                workspaceSourceFile(
                    build = "workspace",
                    project = ":feature",
                    sourceSet = "main",
                    content = "later",
                )
            val earlier =
                workspaceSourceFile(
                    build = "workspace",
                    project = ":app",
                    sourceSet = "main",
                    content = "earlier",
                )

            `when`("they are added in reverse order") {
                then("the report rejects non-deterministic ordering") {
                    shouldThrow<IllegalArgumentException> {
                        emptyWorkspace(
                            name = "unordered",
                            sourceFiles = listOf(later, earlier),
                        )
                    }
                }
            }
        }

        given("invalid workspace domain values") {
            `when`("blank names, paths, or negative counts are supplied") {
                then("the model rejects them") {
                    shouldThrow<IllegalArgumentException> { emptyWorkspace("") }
                    shouldThrow<IllegalArgumentException> { IncludedBuildSummary("", "../lib", emptyList()) }
                    shouldThrow<IllegalArgumentException> { IncludedBuildSummary("lib", "", emptyList()) }
                    shouldThrow<IllegalArgumentException> { BuildEdge("", "lib") }
                    shouldThrow<IllegalArgumentException> { BuildEdge("root", "") }
                    shouldThrow<IllegalArgumentException> { EntryPointSummary("", "com.example", EntryPointKind.TEST) }
                    shouldThrow<IllegalArgumentException> { EntryPointSummary("Test", "", EntryPointKind.TEST) }
                    shouldThrow<IllegalArgumentException> { InterfaceSummary("Api", "com.example", -1, false, "main") }
                    shouldThrow<IllegalArgumentException> { InterfaceSummary("Api", "com.example", 0, false, "") }
                    shouldThrow<IllegalArgumentException> {
                        workspaceSourceFile(
                            build = "",
                            project = ":app",
                            sourceSet = "main",
                            content = "source",
                        )
                    }
                    shouldThrow<IllegalArgumentException> {
                        WorkspaceSourceFile(
                            build = "workspace",
                            project = ":app",
                            sourceSet = "main",
                            projectRelativeFile = "/absolute/Shared.kt",
                            content = "source",
                        )
                    }
                }
            }
        }
    })

private fun projectSummary(
    path: String,
    symbolName: String,
    finding: Finding,
    withDependency: Boolean = false,
    hubs: List<HubClass> = emptyList(),
): ProjectSummary {
    val symbol =
        SymbolEntry(
            SymbolName(symbolName),
            SymbolKind.CLASS,
            PackageName("com.example"),
            FilePath("$symbolName.kt"),
            1,
        )
    val dependencies =
        if (withDependency) {
            listOf(
                DependencyEntry(
                    ArtifactGroup("com.example"),
                    ArtifactName("library"),
                    ArtifactVersion("1.0"),
                    "api",
                ),
            )
        } else {
            emptyList()
        }
    return ProjectSummary(
        projectPath = ProjectPath(path),
        symbols = listOf(symbol),
        dependencies = dependencies,
        buildFile = "build.gradle.kts",
        sourceDirs = listOf("src/main/kotlin"),
        subprojects = emptyList(),
        sourceSets = listOf(SourceSetSummary(SourceSetName("main"), listOf(symbol), listOf("src/main/kotlin"))),
        analysis = AnalysisSummary(listOf(finding), hubs, emptyList()),
    )
}

private fun emptyWorkspace(
    name: String,
    sourceFiles: List<WorkspaceSourceFile> = emptyList(),
): WorkspaceReport =
    WorkspaceReport(
        name = name,
        rootProjects = emptyList(),
        includedBuilds = emptyList(),
        buildEdges = emptyList(),
        aggregateAnalysis = null,
        entryPoints = emptyList(),
        interfaces = emptyList(),
        sourceFiles = sourceFiles,
    )

private fun workspaceSourceFile(
    build: String,
    project: String,
    sourceSet: String,
    content: String,
): WorkspaceSourceFile =
    WorkspaceSourceFile(
        build = build,
        project = project,
        sourceSet = sourceSet,
        projectRelativeFile = "src/main/kotlin/sample/Shared.kt",
        content = content,
    )

private fun workspaceSymbol(qualifiedName: String): WorkspaceSymbol =
    WorkspaceSymbol(
        build = "example-workspace",
        project = ":app",
        sourceSet = "main",
        name = qualifiedName.substringAfterLast('.'),
        qualifiedName = qualifiedName,
        kind = SymbolDetailKind.CLASS,
        projectRelativeFile = "src/main/kotlin/${qualifiedName.replace('.', '/')}.kt",
        declarationLine = 1,
    )
