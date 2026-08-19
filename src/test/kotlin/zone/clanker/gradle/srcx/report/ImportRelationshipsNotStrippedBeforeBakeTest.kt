package zone.clanker.gradle.srcx.report

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import zone.clanker.gradle.srcx.model.ImportantSymbol
import zone.clanker.gradle.srcx.model.ImportantSymbolReason
import zone.clanker.gradle.srcx.model.ReferenceEvidence
import zone.clanker.gradle.srcx.model.ReferenceKind
import zone.clanker.gradle.srcx.model.SymbolDetailKind
import zone.clanker.gradle.srcx.model.WorkspaceIndex
import zone.clanker.gradle.srcx.model.WorkspaceReference
import zone.clanker.gradle.srcx.model.WorkspaceRelationship
import zone.clanker.gradle.srcx.model.WorkspaceRelationshipKind
import zone.clanker.gradle.srcx.model.WorkspaceReport
import zone.clanker.gradle.srcx.model.WorkspaceSourceFile
import zone.clanker.gradle.srcx.model.WorkspaceSymbol
import zone.clanker.gradle.srcx.model.WorkspaceSymbolUsage
import java.io.File

/**
 * GraphRenderer must not drop IMPORT relationships before bake.
 * The filter `.filterNot { it.kind == IMPORT }` is gone.
 */
class ImportRelationshipsNotStrippedBeforeBakeTest :
    BehaviorSpec({
        given("a resolved IMPORT relationship in the workspace index") {
            val source = importSymbol("Importer", SymbolDetailKind.CLASS)
            val target = importSymbol("Imported", SymbolDetailKind.INTERFACE)
            val importRelationship =
                WorkspaceRelationship(
                    source = source,
                    target = target,
                    kind = WorkspaceRelationshipKind.IMPORT,
                    sourceEvidence =
                        WorkspaceReference(
                            build = source.build,
                            project = source.project,
                            sourceSet = source.sourceSet,
                            sourceSymbol = source,
                            targetName = target.name,
                            targetQualifiedName = target.qualifiedName,
                            kind = ReferenceKind.IMPORT,
                            projectRelativeFile = source.projectRelativeFile,
                            line = 3,
                            context = "import example.Imported",
                            evidence = ReferenceEvidence.DIRECT,
                        ),
                    evidence = ReferenceEvidence.DIRECT,
                )
            val report =
                WorkspaceReport(
                    name = "import-bake",
                    rootProjects = emptyList(),
                    includedBuilds = emptyList(),
                    buildEdges = emptyList(),
                    aggregateAnalysis = null,
                    entryPoints = emptyList(),
                    interfaces = emptyList(),
                    workspaceIndex =
                        WorkspaceIndex(
                            symbols = listOf(source, target),
                            references = listOf(importRelationship.sourceEvidence),
                            relationships = listOf(importRelationship),
                        ),
                    importantSymbols =
                        listOf(
                            ImportantSymbol(
                                symbol = source,
                                reasons = listOf(ImportantSymbolReason.ENTRY_POINT),
                                score = ImportantSymbolReason.ENTRY_POINT.score,
                                usage = WorkspaceSymbolUsage(source, emptyList(), listOf(importRelationship)),
                            ),
                        ),
                    sourceFiles =
                        listOf(
                            WorkspaceSourceFile(
                                build = target.build,
                                project = target.project,
                                sourceSet = target.sourceSet,
                                projectRelativeFile = target.projectRelativeFile,
                                content = "interface Imported\n",
                            ),
                            WorkspaceSourceFile(
                                build = source.build,
                                project = source.project,
                                sourceSet = source.sourceSet,
                                projectRelativeFile = source.projectRelativeFile,
                                content = "import example.Imported\nclass Importer\n",
                            ),
                        ),
                )

            `when`("the architecture graph is baked") {
                val graph = buildWorkspaceArchitectureGraph(report)
                val json = graph.toJson()

                then("IMPORT survives into baked edges and graph.toJson()") {
                    graph.totalRelationshipRecordCount shouldBe 1
                    graph.availableEdges.map { it.kind } shouldContain WorkspaceRelationshipKind.IMPORT.name
                    graph.edges.map { it.kind } shouldContain WorkspaceRelationshipKind.IMPORT.name
                    graph.availableFileEdges
                        .flatMap { edge -> edge.kindCounts.map { count -> count.kind } } shouldContain
                        WorkspaceRelationshipKind.IMPORT.name
                    json shouldContain "\"kind\":\"${WorkspaceRelationshipKind.IMPORT.name}\""
                    json shouldContain "\"kindLabel\":\"${WorkspaceRelationshipKind.IMPORT.label}\""
                }
            }

            `when`("the GraphRenderer bake entry is inspected") {
                val renderer =
                    File("src/main/kotlin/zone/clanker/gradle/srcx/report/WorkspaceArchitectureGraphRenderer.kt")
                        .readText()

                then("relationships are not filtered by IMPORT before bake") {
                    renderer shouldContain
                        "report.workspaceIndex.relationships"
                    renderer shouldContain
                        ".sortedWith(RELATIONSHIP_COMPARATOR)"
                    renderer shouldNotContain
                        ".filterNot { it.kind == WorkspaceRelationshipKind.IMPORT }"
                }
            }
        }
    })

private fun importSymbol(
    name: String,
    kind: SymbolDetailKind,
): WorkspaceSymbol =
    WorkspaceSymbol(
        build = "root",
        project = ":app",
        sourceSet = "main",
        name = name,
        qualifiedName = "example.$name",
        kind = kind,
        projectRelativeFile = "src/main/kotlin/example/$name.kt",
        declarationLine = 1,
    )
