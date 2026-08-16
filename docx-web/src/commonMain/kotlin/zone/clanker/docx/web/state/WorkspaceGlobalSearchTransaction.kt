package zone.clanker.docx.web.state

import zone.clanker.report.model.WorkspaceSearchCatalog
import zone.clanker.report.model.WorkspaceSearchResults

internal data class WorkspaceGlobalSearchRequest(
    val generationId: String,
    val revision: Int,
    val query: String,
    val cachedCatalog: WorkspaceSearchCatalog?,
) {
    init {
        require(generationId.isNotBlank()) { "Global-search generation must not be blank" }
        require(revision > 0) { "Global-search request revision must be positive" }
        require(query.isNotBlank()) { "Global-search request query must not be blank" }
        require(cachedCatalog == null || cachedCatalog.generationId == generationId) {
            "Global-search cached catalog must belong to its request generation"
        }
    }
}

internal sealed interface WorkspaceGlobalSearchCompletion {
    val request: WorkspaceGlobalSearchRequest

    data class Success(
        override val request: WorkspaceGlobalSearchRequest,
        val catalog: WorkspaceSearchCatalog,
        val results: WorkspaceSearchResults,
    ) : WorkspaceGlobalSearchCompletion {
        init {
            require(catalog.generationId == request.generationId) {
                "Global-search result catalog must belong to its request generation"
            }
            require(results.query == request.query.trim()) {
                "Global-search results must belong to their request query"
            }
        }
    }

    data class Failure(
        override val request: WorkspaceGlobalSearchRequest,
        val catalog: WorkspaceSearchCatalog? = null,
        val message: String,
    ) : WorkspaceGlobalSearchCompletion {
        init {
            require(catalog == null || catalog.generationId == request.generationId) {
                "Global-search failure catalog must belong to its request generation"
            }
            require(message.isNotBlank()) { "Global-search failure message must not be blank" }
        }
    }
}
