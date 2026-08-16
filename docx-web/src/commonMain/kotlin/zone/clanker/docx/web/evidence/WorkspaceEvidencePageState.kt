package zone.clanker.docx.web.evidence

import zone.clanker.report.model.WorkspaceEvidenceCursor
import zone.clanker.report.model.WorkspaceRelationshipOccurrence
import zone.clanker.report.model.WorkspaceUsageCategory
import zone.clanker.report.model.workspaceEvidenceCursorComparator
import zone.clanker.report.model.workspaceEvidenceOccurrenceComparator

internal data class WorkspaceEvidencePageState(
    val totalCount: Long,
    val occurrences: List<WorkspaceRelationshipOccurrence>,
    val previousCursor: WorkspaceEvidenceCursor?,
    val nextCursor: WorkspaceEvidenceCursor?,
    val activeIndex: Int,
    val pageStartIndex: Long,
) {
    init {
        require(totalCount >= occurrences.size) { "Evidence total cannot omit the active page" }
        require(occurrences.isNotEmpty()) { "An active evidence page must contain at least one occurrence" }
        require(occurrences.size <= MAX_PAGE_SIZE) { "Evidence navigation pages must remain bounded" }
        require(occurrences == occurrences.sortedWith(workspaceEvidenceOccurrenceComparator())) {
            "Evidence navigation pages must remain deterministic"
        }
        require(activeIndex in occurrences.indices) { "Evidence active index must identify a loaded occurrence" }
        require(pageStartIndex >= 0) { "Evidence page start index must not be negative" }
        require(pageStartIndex + occurrences.size <= totalCount) { "Evidence page exceeds its total count" }
        require(previousCursor == null || previousCursor == occurrences.first().cursor) {
            "Evidence previous cursor must identify the first loaded occurrence"
        }
        require(nextCursor == null || nextCursor == occurrences.last().cursor) {
            "Evidence next cursor must identify the last loaded occurrence"
        }
    }

    val active: WorkspaceRelationshipOccurrence
        get() = occurrences[activeIndex]

    val absoluteActiveIndex: Long
        get() = pageStartIndex + activeIndex

    val canMovePrevious: Boolean
        get() = activeIndex > 0 || previousCursor != null

    val canMoveNext: Boolean
        get() = activeIndex < occurrences.lastIndex || nextCursor != null

    val needsPreviousPage: Boolean
        get() = activeIndex == 0 && previousCursor != null

    val needsNextPage: Boolean
        get() = activeIndex == occurrences.lastIndex && nextCursor != null

    fun previousLoaded(): WorkspaceEvidencePageState {
        require(activeIndex > 0) { "The previous occurrence is not in the active page" }
        return copy(activeIndex = activeIndex - 1)
    }

    fun nextLoaded(): WorkspaceEvidencePageState {
        require(activeIndex < occurrences.lastIndex) { "The next occurrence is not in the active page" }
        return copy(activeIndex = activeIndex + 1)
    }

    fun activate(index: Int): WorkspaceEvidencePageState {
        require(index in occurrences.indices) { "Evidence selection must identify a loaded occurrence" }
        return copy(activeIndex = index)
    }

    fun advance(
        records: List<WorkspaceRelationshipOccurrence>,
        total: Long,
        previous: WorkspaceEvidenceCursor?,
        next: WorkspaceEvidenceCursor?,
    ): WorkspaceEvidencePageState {
        require(needsNextPage) { "Evidence navigation is not waiting for a next page" }
        require(total == totalCount) { "Evidence total changed while traversing a pinned generation" }
        require(records.isNotEmpty()) { "Evidence next page must not be empty when a cursor was advertised" }
        require(
            workspaceEvidenceCursorComparator().compare(records.first().cursor, requireNotNull(nextCursor)) > 0,
        ) { "Evidence next page did not advance beyond its cursor" }
        return WorkspaceEvidencePageState(
            totalCount = total,
            occurrences = records,
            previousCursor = previous,
            nextCursor = next,
            activeIndex = 0,
            pageStartIndex = pageStartIndex + occurrences.size,
        )
    }

    fun retreat(
        records: List<WorkspaceRelationshipOccurrence>,
        total: Long,
        previous: WorkspaceEvidenceCursor?,
        next: WorkspaceEvidenceCursor?,
    ): WorkspaceEvidencePageState {
        require(needsPreviousPage) { "Evidence navigation is not waiting for a previous page" }
        require(total == totalCount) { "Evidence total changed while traversing a pinned generation" }
        require(records.isNotEmpty()) { "Evidence previous page must contain the retained occurrence" }
        require(
            workspaceEvidenceCursorComparator().compare(records.last().cursor, requireNotNull(previousCursor)) < 0,
        ) { "Evidence previous page did not retreat before its cursor" }
        return WorkspaceEvidencePageState(
            totalCount = total,
            occurrences = records,
            previousCursor = previous,
            nextCursor = next,
            activeIndex = records.lastIndex,
            pageStartIndex = (pageStartIndex - records.size).coerceAtLeast(0),
        )
    }

    fun categorized(): List<WorkspaceUsageSection> =
        WorkspaceUsageCategory.entries.mapNotNull { category ->
            occurrences
                .filter { occurrence -> occurrence.category == category }
                .takeIf { records -> records.isNotEmpty() }
                ?.let { records -> WorkspaceUsageSection(category, records) }
        }

    companion object {
        const val MAX_PAGE_SIZE: Int = 100

        fun initial(
            records: List<WorkspaceRelationshipOccurrence>,
            totalCount: Long,
            previousCursor: WorkspaceEvidenceCursor?,
            nextCursor: WorkspaceEvidenceCursor?,
        ): WorkspaceEvidencePageState? =
            records.takeIf { page -> page.isNotEmpty() }?.let {
                WorkspaceEvidencePageState(
                    totalCount = totalCount,
                    occurrences = records,
                    previousCursor = previousCursor,
                    nextCursor = nextCursor,
                    activeIndex = 0,
                    pageStartIndex = 0,
                )
            }
    }
}

internal data class WorkspaceUsageSection(
    val category: WorkspaceUsageCategory,
    val occurrences: List<WorkspaceRelationshipOccurrence>,
) {
    init {
        require(occurrences.isNotEmpty()) { "Evidence usage sections must not be empty" }
        require(occurrences.all { occurrence -> occurrence.category == category }) {
            "Evidence usage sections must contain one truthful category"
        }
    }
}
