package zone.clanker.gradle.srcx.analysis

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import zone.clanker.gradle.srcx.model.ImportantSymbolReason
import zone.clanker.gradle.srcx.model.ImportantSymbolSignals
import zone.clanker.gradle.srcx.model.ReferenceEvidence
import zone.clanker.gradle.srcx.model.ReferenceKind
import zone.clanker.gradle.srcx.model.SymbolDetailKind
import zone.clanker.gradle.srcx.model.WorkspaceIndex
import zone.clanker.gradle.srcx.model.WorkspaceReference
import zone.clanker.gradle.srcx.model.WorkspaceRelationship
import zone.clanker.gradle.srcx.model.WorkspaceRelationshipKind
import zone.clanker.gradle.srcx.model.WorkspaceSymbol
import zone.clanker.gradle.srcx.model.WorkspaceSymbolUsage

class ImportantSymbolPolicyTest :
    BehaviorSpec({
        given("cross-build workspace usage") {
            val workspaceApi = symbol("shared.Api", build = "shared", project = ":api")
            val crossBuildCaller = symbol("application.Caller", build = "application", project = ":app")
            val workspaceUsage = usage(workspaceApi, incoming = listOf(relationship(crossBuildCaller, workspaceApi)))
            val localCandidate = symbol("local.Contract", kind = SymbolDetailKind.INTERFACE)
            val localIncoming =
                incomingRelationships(
                    localCandidate,
                    ImportantSymbolPolicy.HIGH_WORKSPACE_INBOUND_THRESHOLD,
                    implementationCount = ImportantSymbolPolicy.MULTIPLE_IMPLEMENTATIONS_THRESHOLD,
                )
            val localUsage =
                usage(
                    localCandidate,
                    incoming = localIncoming,
                    outgoing =
                        outgoingRelationships(
                            localCandidate,
                            ImportantSymbolPolicy.HIGH_WORKSPACE_OUTBOUND_THRESHOLD,
                        ),
                )
            val signals =
                ImportantSymbolSignals(
                    entryPoints = setOf(localCandidate.identity),
                    cycleParticipants = setOf(localCandidate.identity),
                    antiPatternSymbols = setOf(localCandidate.identity),
                )

            `when`("a cap admits only one candidate") {
                val all = ImportantSymbolPolicy.select(indexOf(workspaceUsage, localUsage), signals)
                val selected =
                    ImportantSymbolPolicy.select(
                        indexOf(localUsage, workspaceUsage),
                        signals,
                        maxDocuments = 1,
                    )

                then("cross-build inbound is stronger than all local signals combined") {
                    val workspaceResult = all.first { it.symbol.identity == workspaceApi.identity }
                    val localResult = all.first { it.symbol.identity == localCandidate.identity }
                    (workspaceResult.score > localResult.score) shouldBe true
                    selected.single().symbol.identity shouldBe workspaceApi.identity
                    selected.single().reasons shouldContainExactly listOf(ImportantSymbolReason.CROSS_BUILD_INBOUND)
                }

                then("local zero does not make a workspace-used symbol globally unused") {
                    val result = selected.single()
                    result.usage.localInbound shouldBe 0
                    result.usage.workspaceInbound shouldBe 1
                    result.usage.isWorkspaceUsed shouldBe true
                }
            }
        }

        given("an interface implemented by multiple declarations") {
            val contract = symbol("api.Contract", kind = SymbolDetailKind.INTERFACE)
            val relationships =
                incomingRelationships(
                    contract,
                    ImportantSymbolPolicy.MULTIPLE_IMPLEMENTATIONS_THRESHOLD,
                    implementationCount = ImportantSymbolPolicy.MULTIPLE_IMPLEMENTATIONS_THRESHOLD,
                )

            `when`("the policy evaluates resolved IMPLEMENTS relationships") {
                val result = ImportantSymbolPolicy.select(indexOf(usage(contract, incoming = relationships))).single()

                then("it identifies the interface purpose without name inference") {
                    result.reasons shouldContainExactly listOf(ImportantSymbolReason.MULTIPLE_IMPLEMENTATIONS)
                    result.score shouldBe ImportantSymbolPolicy.MULTIPLE_IMPLEMENTATIONS_SCORE
                }
            }
        }

        given("exact externally supplied identities") {
            val selected = symbol("duplicate.App", build = "root", project = ":app")
            val sameNameElsewhere = symbol("duplicate.App", build = "included", project = ":app")
            val signals =
                ImportantSymbolSignals(
                    entryPoints = setOf(selected.identity),
                    cycleParticipants = setOf(selected.identity),
                    antiPatternSymbols = setOf(selected.identity),
                )

            `when`("entry-point, cycle, and anti-pattern evidence targets one scoped declaration") {
                val result =
                    ImportantSymbolPolicy.select(
                        indexOf(usage(sameNameElsewhere), usage(selected)),
                        signals,
                    )

                then("only the exact identity receives those reasons") {
                    result.map { it.symbol.identity } shouldContainExactly listOf(selected.identity)
                    result.single().reasons shouldContainExactly
                        listOf(
                            ImportantSymbolReason.ENTRY_POINT,
                            ImportantSymbolReason.DEPENDENCY_CYCLE,
                            ImportantSymbolReason.ANTI_PATTERN_INVOLVEMENT,
                        )
                }
            }
        }

        given("production and test source-set candidates") {
            val production = symbol("sample.App")
            val test = symbol("sample.AppIntegrationTest", sourceSet = "integrationTest")
            val signals = ImportantSymbolSignals(entryPoints = setOf(production.identity, test.identity))
            val index = indexOf(usage(test), usage(production))

            `when`("the default source-set policy is used") {
                then("test declarations are excluded") {
                    ImportantSymbolPolicy.select(index, signals).map { it.symbol.identity } shouldContainExactly
                        listOf(production.identity)
                }
            }

            `when`("test source sets are explicitly included") {
                then("both exact candidates are retained") {
                    ImportantSymbolPolicy
                        .select(index, signals, includeTestSourceSets = true)
                        .map { it.symbol.identity }
                        .toSet() shouldBe setOf(production.identity, test.identity)
                }
            }
        }

        given("more candidates than the document budget") {
            val symbols =
                (0..ImportantSymbolPolicy.DEFAULT_MAX_DOCUMENTS).map { number ->
                    symbol("candidate.Symbol${number.toString().padStart(3, '0')}")
                }
            val signals = ImportantSymbolSignals(entryPoints = symbols.mapTo(mutableSetOf()) { it.identity })
            val index = indexOf(*symbols.map(::usage).toTypedArray())

            `when`("the default and an explicit smaller cap are applied") {
                val defaultResult = ImportantSymbolPolicy.select(index, signals)
                val smallerResult = ImportantSymbolPolicy.select(index, signals, maxDocuments = 2)

                then("output is bounded by the single documented policy limit") {
                    defaultResult shouldHaveSize ImportantSymbolPolicy.DEFAULT_MAX_DOCUMENTS
                    smallerResult shouldHaveSize 2
                }
            }
        }

        given("equal-scoring declarations") {
            val first = symbol("sample.Alpha")
            val second = symbol("sample.Zulu")
            val signals = ImportantSymbolSignals(entryPoints = setOf(second.identity, first.identity))

            `when`("input arrives in reverse identity order") {
                val result = ImportantSymbolPolicy.select(indexOf(usage(second), usage(first)), signals)

                then("ties are ordered by scoped identity") {
                    result.map { it.symbol.identity } shouldContainExactly listOf(first.identity, second.identity)
                }
            }
        }

        given("equivalent indexes with reversed collections") {
            val hub = symbol("sample.Hub")
            val entryPoint = symbol("sample.Main")
            val hubUsage =
                usage(
                    hub,
                    incoming =
                        incomingRelationships(
                            hub,
                            ImportantSymbolPolicy.HIGH_WORKSPACE_INBOUND_THRESHOLD,
                        ),
                )
            val entryUsage = usage(entryPoint)
            val signals = ImportantSymbolSignals(entryPoints = setOf(entryPoint.identity))
            val index = indexOf(hubUsage, entryUsage)
            val reversed =
                index.copy(
                    symbols = index.symbols.reversed(),
                    references = index.references.reversed(),
                    relationships = index.relationships.reversed(),
                    usages = index.usages.reversed(),
                )

            `when`("the policy selects from each index") {
                then("the complete result is identical") {
                    val expected = ImportantSymbolPolicy.select(index, signals)
                    ImportantSymbolPolicy.select(reversed, signals) shouldBe expected
                }
            }
        }

        given("named score and threshold constants") {
            val highCounts = symbol("sample.HighCounts")
            val highUsage =
                usage(
                    highCounts,
                    incoming =
                        incomingRelationships(
                            highCounts,
                            ImportantSymbolPolicy.HIGH_WORKSPACE_INBOUND_THRESHOLD,
                        ),
                    outgoing =
                        outgoingRelationships(
                            highCounts,
                            ImportantSymbolPolicy.HIGH_WORKSPACE_OUTBOUND_THRESHOLD,
                        ),
                )
            val connected = symbol("sample.Connected")
            val inboundBelowHigh = ImportantSymbolPolicy.HIGH_WORKSPACE_INBOUND_THRESHOLD - 1
            val connectivityUsage =
                usage(
                    connected,
                    incoming = incomingRelationships(connected, inboundBelowHigh),
                    outgoing =
                        outgoingRelationships(
                            connected,
                            ImportantSymbolPolicy.UNUSUAL_CONNECTIVITY_THRESHOLD - inboundBelowHigh,
                        ),
                )

            `when`("counts meet the policy thresholds") {
                val result = ImportantSymbolPolicy.select(indexOf(highUsage, connectivityUsage))
                val highResult = result.first { it.symbol.identity == highCounts.identity }
                val connectivityResult = result.first { it.symbol.identity == connected.identity }

                then("scores are the sum of named reason constants") {
                    highResult.reasons shouldContainExactly
                        listOf(
                            ImportantSymbolReason.HIGH_WORKSPACE_INBOUND,
                            ImportantSymbolReason.HIGH_WORKSPACE_OUTBOUND,
                            ImportantSymbolReason.UNUSUAL_CONNECTIVITY,
                        )
                    highResult.score shouldBe
                        ImportantSymbolPolicy.HIGH_WORKSPACE_INBOUND_SCORE +
                        ImportantSymbolPolicy.HIGH_WORKSPACE_OUTBOUND_SCORE +
                        ImportantSymbolPolicy.UNUSUAL_CONNECTIVITY_SCORE
                    connectivityResult.reasons shouldContainExactly
                        listOf(ImportantSymbolReason.UNUSUAL_CONNECTIVITY)
                    connectivityResult.score shouldBe ImportantSymbolPolicy.UNUSUAL_CONNECTIVITY_SCORE
                }

                then("the cross-build constant dominates all supported local reasons") {
                    val allLocalScores =
                        ImportantSymbolPolicy.HIGH_WORKSPACE_INBOUND_SCORE +
                            ImportantSymbolPolicy.MULTIPLE_IMPLEMENTATIONS_SCORE +
                            ImportantSymbolPolicy.ENTRY_POINT_SCORE +
                            ImportantSymbolPolicy.DEPENDENCY_CYCLE_SCORE +
                            ImportantSymbolPolicy.HIGH_WORKSPACE_OUTBOUND_SCORE +
                            ImportantSymbolPolicy.UNUSUAL_CONNECTIVITY_SCORE +
                            ImportantSymbolPolicy.ANTI_PATTERN_INVOLVEMENT_SCORE
                    (ImportantSymbolPolicy.CROSS_BUILD_INBOUND_SCORE > allLocalScores) shouldBe true
                }
            }
        }

        given("import-only relationships") {
            val imported = symbol("sample.Imported")
            val imports =
                List(ImportantSymbolPolicy.UNUSUAL_CONNECTIVITY_THRESHOLD) { line ->
                    relationship(null, imported, WorkspaceRelationshipKind.IMPORT, line + 1)
                }

            `when`("imports appear in a directly constructed usage") {
                then("they do not contribute to inbound or connectivity selection") {
                    ImportantSymbolPolicy.select(indexOf(usage(imported, incoming = imports))).shouldBeEmpty()
                }
            }
        }
    })

private fun symbol(
    qualifiedName: String,
    build: String = "root",
    project: String = ":app",
    sourceSet: String = "main",
    kind: SymbolDetailKind = SymbolDetailKind.CLASS,
): WorkspaceSymbol =
    WorkspaceSymbol(
        build = build,
        project = project,
        sourceSet = sourceSet,
        name = qualifiedName.substringAfterLast('.'),
        qualifiedName = qualifiedName,
        kind = kind,
        projectRelativeFile = "src/$sourceSet/kotlin/${qualifiedName.replace('.', '/')}.kt",
        declarationLine = 1,
    )

private fun usage(
    symbol: WorkspaceSymbol,
    incoming: List<WorkspaceRelationship> = emptyList(),
    outgoing: List<WorkspaceRelationship> = emptyList(),
): WorkspaceSymbolUsage = WorkspaceSymbolUsage(symbol, incoming, outgoing)

private fun incomingRelationships(
    target: WorkspaceSymbol,
    count: Int,
    implementationCount: Int = 0,
): List<WorkspaceRelationship> =
    List(count) { index ->
        val source = symbol("fixture.Incoming$index", build = target.build, project = target.project)
        val kind =
            if (index < implementationCount) {
                WorkspaceRelationshipKind.IMPLEMENTS
            } else {
                WorkspaceRelationshipKind.TYPE_REFERENCE
            }
        relationship(source, target, kind, index + 1)
    }

private fun outgoingRelationships(
    source: WorkspaceSymbol,
    count: Int,
): List<WorkspaceRelationship> =
    List(count) { index ->
        val target = symbol("fixture.Outgoing$index", build = source.build, project = source.project)
        relationship(source, target, WorkspaceRelationshipKind.TYPE_REFERENCE, index + 1)
    }

private fun relationship(
    source: WorkspaceSymbol?,
    target: WorkspaceSymbol,
    kind: WorkspaceRelationshipKind = WorkspaceRelationshipKind.TYPE_REFERENCE,
    line: Int = 1,
): WorkspaceRelationship {
    val referenceKind =
        when (kind) {
            WorkspaceRelationshipKind.IMPORT -> ReferenceKind.IMPORT
            WorkspaceRelationshipKind.IMPLEMENTS,
            WorkspaceRelationshipKind.EXTENDS,
            -> ReferenceKind.SUPERTYPE
            else -> ReferenceKind.TYPE_REF
        }
    val sourceOwner = source ?: target
    val reference =
        WorkspaceReference(
            build = sourceOwner.build,
            project = sourceOwner.project,
            sourceSet = sourceOwner.sourceSet,
            sourceSymbol = source,
            targetName = target.name,
            targetQualifiedName = target.qualifiedName,
            kind = referenceKind,
            projectRelativeFile = sourceOwner.projectRelativeFile,
            line = line,
            context = target.name,
            evidence = ReferenceEvidence.DIRECT,
        )
    return WorkspaceRelationship(source, target, kind, reference, ReferenceEvidence.DIRECT)
}

private fun indexOf(vararg usages: WorkspaceSymbolUsage): WorkspaceIndex {
    val relationships = usages.flatMap { it.incoming + it.outgoing }.distinct()
    return WorkspaceIndex(
        symbols = usages.map { it.symbol },
        references = relationships.map { it.sourceEvidence },
        relationships = relationships,
        usages = usages.toList(),
    )
}
