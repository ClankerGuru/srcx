package zone.clanker.report.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

class WorkspaceSnapshotTest :
    BehaviorSpec({
        given("a complete typed workspace snapshot") {
            val snapshot = completeWorkspaceSnapshot()

            `when`("it crosses the canonical JSON boundary") {
                val encoded = WorkspaceSnapshotJson.encode(snapshot)
                val decoded = WorkspaceSnapshotJson.decode(encoded)

                then("every typed source, graph, analysis, summary, and nullable DI fact survives") {
                    decoded shouldBe snapshot
                    decoded.references.map(ReferenceSnapshot::targetName) shouldContainExactly
                        listOf(
                            "Implementation",
                            "Consumer",
                            "Consumer",
                            "Helper",
                            "Contract",
                            "MissingPolicy",
                        )
                    decoded.relationshipCycles.single().symbolIds shouldContainExactly
                        listOf(
                            "symbol:consumer",
                            "symbol:implementation",
                            "symbol:helper",
                            "symbol:consumer",
                        )
                    decoded.importantSymbols.single().usage shouldBe
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
                        )
                    decoded.dependencyInjection
                        ?.bindings
                        ?.first()
                        ?.scope shouldBe null
                }

                then("the canonical document has exactly one trailing newline") {
                    encoded.endsWith("\n") shouldBe true
                    encoded.endsWith("\n\n") shouldBe false
                }
            }

            `when`("a future producer adds an unknown field") {
                val encoded = WorkspaceSnapshotJson.encode(snapshot)
                val fields = Json.parseToJsonElement(encoded).jsonObject.toMutableMap()
                fields["futureRendererHint"] = JsonPrimitive("ignored")

                then("the current decoder retains the known contract") {
                    WorkspaceSnapshotJson.decode(JsonObject(fields).toString()) shouldBe snapshot
                }
            }

            `when`("the document claims an unsupported schema version") {
                val fields =
                    Json
                        .parseToJsonElement(WorkspaceSnapshotJson.encode(snapshot))
                        .jsonObject
                        .toMutableMap()
                fields["schemaVersion"] = JsonPrimitive(999)

                then("the decoder rejects the incompatible boundary") {
                    shouldThrow<IllegalArgumentException> {
                        WorkspaceSnapshotJson.decode(JsonObject(fields).toString())
                    }
                }
            }
        }

        given("the checked-in version-one golden document") {
            `when`("the minimal fixture is serialized") {
                then("its bytes remain stable") {
                    val expected =
                        requireNotNull(
                            WorkspaceSnapshotTest::class.java.getResource("/workspace-snapshot-v1.json"),
                        ).readText()
                    WorkspaceSnapshotJson.encode(goldenWorkspaceSnapshot()) shouldBe expected
                    val decoded = WorkspaceSnapshotJson.decode(expected)
                    decoded shouldBe goldenWorkspaceSnapshot()
                    decoded.symbols.single().ownerSymbolId shouldBe null
                    decoded.symbols.single().signature shouldBe null
                    decoded.symbols.single().declarationRange shouldBe null
                }
            }
        }

        given("exact declaration evidence") {
            val source = "class App {\n    fun run() = Unit\n}\n"
            val functionStart = source.indexOf("fun run")
            val functionEnd = source.indexOf('\n', functionStart)
            val base = goldenWorkspaceSnapshot()
            val owner =
                base.symbols.single().copy(
                    signature = "class fixture.App",
                    declarationRange = SourceRangeSnapshot(0, source.lastIndexOf('}') + 1),
                )
            val member =
                owner.copy(
                    id = "symbol:app-run",
                    name = "App.run",
                    qualifiedName = "fixture.App.run",
                    kind = SymbolKind.FUNCTION,
                    declarationSemantic = DeclarationSemantic.OTHER,
                    declarationLine = 2,
                    ownerSymbolId = owner.id,
                    signature = "fun fixture.App.run():kotlin.Unit",
                    declarationRange = SourceRangeSnapshot(functionStart, functionEnd),
                )
            val snapshot =
                base.copy(
                    files = listOf(base.files.single().copy(content = source)),
                    symbols = listOf(owner, member),
                )

            `when`("the enriched snapshot crosses the canonical boundary") {
                then("owner, signature, and complete source offsets survive exactly") {
                    val decoded = WorkspaceSnapshotJson.decode(WorkspaceSnapshotJson.encode(snapshot))
                    decoded shouldBe snapshot
                    decoded.symbols.last().ownerSymbolId shouldBe owner.id
                    decoded.symbols.last().declarationRange shouldBe SourceRangeSnapshot(functionStart, functionEnd)
                }

                then("an annotation may begin the range before the compatibility declaration line") {
                    val annotatedSource = "@Deprecated\nclass App\n"
                    val annotated =
                        base.copy(
                            files = listOf(base.files.single().copy(content = annotatedSource)),
                            symbols =
                                listOf(
                                    base.symbols.single().copy(
                                        declarationLine = 2,
                                        declarationRange = SourceRangeSnapshot(0, annotatedSource.length - 1),
                                    ),
                                ),
                        )

                    WorkspaceSnapshotJson.decode(WorkspaceSnapshotJson.encode(annotated)) shouldBe annotated
                }
            }

            `when`("a producer claims evidence outside retained source") {
                then("the transport rejects the false exact range") {
                    shouldThrow<IllegalArgumentException> {
                        snapshot.copy(
                            symbols =
                                snapshot.symbols.map { symbol ->
                                    if (symbol.id == member.id) {
                                        symbol.copy(
                                            declarationRange = SourceRangeSnapshot(functionStart, source.length + 1),
                                        )
                                    } else {
                                        symbol
                                    }
                                },
                        )
                    }
                }
            }

            `when`("a producer claims a cross-file owner") {
                then("the transport rejects the false containment") {
                    val complete = completeWorkspaceSnapshot()
                    shouldThrow<IllegalArgumentException> {
                        complete.copy(
                            symbols =
                                complete.symbols.map { symbol ->
                                    if (symbol.id == "symbol:helper") {
                                        symbol.copy(ownerSymbolId = "symbol:consumer")
                                    } else {
                                        symbol
                                    }
                                },
                        )
                    }
                }
            }
        }

        given("adversarial transport data") {
            `when`("a symbol points outside the file catalog") {
                then("construction rejects the dangling identity") {
                    val snapshot = completeWorkspaceSnapshot()
                    shouldThrow<IllegalArgumentException> {
                        snapshot.copy(
                            symbols =
                                snapshot.symbols.map { symbol ->
                                    if (symbol.id == "symbol:consumer") symbol.copy(fileId = "file:missing") else symbol
                                },
                        )
                    }
                }
            }

            `when`("a declaration line exceeds its retained source") {
                then("construction rejects the false location") {
                    val snapshot = completeWorkspaceSnapshot()
                    shouldThrow<IllegalArgumentException> {
                        snapshot.copy(
                            symbols =
                                snapshot.symbols.map { symbol ->
                                    if (symbol.id == "symbol:contract") symbol.copy(declarationLine = 2) else symbol
                                },
                        )
                    }
                }
            }

            `when`("a retained source ends with a terminal newline") {
                then("the separator does not invent another source line") {
                    requireLineWithinContent(1, "class Contract\n", "Declaration")
                    shouldThrow<IllegalArgumentException> {
                        requireLineWithinContent(2, "class Contract\n", "Declaration")
                    }
                    shouldThrow<IllegalArgumentException> {
                        requireLineWithinContent(1, "", "Empty source")
                    }
                }
            }

            `when`("an aggregate hub line exceeds its retained source") {
                then("construction rejects the false exact location") {
                    val snapshot = completeWorkspaceSnapshot()
                    shouldThrow<IllegalArgumentException> {
                        snapshot.copy(
                            aggregateHubs =
                                listOf(
                                    snapshot.aggregateHubs.single().copy(line = 5),
                                ),
                        )
                    }
                }
            }

            `when`("a finding claims a symbol from another project") {
                then("construction rejects the cross-project finding") {
                    val snapshot = completeWorkspaceSnapshot()
                    val analysis = snapshot.projectAnalyses.single()
                    shouldThrow<IllegalArgumentException> {
                        snapshot.copy(
                            projectAnalyses =
                                listOf(
                                    analysis.copy(
                                        findings =
                                            listOf(
                                                analysis.findings.single().copy(
                                                    symbolIds = listOf("symbol:contract"),
                                                ),
                                            ),
                                    ),
                                ),
                        )
                    }
                }
            }

            `when`("an architecture cycle skips a directed dependency") {
                then("construction rejects the invented route") {
                    val analysis = completeWorkspaceSnapshot().projectAnalyses.single()
                    shouldThrow<IllegalArgumentException> {
                        analysis.copy(dependencies = analysis.dependencies.take(1))
                    }
                }
            }

            `when`("an observed relationship cycle reverses its evidence") {
                then("construction rejects the invented directed route") {
                    val snapshot = completeWorkspaceSnapshot()
                    shouldThrow<IllegalArgumentException> {
                        snapshot.copy(
                            relationshipCycles =
                                listOf(
                                    snapshot.relationshipCycles.single().copy(
                                        relationshipIds =
                                            snapshot.relationshipCycles
                                                .single()
                                                .relationshipIds
                                                .reversed(),
                                    ),
                                ),
                        )
                    }
                }
            }

            `when`("important-symbol incoming evidence is not incident to the declaration") {
                then("construction rejects the unrelated relationship") {
                    val snapshot = completeWorkspaceSnapshot()
                    val important = snapshot.importantSymbols.single()
                    shouldThrow<IllegalArgumentException> {
                        snapshot.copy(
                            importantSymbols =
                                listOf(
                                    important.copy(
                                        usage =
                                            important.usage.copy(
                                                incomingRelationshipIds = listOf("relationship:constructor"),
                                            ),
                                    ),
                                ),
                        )
                    }
                }
            }

            `when`("important-symbol counts include import-only evidence") {
                then("construction rejects the inflated usage count") {
                    val snapshot = completeWorkspaceSnapshot()
                    val important = snapshot.importantSymbols.single()
                    shouldThrow<IllegalArgumentException> {
                        snapshot.copy(
                            importantSymbols =
                                listOf(
                                    important.copy(
                                        usage = important.usage.copy(workspaceInboundCount = 2),
                                    ),
                                ),
                        )
                    }
                }
            }

            `when`("important-symbol outgoing evidence belongs to another declaration") {
                then("construction rejects the unrelated relationship") {
                    val snapshot = completeWorkspaceSnapshot()
                    val important = snapshot.importantSymbols.single()
                    shouldThrow<IllegalArgumentException> {
                        snapshot.copy(
                            importantSymbols =
                                listOf(
                                    important.copy(
                                        usage =
                                            important.usage.copy(
                                                outgoingRelationshipIds = listOf("relationship:implementation-helper"),
                                            ),
                                    ),
                                ),
                        )
                    }
                }
            }

            `when`("a project path escapes its normalized boundary") {
                then("construction rejects the unsafe path") {
                    shouldThrow<IllegalArgumentException> {
                        SourceFileSnapshot(
                            "file:unsafe",
                            "source-set:root",
                            "../outside.kt",
                            SourceLanguage.KOTLIN,
                        )
                    }
                }
            }

            `when`("a top-level catalog is not ordered by stable ID") {
                then("construction rejects nondeterministic transport order") {
                    val snapshot = completeWorkspaceSnapshot()
                    shouldThrow<IllegalArgumentException> {
                        snapshot.copy(files = snapshot.files.reversed())
                    }
                }
            }
        }
    })
