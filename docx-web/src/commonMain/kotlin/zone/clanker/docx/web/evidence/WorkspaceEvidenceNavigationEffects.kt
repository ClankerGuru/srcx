package zone.clanker.docx.web.evidence

import zone.clanker.docx.web.site.WorkspaceEvidenceGateway
import zone.clanker.report.model.WorkspaceDeclarationEvidencePage
import zone.clanker.report.model.WorkspaceDeclarationEvidenceRequest
import zone.clanker.report.model.WorkspaceEvidenceCursor
import zone.clanker.report.model.WorkspaceRelationshipOccurrence
import zone.clanker.report.model.WorkspaceRelationshipOccurrencePage
import zone.clanker.report.model.WorkspaceRelationshipOccurrenceRequest
import zone.clanker.report.model.WorkspaceReverseUsagePage
import zone.clanker.report.model.WorkspaceReverseUsageRequest

internal class WorkspaceRelationshipEvidenceNavigator(
    gateway: WorkspaceEvidenceGateway,
    onStateChanged: (WorkspaceEvidencePageState?) -> Unit,
    onSourceRequested: (projectId: String, fileId: String) -> Unit,
) {
    private val navigator =
        WorkspacePagedEvidenceNavigator(
            load = gateway::relationshipOccurrences,
            withCursor = { request, after, before -> request.copy(after = after, before = before) },
            pageData = WorkspaceRelationshipOccurrencePage::pageData,
            onStateChanged = onStateChanged,
            onSourceRequested = onSourceRequested,
        )

    val state: WorkspaceEvidencePageState?
        get() = navigator.state

    suspend fun open(request: WorkspaceRelationshipOccurrenceRequest) = navigator.open(request)

    suspend fun previous() = navigator.previous()

    suspend fun next() = navigator.next()

    fun select(index: Int) = navigator.select(index)
}

internal class WorkspaceReverseUsageEvidenceNavigator(
    private val gateway: WorkspaceEvidenceGateway,
    onStateChanged: (WorkspaceEvidencePageState?) -> Unit,
    onSourceRequested: (projectId: String, fileId: String) -> Unit,
) {
    private val navigator =
        WorkspacePagedEvidenceNavigator(
            load = gateway::reverseUsages,
            withCursor = { request, after, before -> request.copy(after = after, before = before) },
            pageData = WorkspaceReverseUsagePage::pageData,
            onStateChanged = onStateChanged,
            onSourceRequested = onSourceRequested,
        )

    val state: WorkspaceEvidencePageState?
        get() = navigator.state

    suspend fun declaration(request: WorkspaceDeclarationEvidenceRequest): WorkspaceDeclarationEvidencePage =
        gateway.declaration(request)

    suspend fun open(request: WorkspaceReverseUsageRequest) = navigator.open(request)

    suspend fun previous() = navigator.previous()

    suspend fun next() = navigator.next()

    fun select(index: Int) = navigator.select(index)
}

private class WorkspacePagedEvidenceNavigator<Request, Page>(
    private val load: suspend (Request) -> Page,
    private val withCursor: (Request, WorkspaceEvidenceCursor?, WorkspaceEvidenceCursor?) -> Request,
    private val pageData: (Page) -> WorkspaceEvidencePageData,
    private val onStateChanged: (WorkspaceEvidencePageState?) -> Unit,
    private val onSourceRequested: (projectId: String, fileId: String) -> Unit,
) {
    private var request: Request? = null
    var state: WorkspaceEvidencePageState? = null
        private set

    suspend fun open(request: Request) {
        this.request = request
        val data = pageData(load(request))
        publish(
            WorkspaceEvidencePageState.initial(
                records = data.occurrences,
                totalCount = data.totalCount,
                previousCursor = data.previousCursor,
                nextCursor = data.nextCursor,
            ),
        )
    }

    suspend fun previous() {
        val current = state ?: return
        if (!current.needsPreviousPage) {
            if (current.canMovePrevious) publish(current.previousLoaded())
            return
        }
        val data = pageData(load(withCursor(requireNotNull(request), null, current.previousCursor)))
        publish(current.retreat(data.occurrences, data.totalCount, data.previousCursor, data.nextCursor))
    }

    suspend fun next() {
        val current = state ?: return
        if (!current.needsNextPage) {
            if (current.canMoveNext) publish(current.nextLoaded())
            return
        }
        val data = pageData(load(withCursor(requireNotNull(request), current.nextCursor, null)))
        publish(current.advance(data.occurrences, data.totalCount, data.previousCursor, data.nextCursor))
    }

    fun select(index: Int) {
        val current = state ?: return
        publish(current.activate(index))
    }

    private fun publish(next: WorkspaceEvidencePageState?) {
        state = next
        onStateChanged(next)
        next?.active?.location?.let { location -> onSourceRequested(location.projectId, location.fileId) }
    }
}

private data class WorkspaceEvidencePageData(
    val totalCount: Long,
    val occurrences: List<WorkspaceRelationshipOccurrence>,
    val previousCursor: WorkspaceEvidenceCursor?,
    val nextCursor: WorkspaceEvidenceCursor?,
)

private fun WorkspaceRelationshipOccurrencePage.pageData(): WorkspaceEvidencePageData =
    WorkspaceEvidencePageData(totalCount, occurrences, previousCursor, nextCursor)

private fun WorkspaceReverseUsagePage.pageData(): WorkspaceEvidencePageData =
    WorkspaceEvidencePageData(totalCount, occurrences, previousCursor, nextCursor)
