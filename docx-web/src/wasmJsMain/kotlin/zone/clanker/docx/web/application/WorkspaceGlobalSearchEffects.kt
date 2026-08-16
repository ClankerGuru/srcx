package zone.clanker.docx.web.application

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.CancellationException
import zone.clanker.docx.web.site.LoadedWorkspaceSite
import zone.clanker.docx.web.site.WorkspaceSiteLoader
import zone.clanker.docx.web.state.WorkspaceGlobalSearchCompletion
import zone.clanker.docx.web.state.WorkspaceGlobalSearchRequest
import zone.clanker.docx.web.state.WorkspaceGlobalSearchState

@Composable
internal fun workspaceGlobalSearchEffects(
    loader: WorkspaceSiteLoader,
    site: LoadedWorkspaceSite?,
    state: WorkspaceGlobalSearchState,
    onCompletion: (WorkspaceGlobalSearchCompletion) -> Unit,
) {
    val request = state.pendingRequest()
    val currentOnCompletion = rememberUpdatedState(onCompletion)
    LaunchedEffect(site?.manifest?.generationId, request?.revision) {
        val currentRequest = request ?: return@LaunchedEffect
        val currentSite =
            site?.takeIf { candidate ->
                candidate.manifest.generationId == currentRequest.generationId
            } ?: return@LaunchedEffect
        val completion = loadGlobalSearch(loader, currentSite, currentRequest)
        currentOnCompletion.value(completion)
    }
}

private suspend fun loadGlobalSearch(
    loader: WorkspaceSiteLoader,
    site: LoadedWorkspaceSite,
    request: WorkspaceGlobalSearchRequest,
): WorkspaceGlobalSearchCompletion =
    runCatching {
        val catalog = request.cachedCatalog ?: loader.loadSearchCatalog(site)?.catalog
        if (catalog == null) {
            WorkspaceGlobalSearchCompletion.Failure(
                request = request,
                message = "This workspace generation does not provide a static search catalog.",
            )
        } else {
            WorkspaceGlobalSearchCompletion.Success(
                request = request,
                catalog = catalog,
                results = loader.search(site, catalog, request.query),
            )
        }
    }.fold(
        onSuccess = { completion -> completion },
        onFailure = { error -> searchFailure(request, error) },
    )

private fun searchFailure(
    request: WorkspaceGlobalSearchRequest,
    error: Throwable,
): WorkspaceGlobalSearchCompletion.Failure {
    if (error is CancellationException) throw error
    return WorkspaceGlobalSearchCompletion.Failure(
        request = request,
        catalog = request.cachedCatalog,
        message = error.message ?: "The workspace search index could not be loaded.",
    )
}
