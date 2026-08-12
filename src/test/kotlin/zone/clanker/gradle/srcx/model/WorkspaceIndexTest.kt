package zone.clanker.gradle.srcx.model

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class WorkspaceIndexTest :
    BehaviorSpec({
        given("workspace report models") {
            val symbol =
                WorkspaceSymbol(
                    build = "root",
                    project = ":app",
                    sourceSet = "main",
                    name = "Service",
                    qualifiedName = "sample.Service",
                    kind = SymbolDetailKind.CLASS,
                    projectRelativeFile = "src/main/kotlin/sample/Service.kt",
                    declarationLine = 3,
                )

            `when`("a scoped identity is requested") {
                then("it includes all declaration ownership") {
                    symbol.identity.value shouldBe
                        "root:::app::main::sample.Service@src/main/kotlin/sample/Service.kt:3"
                }
            }

            `when`("usage has no workspace inbound relationships") {
                val usage = WorkspaceSymbolUsage(symbol, emptyList(), emptyList())

                then("the symbol is not workspace-used") {
                    usage.isWorkspaceUsed shouldBe false
                }
            }

            `when`("resolved consumers have evidence scopes but no source declaration") {
                fun relationship(
                    build: String,
                    project: String,
                    kind: WorkspaceRelationshipKind,
                ): WorkspaceRelationship {
                    val reference =
                        WorkspaceReference(
                            build = build,
                            project = project,
                            sourceSet = "main",
                            sourceSymbol = null,
                            targetName = symbol.name,
                            targetQualifiedName = symbol.qualifiedName,
                            kind =
                                if (kind == WorkspaceRelationshipKind.IMPORT) {
                                    ReferenceKind.IMPORT
                                } else {
                                    ReferenceKind.CALL
                                },
                            projectRelativeFile = "src/main/kotlin/sample/Consumer.kt",
                            line = 9,
                            context = symbol.name,
                            evidence = ReferenceEvidence.DIRECT,
                        )
                    return WorkspaceRelationship(null, symbol, kind, reference, ReferenceEvidence.DIRECT)
                }

                val local = relationship("root", ":app", WorkspaceRelationshipKind.CALL)
                val external = relationship("included", ":client", WorkspaceRelationshipKind.CALL)
                val import = relationship("included", ":client", WorkspaceRelationshipKind.IMPORT)
                val usage = WorkspaceSymbolUsage(symbol, listOf(import, external, local), emptyList())

                then("evidence ownership drives counts while imports never inflate usage") {
                    usage.localInbound shouldBe 1
                    usage.workspaceInbound shouldBe 2
                    usage.crossBuildInbound shouldBe 1
                    usage.isWorkspaceUsed shouldBe true
                }
            }
        }
    })
