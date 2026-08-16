package zone.clanker.docx.web.state

import zone.clanker.report.model.WorkspaceSearchCatalog
import zone.clanker.report.model.WorkspaceSearchEntry
import zone.clanker.report.model.WorkspaceSearchResults

internal data class WorkspaceGlobalSearchQueryState(
    val generationId: String? = null,
    val query: String = "",
    val requestRevision: Int = 0,
    val loading: Boolean = false,
    val error: String? = null,
) {
    init {
        require(generationId == null || generationId.isNotBlank()) {
            "Global-search generation must not be blank"
        }
        require(requestRevision >= 0) { "Global-search request revision must not be negative" }
        require(!loading || generationId != null) { "Global-search loading requires a generation" }
        require(!loading || query.isNotBlank()) { "Global-search loading requires a query" }
        require(!loading || error == null) { "Global search cannot be loading and failed together" }
        require(error == null || error.isNotBlank()) { "Global-search error must not be blank" }
    }
}

internal data class WorkspaceGlobalSearchState(
    val queryState: WorkspaceGlobalSearchQueryState = WorkspaceGlobalSearchQueryState(),
    val catalog: WorkspaceSearchCatalog? = null,
    val results: WorkspaceSearchResults? = null,
    val activeResultKey: String? = null,
) {
    init {
        require(catalog == null || catalog.generationId == queryState.generationId) {
            "Global-search catalog must belong to the active generation"
        }
        require(results == null || results.query == queryState.query.trim()) {
            "Global-search results must belong to the active query"
        }
        require(activeResultKey == null || visibleResults.any { result -> result.key == activeResultKey }) {
            "Active global-search result must be visible"
        }
    }

    val visibleResults: List<WorkspaceSearchEntry>
        get() = results?.groups.orEmpty().flatMap { group -> group.results }

    val activeResult: WorkspaceSearchEntry?
        get() = visibleResults.firstOrNull { result -> result.key == activeResultKey }

    fun attachGeneration(generationId: String): WorkspaceGlobalSearchState {
        require(generationId.isNotBlank()) { "Global-search generation must not be blank" }
        if (queryState.generationId == generationId) return this
        return WorkspaceGlobalSearchState(
            queryState = WorkspaceGlobalSearchQueryState(generationId = generationId),
        )
    }

    fun updateQuery(query: String): WorkspaceGlobalSearchState {
        if (query == queryState.query) return this
        val loading = query.isNotBlank() && queryState.generationId != null
        return copy(
            queryState =
                queryState.copy(
                    query = query,
                    requestRevision = queryState.requestRevision + 1,
                    loading = loading,
                    error = null,
                ),
            results = null,
            activeResultKey = null,
        )
    }

    fun pendingRequest(): WorkspaceGlobalSearchRequest? {
        val generationId = queryState.generationId ?: return null
        if (!queryState.loading || queryState.query.isBlank()) return null
        return WorkspaceGlobalSearchRequest(
            generationId = generationId,
            revision = queryState.requestRevision,
            query = queryState.query,
            cachedCatalog = catalog,
        )
    }

    fun accept(completion: WorkspaceGlobalSearchCompletion): WorkspaceGlobalSearchState {
        if (!matches(completion.request)) return this
        return when (completion) {
            is WorkspaceGlobalSearchCompletion.Success ->
                copy(
                    queryState = queryState.copy(loading = false, error = null),
                    catalog = completion.catalog,
                    results = completion.results,
                    activeResultKey = completion.results.firstResultKey(),
                )

            is WorkspaceGlobalSearchCompletion.Failure ->
                copy(
                    queryState = queryState.copy(loading = false, error = completion.message),
                    catalog = completion.catalog ?: catalog,
                    results = null,
                    activeResultKey = null,
                )
        }
    }

    fun activate(resultKey: String?): WorkspaceGlobalSearchState {
        require(resultKey == null || visibleResults.any { result -> result.key == resultKey }) {
            "Active global-search result must be visible"
        }
        if (resultKey == activeResultKey) return this
        return copy(activeResultKey = resultKey)
    }

    fun moveActive(offset: Int): WorkspaceGlobalSearchState {
        if (visibleResults.isEmpty() || offset == 0) return this
        val currentIndex = visibleResults.indexOfFirst { result -> result.key == activeResultKey }
        val origin = currentIndex.takeIf { index -> index >= 0 } ?: if (offset > 0) -1 else 0
        val nextIndex = (origin + offset).mod(visibleResults.size)
        return activate(visibleResults[nextIndex].key)
    }

    private fun matches(request: WorkspaceGlobalSearchRequest): Boolean =
        queryState.generationId == request.generationId &&
            queryState.requestRevision == request.revision &&
            queryState.query == request.query &&
            queryState.loading
}

private fun WorkspaceSearchResults.firstResultKey(): String? =
    groups
        .firstOrNull()
        ?.results
        ?.firstOrNull()
        ?.key
