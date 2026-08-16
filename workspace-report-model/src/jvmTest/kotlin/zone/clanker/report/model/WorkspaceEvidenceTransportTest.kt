package zone.clanker.report.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class WorkspaceEvidenceTransportTest :
    FunSpec({
        test("round trips a bounded generation-scoped occurrence page") {
            val target = WorkspaceEvidenceTarget("workspace", "generation")
            val selector = WorkspaceRelationshipOccurrenceSelector(RelationshipKind.CALL, "source", "target")
            val occurrence = occurrence("relationship-1")
            val page =
                WorkspaceRelationshipOccurrencePage(
                    target = target,
                    selector = selector,
                    totalCount = 2,
                    occurrences = listOf(occurrence),
                    previousCursor = occurrence.cursor,
                    nextCursor = occurrence.cursor,
                )

            WorkspaceEvidenceJson.decodeOccurrencePage(
                WorkspaceEvidenceJson.encodeOccurrencePage(page),
            ) shouldBe page
        }

        test("rejects unbounded evidence requests") {
            shouldThrow<IllegalArgumentException> {
                WorkspaceReverseUsageRequest(
                    target = WorkspaceEvidenceTarget("workspace", "generation"),
                    symbolId = "symbol",
                    limit = WorkspaceEvidenceLimits.MAX_PAGE_SIZE + 1,
                )
            }
        }

        test("rejects ambiguous bidirectional cursor requests") {
            val cursor = occurrence("relationship-cursor").cursor
            shouldThrow<IllegalArgumentException> {
                WorkspaceReverseUsageRequest(
                    target = WorkspaceEvidenceTarget("workspace", "generation"),
                    symbolId = "symbol",
                    after = cursor,
                    before = cursor,
                )
            }
        }

        test("does not claim property reads or writes without captured facts") {
            RelationshipKind.PROPERTY_TYPE.usageCategory() shouldBe WorkspaceUsageCategory.TYPE_REFERENCE
            RelationshipKind.NAME_REFERENCE.usageCategory() shouldBe WorkspaceUsageCategory.REFERENCE
        }
    })

private fun occurrence(id: String): WorkspaceRelationshipOccurrence =
    WorkspaceRelationshipOccurrence(
        relationshipId = id,
        referenceId = "reference-$id",
        sourceSymbolId = "source-symbol",
        targetSymbolId = "target-symbol",
        kind = RelationshipKind.CALL,
        evidence = RelationshipEvidence.DIRECT,
        category = WorkspaceUsageCategory.CALLER,
        location =
            WorkspaceEvidenceLocation(
                buildId = "build",
                projectId = "project",
                sourceSetId = "source-set",
                sourceSetName = "main",
                fileId = "file",
                filePath = "src/main/kotlin/Caller.kt",
                line = 7,
            ),
        context = "target()",
    )
