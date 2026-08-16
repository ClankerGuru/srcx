package zone.clanker.gradle.srcx.snapshot

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import zone.clanker.gradle.srcx.model.DeclarationRange
import zone.clanker.gradle.srcx.model.ReferenceEvidence
import zone.clanker.gradle.srcx.model.SymbolDetailKind
import zone.clanker.gradle.srcx.model.WorkspaceRelationship
import zone.clanker.gradle.srcx.model.WorkspaceRelationshipKind
import zone.clanker.gradle.srcx.model.WorkspaceSymbol
import zone.clanker.report.model.DeclarationSemantic
import zone.clanker.report.model.RelationshipKind
import zone.clanker.report.model.SourceLanguage
import zone.clanker.report.model.WorkspaceSnapshotJson

class WorkspaceSnapshotMapperTest :
    BehaviorSpec({
        given("a complete SRCX workspace report") {
            val report = completeWorkspaceReport()

            `when`("the producer maps it to the public snapshot") {
                val snapshot = WorkspaceSnapshotMapper.map(report, "workspace:fixture")

                then("the public contract retains structure, source, graph, and analysis evidence") {
                    snapshot.workspace.name shouldBe "fixture"
                    snapshot.builds.size shouldBe 2
                    snapshot.buildEdges.size shouldBe 1
                    snapshot.projects.size shouldBe 2
                    snapshot.projectDependencies.size shouldBe 1
                    snapshot.files.size shouldBe report.sourceFiles.size
                    snapshot.symbols.size shouldBe report.workspaceIndex.symbols.size
                    snapshot.references.size shouldBe report.workspaceIndex.references.size
                    snapshot.relationships.size shouldBe report.workspaceIndex.relationships.size
                    snapshot.relationshipCycles.size shouldBe 1
                    snapshot.projectAnalyses.size shouldBe 1
                    snapshot.aggregateAnalysisPresent shouldBe true
                    snapshot.aggregateFindings.size shouldBe 1
                    snapshot.aggregateHubs.size shouldBe 1
                    snapshot.aggregateNamedCycles.size shouldBe 1
                    snapshot.entryPoints.size shouldBe 1
                    snapshot.interfaces.size shouldBe 1
                    snapshot.importantSymbols.size shouldBe 1
                    snapshot.dependencyInjection shouldBe null
                }

                then("important-symbol usage retains exact import and non-import incident evidence") {
                    val important = snapshot.importantSymbols.single()
                    val relationshipsById = snapshot.relationships.associateBy { it.id }
                    important.usage.incomingRelationshipIds
                        .map { relationshipId -> relationshipsById.getValue(relationshipId).kind }
                        .toSet() shouldBe setOf(RelationshipKind.IMPORT, RelationshipKind.TYPE_REFERENCE)
                    important.usage.outgoingRelationshipIds
                        .map { relationshipId -> relationshipsById.getValue(relationshipId).kind } shouldContainExactly
                        listOf(RelationshipKind.CONSTRUCTOR)
                    important.usage.localInboundCount shouldBe 1
                    important.usage.workspaceInboundCount shouldBe 1
                    important.usage.crossBuildInboundCount shouldBe 0
                    important.usage.isWorkspaceUsed shouldBe true
                }

                then("observed relationship cycles retain directed relationship evidence") {
                    val cycle = snapshot.relationshipCycles.single()
                    val relationshipsById = snapshot.relationships.associateBy { it.id }
                    cycle.symbolIds.size shouldBe 4
                    cycle.symbolIds.first() shouldBe cycle.symbolIds.last()
                    cycle.relationshipIds.zip(cycle.symbolIds.zipWithNext()).all { (relationshipId, step) ->
                        relationshipsById.getValue(relationshipId).let { relationship ->
                            relationship.sourceSymbolId == step.first && relationship.targetSymbolId == step.second
                        }
                    } shouldBe true
                }

                then("unresolved reference context remains independently inspectable") {
                    val unresolved = snapshot.references.single { it.targetName == "MissingPolicy" }
                    unresolved.targetQualifiedName shouldBe null
                    unresolved.context shouldBe "class Consumer"
                    snapshot.relationships.none { it.referenceId == unresolved.id } shouldBe true
                }

                then("source languages and declaration semantics stay explicit") {
                    snapshot.files.map { it.language }.toSet() shouldBe
                        setOf(
                            SourceLanguage.JAVA,
                            SourceLanguage.KOTLIN,
                            SourceLanguage.KOTLIN_SCRIPT,
                            SourceLanguage.GROOVY,
                            SourceLanguage.JSON,
                            SourceLanguage.YAML,
                            SourceLanguage.TOML,
                            SourceLanguage.MARKDOWN,
                        )
                    snapshot.symbols
                        .single { it.name == "Contract" }
                        .declarationSemantic shouldBe DeclarationSemantic.INTERFACE
                }

                then("multi-member directed cycles retain their closed route") {
                    val analysis = snapshot.projectAnalyses.single()
                    analysis.cycles
                        .single()
                        .componentIds.size shouldBe 3
                    analysis.cycles
                        .single()
                        .componentIds
                        .first() shouldBe
                        analysis.cycles
                            .single()
                            .componentIds
                            .last()
                    analysis.findings.single().componentCycle shouldContainExactly
                        listOf("fixture.Consumer", "fixture.Helper", "fixture.Consumer")
                }
            }

            `when`("the producer captures a member owner and exact declaration ranges") {
                val sourceFile =
                    report.sourceFiles.single { source ->
                        source.projectRelativeFile.endsWith("fixture/Consumer.kt")
                    }
                val source = sourceFile.content
                val ownerStart = source.indexOf("class Consumer")
                val ownerEnd = source.lastIndexOf('}') + 1
                val memberStart = source.indexOf("fun create")
                val memberEnd = source.indexOf('\n', memberStart)
                val originalOwner = report.workspaceIndex.symbols.single { it.qualifiedName == "fixture.Consumer" }
                val owner =
                    originalOwner.copy(
                        declarationRange = DeclarationRange(ownerStart, ownerEnd),
                    )
                val member =
                    WorkspaceSymbol(
                        build = owner.build,
                        project = owner.project,
                        sourceSet = owner.sourceSet,
                        name = "Consumer.create",
                        qualifiedName = "fixture.Consumer.create",
                        kind = SymbolDetailKind.FUNCTION,
                        projectRelativeFile = owner.projectRelativeFile,
                        declarationLine = 3,
                        signature = "fun fixture.Consumer.create():library.Implementation",
                        ownerQualifiedName = owner.qualifiedName,
                        declarationRange = DeclarationRange(memberStart, memberEnd),
                    )
                val enrichedReport =
                    report.copy(
                        workspaceIndex =
                            report.workspaceIndex.copy(
                                symbols =
                                    report.workspaceIndex.symbols.map { symbol ->
                                        if (symbol.identity == originalOwner.identity) owner else symbol
                                    } + member,
                            ),
                    )
                val snapshot = WorkspaceSnapshotMapper.map(enrichedReport, "workspace:fixture")

                then("the public member links to its exact owner and complete offsets") {
                    val ownerSnapshot = snapshot.symbols.single { it.qualifiedName == owner.qualifiedName }
                    val memberSnapshot = snapshot.symbols.single { it.qualifiedName == member.qualifiedName }
                    memberSnapshot.ownerSymbolId shouldBe ownerSnapshot.id
                    memberSnapshot.signature shouldBe member.signature
                    memberSnapshot.declarationRange?.startOffset shouldBe memberStart
                    memberSnapshot.declarationRange?.endOffsetExclusive shouldBe memberEnd
                }

                then("a same-name declaration outside the owner range is not assigned a false owner") {
                    val truncatedOwner = owner.copy(declarationRange = DeclarationRange(ownerStart, memberStart))
                    val invalidOwnershipReport =
                        enrichedReport.copy(
                            workspaceIndex =
                                enrichedReport.workspaceIndex.copy(
                                    symbols =
                                        enrichedReport.workspaceIndex.symbols.map { symbol ->
                                            if (symbol.identity == owner.identity) truncatedOwner else symbol
                                        },
                                ),
                        )

                    val invalidOwnershipSnapshot =
                        WorkspaceSnapshotMapper.map(invalidOwnershipReport, "workspace:fixture")

                    invalidOwnershipSnapshot.symbols
                        .single { it.qualifiedName == member.qualifiedName }
                        .ownerSymbolId shouldBe null
                }

                then("line movement leaves the external semantic ID unchanged") {
                    symbolId(owner.copy(declarationLine = 99, declarationRange = null)) shouldBe symbolId(owner)
                }

                then("overload signatures remain distinct external identities") {
                    val stringOverload = member.copy(signature = "fun fixture.Consumer.create(kotlin.String)")
                    val intOverload = member.copy(signature = "fun fixture.Consumer.create(kotlin.Int)")
                    (symbolId(stringOverload) == symbolId(intOverload)) shouldBe false
                }
            }

            `when`("all unordered producer collections arrive in reverse order") {
                val expected = WorkspaceSnapshotMapper.map(report, "workspace:fixture")
                val actual = WorkspaceSnapshotMapper.map(report.reorderedForSnapshotTest(), "workspace:fixture")

                then("canonical serialization is byte-for-byte deterministic") {
                    actual shouldBe expected
                    WorkspaceSnapshotJson.encode(actual) shouldBe WorkspaceSnapshotJson.encode(expected)
                }
            }

            `when`("source references contain blank target fields") {
                val originalImport = report.workspaceIndex.references.single { it.targetName == "Contract" }
                val originalUnresolved = report.workspaceIndex.references.single { it.targetName == "MissingPolicy" }
                val qualifiedOnlyImport = originalImport.copy(targetName = "")
                val blankQualifiedUnresolved = originalUnresolved.copy(targetQualifiedName = " \t")
                val unaddressable =
                    originalUnresolved.copy(
                        targetName = " \t",
                        targetQualifiedName = null,
                        context = "incomplete PSI reference",
                    )
                val consumer = report.workspaceIndex.symbols.single { it.qualifiedName == "fixture.Consumer" }
                val contract = report.workspaceIndex.symbols.single { it.qualifiedName == "library.Contract" }
                val rewrittenRelationships =
                    report.workspaceIndex.relationships.map { relationship ->
                        if (relationship.sourceEvidence == originalImport) {
                            relationship.copy(sourceEvidence = qualifiedOnlyImport)
                        } else {
                            relationship
                        }
                    }
                val impossibleRelationship =
                    WorkspaceRelationship(
                        source = consumer,
                        target = contract,
                        kind = WorkspaceRelationshipKind.TYPE_REFERENCE,
                        sourceEvidence = unaddressable,
                        evidence = ReferenceEvidence.HEURISTIC,
                    )
                val malformedReport =
                    report.copy(
                        workspaceIndex =
                            report.workspaceIndex.copy(
                                references =
                                    report.workspaceIndex.references.map { reference ->
                                        when (reference) {
                                            originalImport -> qualifiedOnlyImport
                                            originalUnresolved -> blankQualifiedUnresolved
                                            else -> reference
                                        }
                                    } + unaddressable,
                                relationships = rewrittenRelationships + impossibleRelationship,
                                usages =
                                    report.workspaceIndex.usages.map { usage ->
                                        if (usage.symbol.identity == consumer.identity) {
                                            usage.copy(outgoing = usage.outgoing + impossibleRelationship)
                                        } else {
                                            usage
                                        }
                                    },
                            ),
                        importantSymbols =
                            report.importantSymbols.map { important ->
                                if (important.symbol.identity == consumer.identity) {
                                    important.copy(
                                        usage =
                                            important.usage.copy(
                                                outgoing = important.usage.outgoing + impossibleRelationship,
                                            ),
                                    )
                                } else {
                                    important
                                }
                            },
                    )
                val snapshot = WorkspaceSnapshotMapper.map(malformedReport, "workspace:fixture")

                then("a qualified target supplies its truthful simple name") {
                    val recovered = snapshot.references.single { it.targetQualifiedName == "library.Contract" }
                    recovered.targetName shouldBe "Contract"
                    snapshot.relationships.single { it.referenceId == recovered.id }.kind shouldBe
                        RelationshipKind.IMPORT
                }

                then("blank qualified targets become absent while addressable references remain") {
                    snapshot.references.single { it.targetName == "MissingPolicy" }.targetQualifiedName shouldBe null
                }

                then("unaddressable evidence and its impossible relationship are omitted from the graph and usage") {
                    snapshot.references.none { it.context == unaddressable.context } shouldBe true
                    snapshot.references.size shouldBe report.workspaceIndex.references.size
                    snapshot.relationships.size shouldBe report.workspaceIndex.relationships.size
                    snapshot.importantSymbols
                        .single()
                        .usage
                        .outgoingRelationshipIds
                        .size shouldBe
                        report.importantSymbols
                            .single()
                            .usage
                            .outgoing
                            .size
                }

                then("normalization and filtering remain canonically deterministic") {
                    val expected = WorkspaceSnapshotMapper.map(report, "workspace:fixture")
                    val reordered =
                        WorkspaceSnapshotMapper.map(
                            malformedReport.reorderedForSnapshotTest(),
                            "workspace:fixture",
                        )
                    snapshot shouldBe expected
                    WorkspaceSnapshotJson.encode(reordered) shouldBe WorkspaceSnapshotJson.encode(expected)
                }
            }

            `when`("a symbol has a directed relationship to itself inside a larger cycle") {
                val consumer = report.workspaceIndex.symbols.single { it.qualifiedName == "fixture.Consumer" }
                val selfReference =
                    report.workspaceIndex.references
                        .single { it.targetName == "MissingPolicy" }
                        .copy(
                            targetName = "Consumer",
                            targetQualifiedName = consumer.qualifiedName,
                            context = "Consumer references itself",
                        )
                val selfRelationship =
                    WorkspaceRelationship(
                        source = consumer,
                        target = consumer,
                        kind = WorkspaceRelationshipKind.TYPE_REFERENCE,
                        sourceEvidence = selfReference,
                        evidence = ReferenceEvidence.DIRECT,
                    )
                val selfLoopReport =
                    report.copy(
                        workspaceIndex =
                            report.workspaceIndex.copy(
                                references = report.workspaceIndex.references + selfReference,
                                relationships = report.workspaceIndex.relationships + selfRelationship,
                                usages =
                                    report.workspaceIndex.usages.map { usage ->
                                        if (usage.symbol.identity == consumer.identity) {
                                            usage.copy(
                                                incoming = usage.incoming + selfRelationship,
                                                outgoing = usage.outgoing + selfRelationship,
                                            )
                                        } else {
                                            usage
                                        }
                                    },
                            ),
                        importantSymbols =
                            report.importantSymbols.map { important ->
                                if (important.symbol.identity == consumer.identity) {
                                    important.copy(
                                        usage =
                                            important.usage.copy(
                                                incoming = important.usage.incoming + selfRelationship,
                                                outgoing = important.usage.outgoing + selfRelationship,
                                            ),
                                    )
                                } else {
                                    important
                                }
                            },
                    )
                val snapshot = WorkspaceSnapshotMapper.map(selfLoopReport, "workspace:fixture")
                val reference = snapshot.references.single { it.context == selfReference.context }
                val relationship = snapshot.relationships.single { it.referenceId == reference.id }

                then("the relationship catalog retains the directed self-reference evidence") {
                    relationship.sourceSymbolId shouldBe relationship.targetSymbolId
                    relationship.kind shouldBe RelationshipKind.TYPE_REFERENCE
                }

                then("the multi-declaration cycle summary excludes the one-node loop") {
                    snapshot.relationshipCycles.size shouldBe 1
                    snapshot.relationshipCycles
                        .single()
                        .relationshipIds
                        .contains(relationship.id) shouldBe false
                    snapshot.relationshipCycles
                        .single()
                        .symbolIds
                        .distinct()
                        .size shouldBe 3
                }

                then("self-loop exclusion is canonically deterministic") {
                    val reordered =
                        WorkspaceSnapshotMapper.map(
                            selfLoopReport.reorderedForSnapshotTest(),
                            "workspace:fixture",
                        )
                    WorkspaceSnapshotJson.encode(reordered) shouldBe WorkspaceSnapshotJson.encode(snapshot)
                }
            }

            `when`("an aggregate hub points at the pseudo-line after a terminal newline") {
                val aggregate = requireNotNull(report.aggregateAnalysis)
                val invalidLineReport =
                    report.copy(
                        aggregateAnalysis =
                            aggregate.copy(
                                hubs = aggregate.hubs.map { hub -> hub.copy(line = 5) },
                            ),
                    )
                val emptySourceReport =
                    report.copy(
                        aggregateAnalysis =
                            aggregate.copy(
                                hubs = aggregate.hubs.map { hub -> hub.copy(filePath = "config.json", line = 1) },
                            ),
                        sourceFiles =
                            report.sourceFiles.map { sourceFile ->
                                if (sourceFile.projectRelativeFile.endsWith("config.json")) {
                                    sourceFile.copy(content = "")
                                } else {
                                    sourceFile
                                }
                            },
                    )

                then("the mapper retains the raw location without claiming false file resolution") {
                    val snapshot = WorkspaceSnapshotMapper.map(invalidLineReport, "workspace:fixture")
                    snapshot.aggregateHubs.single().line shouldBe 5
                    snapshot.aggregateHubs.single().sourceFileId shouldBe null
                    val emptySourceSnapshot = WorkspaceSnapshotMapper.map(emptySourceReport, "workspace:fixture")
                    emptySourceSnapshot.aggregateHubs.single().line shouldBe 1
                    emptySourceSnapshot.aggregateHubs.single().sourceFileId shouldBe null
                }
            }
        }
    })
