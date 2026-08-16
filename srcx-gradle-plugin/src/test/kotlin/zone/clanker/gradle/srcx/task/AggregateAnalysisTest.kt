package zone.clanker.gradle.srcx.task

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.gradle.testfixtures.ProjectBuilder
import zone.clanker.gradle.srcx.model.AnalysisSummary
import zone.clanker.gradle.srcx.model.Finding
import zone.clanker.gradle.srcx.model.FindingSeverity
import zone.clanker.gradle.srcx.model.HubClass
import zone.clanker.gradle.srcx.model.HubDependentRef
import zone.clanker.gradle.srcx.model.ProjectPath
import zone.clanker.gradle.srcx.model.ProjectSummary
import zone.clanker.gradle.srcx.model.ReferenceEvidence
import zone.clanker.gradle.srcx.model.ReferenceKind
import zone.clanker.gradle.srcx.model.SymbolDetailKind
import zone.clanker.gradle.srcx.model.WorkspaceIndex
import zone.clanker.gradle.srcx.model.WorkspaceReference
import zone.clanker.gradle.srcx.model.WorkspaceRelationship
import zone.clanker.gradle.srcx.model.WorkspaceRelationshipKind
import zone.clanker.gradle.srcx.model.WorkspaceSymbol
import zone.clanker.gradle.srcx.model.WorkspaceSymbolUsage
import zone.clanker.gradle.srcx.scan.ProjectScan

class AggregateAnalysisTest :
    BehaviorSpec({
        fun createTask(): ContextTask {
            val project = ProjectBuilder.builder().build()
            return project.tasks.create("testTask", ContextTask::class.java)
        }

        given("aggregateAnalysis") {
            `when`("all scans have no analysis") {
                val result = createTask().aggregateAnalysis(listOf(scan("root", ":", null)), WorkspaceIndex())

                then("it preserves the nullable aggregate behavior") {
                    result.shouldBeNull()
                }
            }

            `when`("resolved usage crosses project and build boundaries") {
                val target = symbol("api.Contract", "shared", ":api", line = 7)
                val function = symbol("api.factory", "shared", ":api", SymbolDetailKind.FUNCTION)
                val zulu = symbol("app.Zulu", "root", ":app", line = 11)
                val alpha = symbol("client.Alpha", "client", ":client", line = 4)
                val zuluType = relationship(zulu, target, WorkspaceRelationshipKind.TYPE_REFERENCE, 20)
                val duplicateZulu = relationship(zulu, target, WorkspaceRelationshipKind.CONSTRUCTOR, 21)
                val alphaType = relationship(alpha, target, WorkspaceRelationshipKind.TYPE_REFERENCE, 8)
                val imported = relationship(null, target, WorkspaceRelationshipKind.IMPORT, 2)
                val functionUse = relationship(alpha, function, WorkspaceRelationshipKind.CALL, 9)
                val index =
                    WorkspaceIndex(
                        symbols = listOf(target, function, zulu, alpha),
                        relationships = listOf(zuluType, duplicateZulu, alphaType, imported, functionUse),
                        usages =
                            listOf(
                                WorkspaceSymbolUsage(
                                    target,
                                    listOf(zuluType, duplicateZulu, alphaType, imported),
                                    emptyList(),
                                ),
                                WorkspaceSymbolUsage(function, listOf(functionUse), emptyList()),
                            ),
                    )
                val legacyHub = HubClass("LegacyLocalHub", 99, "service", "Legacy.kt", 1)
                val scans =
                    listOf(
                        scan("root", ":app", AnalysisSummary(emptyList(), listOf(legacyHub), emptyList())),
                        scan("client", ":client", AnalysisSummary(emptyList(), emptyList(), emptyList())),
                    )

                val result = createTask().aggregateAnalysis(scans, index)

                then("hubs are rebuilt from distinct inbound source declarations") {
                    result.shouldNotBeNull()
                    result.hubs shouldHaveSize 1
                    result.hubs.single().name shouldBe "Contract"
                    result.hubs.single().dependentCount shouldBe 2
                    result.hubs.single().filePath shouldBe "src/main/kotlin/api/Contract.kt"
                    result.hubs.single().line shouldBe 7
                }

                then("dependent records retain deterministic source provenance") {
                    result.shouldNotBeNull()
                    result.hubs.single().dependents shouldContainExactly
                        listOf(
                            HubDependentRef("Alpha", "src/main/kotlin/client/Alpha.kt", 4),
                            HubDependentRef("Zulu", "src/main/kotlin/app/Zulu.kt", 11),
                        )
                }
            }

            `when`("more cumulative hubs exist than the aggregate cap") {
                val caller = symbol("client.Caller", "root", ":client")
                val targets =
                    (0..30).map { number ->
                        symbol("api.Hub${number.toString().padStart(2, '0')}", "root", ":api")
                    }
                val relationships =
                    targets.mapIndexed { index, target -> relationship(caller, target, line = index + 1) }
                val workspaceIndex =
                    WorkspaceIndex(
                        symbols = targets + caller,
                        relationships = relationships.reversed(),
                        usages =
                            targets
                                .zip(relationships)
                                .map { (target, inbound) -> WorkspaceSymbolUsage(target, listOf(inbound), emptyList()) }
                                .reversed(),
                    )
                val scans = listOf(scan("root", ":", AnalysisSummary(emptyList(), emptyList(), emptyList())))

                val result = createTask().aggregateAnalysis(scans, workspaceIndex)

                then("equal counts are identity ordered before the deterministic cap") {
                    result.shouldNotBeNull()
                    result.hubs.map { it.name } shouldContainExactly
                        (0 until 30).map { number -> "Hub${number.toString().padStart(2, '0')}" }
                }
            }

            `when`("findings and cycles exist across scans") {
                val first = Finding(FindingSeverity.WARNING, "msg1", "fix1")
                val second = Finding(FindingSeverity.WARNING, "msg2", "fix2")
                val duplicate = Finding(FindingSeverity.INFO, "msg1", "another fix")
                val firstCycle = listOf("A", "B", "A")
                val secondCycle = listOf("X", "Y", "X")
                val scans =
                    listOf(
                        scan("root", ":", AnalysisSummary(listOf(first), emptyList(), listOf(firstCycle))),
                        scan(
                            "included",
                            ":lib",
                            AnalysisSummary(listOf(second, duplicate), emptyList(), listOf(secondCycle, firstCycle)),
                        ),
                    )

                val result = createTask().aggregateAnalysis(scans, WorkspaceIndex())

                then("the existing finding and cycle aggregation behavior is retained") {
                    result.shouldNotBeNull()
                    result.findings shouldContainExactly listOf(first, second)
                    result.cycles shouldContainExactly listOf(firstCycle, secondCycle)
                }
            }
        }
    })

private fun scan(
    build: String,
    project: String,
    analysis: AnalysisSummary?,
): ProjectScan {
    val projectPath = ProjectPath(project)
    val summary =
        ProjectSummary(
            projectPath = projectPath,
            symbols = emptyList(),
            dependencies = emptyList(),
            buildFile = "build.gradle.kts",
            sourceDirs = emptyList(),
            subprojects = emptyList(),
            sourceSets = emptyList(),
            analysis = analysis,
        )
    return ProjectScan(build, projectPath, emptyList(), summary)
}

private fun symbol(
    qualifiedName: String,
    build: String,
    project: String,
    kind: SymbolDetailKind = SymbolDetailKind.CLASS,
    line: Int = 1,
): WorkspaceSymbol =
    WorkspaceSymbol(
        build = build,
        project = project,
        sourceSet = "main",
        name = qualifiedName.substringAfterLast('.'),
        qualifiedName = qualifiedName,
        kind = kind,
        projectRelativeFile = "src/main/kotlin/${qualifiedName.replace('.', '/')}.kt",
        declarationLine = line,
    )

private fun relationship(
    source: WorkspaceSymbol?,
    target: WorkspaceSymbol,
    kind: WorkspaceRelationshipKind = WorkspaceRelationshipKind.TYPE_REFERENCE,
    line: Int,
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
            line = line,
            context = target.name,
            evidence = ReferenceEvidence.DIRECT,
        )
    return WorkspaceRelationship(source, target, kind, reference, ReferenceEvidence.DIRECT)
}
