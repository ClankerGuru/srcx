package zone.clanker.docx.web.evidence

import kotlinx.coroutines.test.runTest
import zone.clanker.docx.web.site.WorkspaceEvidenceGateway
import zone.clanker.report.model.RelationshipEvidence
import zone.clanker.report.model.RelationshipKind
import zone.clanker.report.model.WorkspaceDeclarationEvidenceRequest
import zone.clanker.report.model.WorkspaceEvidenceCursor
import zone.clanker.report.model.WorkspaceEvidenceLocation
import zone.clanker.report.model.WorkspaceEvidenceTarget
import zone.clanker.report.model.WorkspaceRelationshipOccurrence
import zone.clanker.report.model.WorkspaceRelationshipOccurrencePage
import zone.clanker.report.model.WorkspaceRelationshipOccurrenceRequest
import zone.clanker.report.model.WorkspaceRelationshipOccurrenceSelector
import zone.clanker.report.model.WorkspaceReverseUsageRequest
import zone.clanker.report.model.WorkspaceUsageCategory
import zone.clanker.report.model.workspaceEvidenceOccurrenceComparator
import kotlin.test.Test
import kotlin.test.assertEquals

class WorkspaceEvidenceNavigationTest {
    @Test
    fun pagesForwardAndBackwardWhileRequestingOnlyTheActiveSource() =
        runTest {
            val firstPage = occurrencePage(listOf(occurrence(1), occurrence(2)), hasPrevious = false, hasMore = true)
            val secondPage = occurrencePage(listOf(occurrence(3)), hasPrevious = true, hasMore = false)
            val requestedCursors =
                mutableListOf<Pair<WorkspaceEvidenceCursor?, WorkspaceEvidenceCursor?>>()
            val requestedSources = mutableListOf<Pair<String, String>>()
            val states = mutableListOf<WorkspaceEvidencePageState?>()
            val gateway =
                occurrenceGateway { request ->
                    requestedCursors += request.after to request.before
                    if (request.after == occurrence(2).cursor) secondPage else firstPage
                }
            val navigator =
                WorkspaceRelationshipEvidenceNavigator(
                    gateway = gateway,
                    onStateChanged = states::add,
                    onSourceRequested = { projectId, fileId -> requestedSources += projectId to fileId },
                )

            navigator.open(occurrenceRequest())
            navigator.next()
            navigator.next()
            navigator.previous()
            navigator.select(0)

            assertEquals(
                listOf(
                    null to null,
                    occurrence(2).cursor to null,
                    null to occurrence(3).cursor,
                ),
                requestedCursors,
            )
            assertEquals(
                listOf(
                    "project:fixture" to "file:1",
                    "project:fixture" to "file:2",
                    "project:fixture" to "file:3",
                    "project:fixture" to "file:2",
                    "project:fixture" to "file:1",
                ),
                requestedSources,
            )
            assertEquals("relationship:001", navigator.state?.active?.relationshipId)
            assertEquals(0L, navigator.state?.absoluteActiveIndex)
            assertEquals(5, states.size)
        }

    @Test
    fun groupsTheBoundedActivePageIntoTruthfulStableUsageCategories() {
        val records =
            listOf(
                occurrence(1),
                occurrence(2).copy(
                    relationshipId = "relationship:004",
                    referenceId = "reference:004",
                    kind = RelationshipKind.TYPE_REFERENCE,
                    category = WorkspaceUsageCategory.TYPE_REFERENCE,
                ),
            ).sortedWith(workspaceEvidenceOccurrenceComparator())
        val state =
            requireNotNull(
                WorkspaceEvidencePageState.initial(records, 2, previousCursor = null, nextCursor = null),
            )

        assertEquals(
            listOf(WorkspaceUsageCategory.CALLER, WorkspaceUsageCategory.TYPE_REFERENCE),
            state.categorized().map { section -> section.category },
        )
    }
}

private fun occurrenceGateway(
    load: suspend (WorkspaceRelationshipOccurrenceRequest) -> WorkspaceRelationshipOccurrencePage,
): WorkspaceEvidenceGateway =
    object : WorkspaceEvidenceGateway {
        override suspend fun declaration(request: WorkspaceDeclarationEvidenceRequest) =
            error("Unexpected declaration request")

        override suspend fun reverseUsages(request: WorkspaceReverseUsageRequest) =
            error("Unexpected reverse-usage request")

        override suspend fun relationshipOccurrences(
            request: WorkspaceRelationshipOccurrenceRequest,
        ): WorkspaceRelationshipOccurrencePage = load(request)
    }

private fun occurrenceRequest(): WorkspaceRelationshipOccurrenceRequest =
    WorkspaceRelationshipOccurrenceRequest(
        target = EVIDENCE_TARGET,
        selector = EVIDENCE_SELECTOR,
        limit = 2,
    )

private fun occurrencePage(
    records: List<WorkspaceRelationshipOccurrence>,
    hasPrevious: Boolean,
    hasMore: Boolean,
): WorkspaceRelationshipOccurrencePage =
    WorkspaceRelationshipOccurrencePage(
        target = EVIDENCE_TARGET,
        selector = EVIDENCE_SELECTOR,
        totalCount = 3,
        occurrences = records,
        previousCursor = records.first().cursor.takeIf { hasPrevious },
        nextCursor = records.last().cursor.takeIf { hasMore },
    )

private fun occurrence(index: Int): WorkspaceRelationshipOccurrence =
    WorkspaceRelationshipOccurrence(
        relationshipId = "relationship:${index.toString().padStart(3, '0')}",
        referenceId = "reference:${index.toString().padStart(3, '0')}",
        sourceSymbolId = "symbol:source",
        targetSymbolId = "symbol:target",
        kind = RelationshipKind.CALL,
        evidence = RelationshipEvidence.DIRECT,
        category = WorkspaceUsageCategory.CALLER,
        location =
            WorkspaceEvidenceLocation(
                buildId = "build:fixture",
                projectId = "project:fixture",
                sourceSetId = "source-set:fixture",
                sourceSetName = "main",
                fileId = "file:$index",
                filePath = "src/main/kotlin/File${index.toString().padStart(3, '0')}.kt",
                line = index,
            ),
        context = "target()",
    )

private val EVIDENCE_TARGET = WorkspaceEvidenceTarget("workspace:fixture", "generation:fixture")
private val EVIDENCE_SELECTOR =
    WorkspaceRelationshipOccurrenceSelector(RelationshipKind.CALL, "symbol:source", "symbol:target")
