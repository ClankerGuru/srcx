package zone.clanker.docx.index

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import zone.clanker.report.model.RelationshipKind
import zone.clanker.report.model.WorkspaceDeclarationEvidenceRequest
import zone.clanker.report.model.WorkspaceEvidenceTarget
import zone.clanker.report.model.WorkspaceRelationshipOccurrenceRequest
import zone.clanker.report.model.WorkspaceRelationshipOccurrenceSelector
import zone.clanker.report.model.WorkspaceReverseUsageRequest
import zone.clanker.report.model.WorkspaceUsageCategory

class WorkspaceEvidenceQueryTest :
    BehaviorSpec({
        given("one indexed immutable generation") {
            val site = WorkspaceIndexFixture.createSite()
            val database = WorkspaceIndexFixture.createDatabase()
            WorkspaceIndexFixture.writeGeneration(site, EVIDENCE_GENERATION_ID)

            then("returns exact declaration and bounded reverse evidence") {
                WorkspaceReportIndex.open(database).use { index ->
                    index.indexSite(WorkspaceIndexFixture.INDEX_WORKSPACE_ID, site)
                    val target = evidenceTarget()
                    val declaration =
                        index
                            .declarationEvidence(
                                WorkspaceIndexFixture.INDEX_WORKSPACE_ID,
                                WorkspaceDeclarationEvidenceRequest(
                                    target,
                                    WorkspaceIndexFixture.ALPHA_FUNCTION_ID,
                                ),
                            ).declaration

                    requireNotNull(declaration).ownerSymbolId shouldBe WorkspaceIndexFixture.ALPHA_CLASS_ID
                    declaration.signature shouldBe "fun runAlpha(): AlphaService"
                    requireNotNull(declaration.location.range).startOffset shouldBe 21

                    val usages =
                        index.reverseUsages(
                            WorkspaceIndexFixture.INDEX_WORKSPACE_ID,
                            WorkspaceReverseUsageRequest(target, WorkspaceIndexFixture.ALPHA_CLASS_ID, limit = 1),
                        )
                    usages.totalCount shouldBe 2
                    requireNotNull(usages.nextCursor)
                    usages.occurrences.map { occurrence -> occurrence.category } shouldContainExactly
                        listOf(WorkspaceUsageCategory.CALLER)
                    usages.occurrences.single().context shouldBe "runAlpha() = AlphaService()"
                    requireNotNull(
                        usages.occurrences
                            .single()
                            .location.range,
                    ).let { range ->
                        range.endOffsetExclusive - range.startOffset shouldBe "AlphaService".length
                    }

                    val next =
                        index.reverseUsages(
                            WorkspaceIndexFixture.INDEX_WORKSPACE_ID,
                            WorkspaceReverseUsageRequest(
                                target,
                                WorkspaceIndexFixture.ALPHA_CLASS_ID,
                                after = usages.nextCursor,
                                limit = 1,
                            ),
                        )
                    requireNotNull(next.previousCursor)
                    next.nextCursor shouldBe null
                    val previous =
                        index.reverseUsages(
                            WorkspaceIndexFixture.INDEX_WORKSPACE_ID,
                            WorkspaceReverseUsageRequest(
                                target,
                                WorkspaceIndexFixture.ALPHA_CLASS_ID,
                                before = next.previousCursor,
                                limit = 1,
                            ),
                        )
                    previous.occurrences shouldBe usages.occurrences
                }
            }

            then("resolves every occurrence behind the aggregate relation endpoints") {
                WorkspaceReportIndex.open(database).use { index ->
                    index.indexSite(WorkspaceIndexFixture.INDEX_WORKSPACE_ID, site)
                    val page =
                        index.relationshipOccurrences(
                            WorkspaceIndexFixture.INDEX_WORKSPACE_ID,
                            WorkspaceRelationshipOccurrenceRequest(
                                target = evidenceTarget(),
                                selector =
                                    WorkspaceRelationshipOccurrenceSelector(
                                        kind = RelationshipKind.CALL,
                                        sourceNodeId = WorkspaceIndexFixture.ALPHA_FUNCTION_ID,
                                        targetNodeId = WorkspaceIndexFixture.ALPHA_CLASS_ID,
                                    ),
                            ),
                        )

                    page.totalCount shouldBe 2
                    page.occurrences.map { occurrence -> occurrence.relationshipId } shouldContainExactly
                        listOf("relationship:alpha:call", "relationship:alpha:call:later")
                }
            }

            then("rejects a stale or foreign generation instead of falling back to active") {
                WorkspaceReportIndex.open(database).use { index ->
                    index.indexSite(WorkspaceIndexFixture.INDEX_WORKSPACE_ID, site)
                    shouldThrow<IllegalArgumentException> {
                        index.declarationEvidence(
                            WorkspaceIndexFixture.INDEX_WORKSPACE_ID,
                            WorkspaceDeclarationEvidenceRequest(
                                WorkspaceEvidenceTarget(
                                    WorkspaceIndexFixture.SOURCE_WORKSPACE_ID,
                                    "other-generation",
                                ),
                                WorkspaceIndexFixture.ALPHA_CLASS_ID,
                            ),
                        )
                    }
                }
            }
        }
    })

private fun evidenceTarget(): WorkspaceEvidenceTarget =
    WorkspaceEvidenceTarget(WorkspaceIndexFixture.SOURCE_WORKSPACE_ID, EVIDENCE_GENERATION_ID)

private const val EVIDENCE_GENERATION_ID: String = "generation-evidence"
