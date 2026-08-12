@file:Suppress("LongMethod", "LongParameterList", "TooManyFunctions")

package zone.clanker.gradle.srcx.report

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.file.shouldExist
import io.kotest.matchers.file.shouldNotExist
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
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
import zone.clanker.gradle.srcx.model.WorkspaceSymbol
import zone.clanker.gradle.srcx.model.WorkspaceSymbolUsage
import java.io.File

class WorkspaceRelationshipsRendererTest :
    BehaviorSpec({
        given("important symbols with local, workspace, and cross-build relationships") {
            val report = relationshipReport()

            `when`("the relationship documents are rendered") {
                val rendered = WorkspaceRelationshipsRenderer().render(report)
                val page = rendered.pages.single()

                then("the index is compact, ranked, and linked") {
                    rendered.indexMarkdown shouldContain
                        "| [sample.Api](${page.fileName}) | interface | " +
                        "<code>workspace</code> / <code>:api</code> / <code>main</code> | 1140 | 1 | 3 | 1 | observed |"
                    rendered.indexMarkdown shouldContain "## Evidence model and limits"
                    rendered.indexMarkdown shouldContain "Import facts do not count as usage"
                    rendered.indexMarkdown shouldContain "not proof of semantic unusedness"
                }

                then("the page has exact identity, importance, and cumulative counts") {
                    page.markdown shouldStartWith
                        """
                        # sample.Api

                        ## Identity

                        | Field | Value |
                        |-------|-------|
                        | Workspace identity | <code>workspace:::api::main::sample.Api@src/main/kotlin/sample/Api.kt:7</code> |
                        | Kind | interface |
                        | Build | <code>workspace</code> |
                        | Project | <code>:api</code> |
                        | Source set | <code>main</code> |
                        | Project-relative file | <code>src/main/kotlin/sample/Api.kt</code> |
                        | Declaration line | 7 |

                        ## Importance

                        | Score | Reasons |
                        |------:|---------|
                        | 1140 | Used from another build; Entry point |

                        ## Usage

                        | Metric | Value |
                        |--------|------:|
                        | Local inbound | 1 |
                        | Workspace inbound | 3 |
                        | Cross-build inbound | 1 |
                        | Workspace-used status | yes - resolved workspace usage observed |
                        """.trimIndent()
                }

                then("external consumers, dependencies, kinds, and source evidence remain explicit") {
                    page.markdown shouldContain "## Who uses it"
                    page.markdown shouldContain "sample.ExternalConsumer"
                    page.markdown shouldContain "implements"
                    page.markdown shouldContain "yes (<code>included</code> to <code>workspace</code>)"
                    page.markdown shouldContain "<code>included</code> | <code>:client</code>"
                    page.markdown shouldContain "<code>src/main/kotlin/sample/ExternalConsumer.kt</code> | 40"
                    page.markdown shouldContain "class ExternalConsumer : Api"
                    page.markdown shouldContain "## What it uses"
                    page.markdown shouldContain "sample.Repository"
                    page.markdown shouldContain "constructs"
                    page.markdown shouldContain "## Cross-build edges"
                    page.markdown shouldContain "| inbound |"
                    page.markdown shouldContain "| outbound |"
                }

                then("all evidence strengths are labeled and imports do not inflate or appear as usage") {
                    page.markdown shouldContain "| DIRECT |"
                    page.markdown shouldContain "| DERIVED |"
                    page.markdown shouldContain "| HEURISTIC |"
                    page.markdown shouldNotContain "import sample.Api"
                }
            }
        }

        given("the same fully-qualified symbol name in different workspace scopes") {
            val first = workspaceSymbol("first-build", ":one", "sample.Service")
            val second = workspaceSymbol("second-build", ":two", "sample.Service")
            val report = reportFor(listOf(important(first, 20), important(second, 10)))

            `when`("page filenames are assigned") {
                val pages = WorkspaceRelationshipsRenderer().render(report).pages

                then("readable slugs have distinct deterministic scope hashes") {
                    pages.map { it.fileName }.distinct().size shouldBe 2
                    pages.forEach { page ->
                        Regex("sample-service-[0-9a-f]{12}\\.md").matches(page.fileName) shouldBe true
                        page.fileName.contains('/') shouldBe false
                        page.fileName.contains(':') shouldBe false
                    }
                }
            }
        }

        given("equivalent reports with reversed symbol and relationship inputs") {
            val original = relationshipReportWithTwoImportantSymbols()
            val reversed =
                original.copy(
                    importantSymbols = original.importantSymbols.reversed(),
                    workspaceIndex =
                        original.workspaceIndex.copy(
                            symbols = original.workspaceIndex.symbols.reversed(),
                            references = original.workspaceIndex.references.reversed(),
                            relationships = original.workspaceIndex.relationships.reversed(),
                            usages = original.workspaceIndex.usages.reversed(),
                        ),
                )

            `when`("both reports are rendered") {
                then("the complete rendered model is byte-for-byte deterministic") {
                    WorkspaceRelationshipsRenderer().render(reversed) shouldBe
                        WorkspaceRelationshipsRenderer().render(original)
                }
            }
        }

        given("more important symbols than root context should enumerate") {
            val symbols =
                (0..WorkspaceRelationshipsRenderer.CONTEXT_LINK_LIMIT + 2).map { index ->
                    val symbol = workspaceSymbol("workspace", ":app", "sample.Symbol$index", line = index + 1)
                    important(symbol, score = index + 1)
                }

            `when`("the compact scout section is rendered") {
                val section = WorkspaceRelationshipsRenderer().render(reportFor(symbols)).contextSectionMarkdown
                val directLinks = section.lineSequence().filter { it.startsWith("- [") }.toList()

                then("only the ranked top subset receives direct links") {
                    directLinks.size shouldBe WorkspaceRelationshipsRenderer.CONTEXT_LINK_LIMIT
                    directLinks.first() shouldContain "sample.Symbol10"
                    directLinks.none { it.contains("sample.Symbol0]") } shouldBe true
                    section shouldContain "[Browse the complete relationship index](relationships/index.md)"
                }
            }
        }

        given("an empty important-symbol selection") {
            `when`("the report is rendered") {
                val rendered = WorkspaceRelationshipsRenderer().render(emptyWorkspaceReport())

                then("the exact index explains the empty state and evidence limits") {
                    rendered.indexMarkdown shouldBe emptyIndexMarkdown()
                }

                then("no symbol shards are created in the rendered model") {
                    rendered.pages shouldContainExactly emptyList()
                    rendered.contextSectionMarkdown shouldBe
                        """
                        ## Important workspace relationships

                        [Browse the complete relationship index](relationships/index.md).

                        No important workspace symbols were selected.

                        """.trimIndent() + "\n"
                }
            }
        }

        given("a previously generated relationship subtree") {
            val outputDirectory = temporaryDirectory()
            val renderer = WorkspaceRelationshipsRenderer()
            val oldSymbol = workspaceSymbol("workspace", ":app", "sample.Old")
            val initial = renderer.render(reportFor(listOf(important(oldSymbol, 1))))
            ReportWriter.writeWorkspaceRelationshipReports(outputDirectory, initial)
            val oldPage = File(outputDirectory, "relationships/${initial.pages.single().fileName}")
            val stalePage = File(outputDirectory, "relationships/stale-shard.md").apply { writeText("stale") }

            `when`("an empty report replaces it") {
                val replacement = renderer.render(emptyWorkspaceReport())
                ReportWriter.writeWorkspaceRelationshipReports(outputDirectory, replacement)
                val relationshipDirectory = File(outputDirectory, "relationships")

                then("the old and stale shards are removed and the new index remains") {
                    oldPage.shouldNotExist()
                    stalePage.shouldNotExist()
                    File(relationshipDirectory, "index.md").shouldExist()
                    relationshipDirectory
                        .listFiles()
                        .orEmpty()
                        .map { it.name }
                        .sorted() shouldContainExactly
                        listOf("index.md")
                }
            }
        }
    })

private fun relationshipReport(): WorkspaceReport {
    val api = workspaceSymbol("workspace", ":api", "sample.Api", SymbolDetailKind.INTERFACE, line = 7)
    val localConsumer = workspaceSymbol("workspace", ":api", "sample.LocalConsumer")
    val projectConsumer = workspaceSymbol("workspace", ":app", "sample.ProjectConsumer")
    val externalConsumer = workspaceSymbol("included", ":client", "sample.ExternalConsumer")
    val repository = workspaceSymbol("data-build", ":store", "sample.Repository")
    val relationships =
        listOf(
            relationship(
                localConsumer,
                api,
                WorkspaceRelationshipKind.CALL,
                ReferenceEvidence.DIRECT,
                line = 20,
                context = "api.run()",
            ),
            relationship(
                projectConsumer,
                api,
                WorkspaceRelationshipKind.TYPE_REFERENCE,
                ReferenceEvidence.DERIVED,
                line = 30,
                context = "val api: Api",
            ),
            relationship(
                externalConsumer,
                api,
                WorkspaceRelationshipKind.IMPLEMENTS,
                ReferenceEvidence.HEURISTIC,
                line = 40,
                context = "class ExternalConsumer : Api",
            ),
            relationship(
                api,
                repository,
                WorkspaceRelationshipKind.CONSTRUCTOR,
                ReferenceEvidence.DIRECT,
                line = 12,
                context = "Repository()",
            ),
            relationship(
                null,
                api,
                WorkspaceRelationshipKind.IMPORT,
                ReferenceEvidence.DIRECT,
                line = 3,
                context = "import sample.Api",
            ),
        )
    val usage =
        WorkspaceSymbolUsage(
            symbol = api,
            incoming = relationships.filter { it.target == api },
            outgoing = relationships.filter { it.source == api },
        )
    val selected =
        ImportantSymbol(
            api,
            listOf(ImportantSymbolReason.ENTRY_POINT, ImportantSymbolReason.CROSS_BUILD_INBOUND),
            1_140,
            usage,
        )
    return workspaceReport(
        symbols = listOf(api, localConsumer, projectConsumer, externalConsumer, repository),
        relationships = relationships,
        importantSymbols = listOf(selected),
    )
}

private fun relationshipReportWithTwoImportantSymbols(): WorkspaceReport {
    val report = relationshipReport()
    val repository = report.workspaceIndex.symbols.single { it.qualifiedName == "sample.Repository" }
    return report.copy(importantSymbols = report.importantSymbols + important(repository, 10))
}

private fun reportFor(importantSymbols: List<ImportantSymbol>): WorkspaceReport =
    workspaceReport(
        symbols = importantSymbols.map { it.symbol },
        relationships = emptyList(),
        importantSymbols = importantSymbols,
    )

private fun workspaceReport(
    symbols: List<WorkspaceSymbol>,
    relationships: List<WorkspaceRelationship>,
    importantSymbols: List<ImportantSymbol>,
): WorkspaceReport =
    WorkspaceReport(
        name = "workspace",
        rootProjects = emptyList(),
        includedBuilds = emptyList(),
        buildEdges = emptyList(),
        aggregateAnalysis = null,
        entryPoints = emptyList(),
        interfaces = emptyList(),
        workspaceIndex =
            WorkspaceIndex(
                symbols = symbols,
                relationships = relationships,
            ),
        importantSymbols = importantSymbols,
    )

private fun emptyWorkspaceReport(): WorkspaceReport =
    WorkspaceReport(
        name = "empty-workspace",
        rootProjects = emptyList(),
        includedBuilds = emptyList(),
        buildEdges = emptyList(),
        aggregateAnalysis = null,
        entryPoints = emptyList(),
        interfaces = emptyList(),
    )

private fun workspaceSymbol(
    build: String,
    project: String,
    qualifiedName: String,
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

private fun important(
    symbol: WorkspaceSymbol,
    score: Int,
): ImportantSymbol =
    ImportantSymbol(
        symbol = symbol,
        reasons = listOf(ImportantSymbolReason.ENTRY_POINT),
        score = score,
        usage = WorkspaceSymbolUsage(symbol, emptyList(), emptyList()),
    )

private fun relationship(
    source: WorkspaceSymbol?,
    target: WorkspaceSymbol,
    kind: WorkspaceRelationshipKind,
    evidence: ReferenceEvidence,
    line: Int,
    context: String,
): WorkspaceRelationship {
    val owner = source ?: target
    val reference =
        WorkspaceReference(
            build = owner.build,
            project = owner.project,
            sourceSet = owner.sourceSet,
            sourceSymbol = source,
            targetName = target.name,
            targetQualifiedName = target.qualifiedName,
            kind = referenceKind(kind),
            projectRelativeFile = owner.projectRelativeFile,
            line = line,
            context = context,
            evidence = evidence,
        )
    return WorkspaceRelationship(source, target, kind, reference, evidence)
}

private fun referenceKind(kind: WorkspaceRelationshipKind): ReferenceKind =
    when (kind) {
        WorkspaceRelationshipKind.IMPORT -> ReferenceKind.IMPORT
        WorkspaceRelationshipKind.CALL -> ReferenceKind.CALL
        WorkspaceRelationshipKind.CONSTRUCTOR -> ReferenceKind.CONSTRUCTOR
        WorkspaceRelationshipKind.NAME_REFERENCE -> ReferenceKind.NAME_REF
        WorkspaceRelationshipKind.TYPE_REFERENCE -> ReferenceKind.TYPE_REF
        WorkspaceRelationshipKind.PROPERTY_TYPE -> ReferenceKind.PROPERTY_TYPE
        WorkspaceRelationshipKind.PARAMETER_TYPE -> ReferenceKind.PARAMETER_TYPE
        WorkspaceRelationshipKind.RETURN_TYPE -> ReferenceKind.RETURN_TYPE
        WorkspaceRelationshipKind.EXTENDS,
        WorkspaceRelationshipKind.IMPLEMENTS,
        -> ReferenceKind.SUPERTYPE
    }

private fun emptyIndexMarkdown(): String =
    listOf(
        "# Important workspace relationships",
        "",
        "Important symbols selected for empty-workspace. " +
            "Rows are ordered by descending importance score, then stable workspace identity.",
        "",
        "No important symbols were selected for this workspace.",
        "",
        "## Evidence model and limits",
        "",
        "- **DIRECT**: the extractor supplied direct source evidence for the resolved target.",
        "- **DERIVED**: the target was resolved from deterministic source facts, such as an unambiguous import.",
        "- **HEURISTIC**: the relationship relies on approximate name or syntax evidence and should be reviewed.",
        "- Import facts do not count as usage and are excluded from inbound, outbound, and workspace-used results.",
        "- Counts cover resolved, observed workspace relationships only. " +
            "No resolved workspace usage is not proof of semantic unusedness.",
        "- Local inbound means the same build and project. Workspace inbound includes all projects and builds; " +
            "cross-build inbound has a consumer in another build.",
    ).joinToString("\n", postfix = "\n")

private fun temporaryDirectory(): File =
    File.createTempFile("srcx-relationships", "").apply {
        check(delete())
        check(mkdirs())
        deleteOnExit()
    }
