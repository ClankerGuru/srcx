package zone.clanker.docx.web.atlas.source

import kotlinx.browser.window
import kotlinx.coroutines.await
import zone.clanker.docx.web.atlas.hierarchy.workspaceSourceHierarchySlice
import zone.clanker.docx.web.site.GenerationProjectCache
import zone.clanker.docx.web.site.LoadedWorkspaceSite
import zone.clanker.docx.web.site.WorkspaceSiteLoader
import zone.clanker.report.model.WorkspaceGraphRequest
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsString
import kotlin.js.Promise

internal fun browserWorkspaceGraphSliceSource(
    loader: WorkspaceSiteLoader,
    site: LoadedWorkspaceSite,
    cache: GenerationProjectCache,
): WorkspaceGraphSliceSource {
    val static =
        StaticWorkspaceGraphSliceSource(
            summary = site.summary,
            projects =
                ProjectGraphShardSource { projectId ->
                    cache.get(site.manifest.generationId, projectId)
                        ?: loader.loadProjectResource(site, projectId).let { resource ->
                            cache.put(site.manifest.generationId, resource)
                            resource.shard
                        }
                },
            search = staticSearchProjectSource(loader, site),
            projector =
                WorkspaceGraphSliceProjector { request, projects ->
                    workspaceSourceHierarchySlice(
                        summary = site.summary,
                        generationId = site.manifest.generationId,
                        request = request,
                        loadedProjects = projects,
                    )
                },
        )
    val endpoint = mountedWorkspaceGraphEndpoint(window.location.pathname) ?: return static
    val live = LiveWorkspaceGraphSliceSource(endpoint, BrowserWorkspaceGraphHttpTransport)
    return EndpointFallbackWorkspaceGraphSliceSource(live, static)
}

private fun staticSearchProjectSource(
    loader: WorkspaceSiteLoader,
    site: LoadedWorkspaceSite,
): WorkspaceGraphSearchProjectSource =
    WorkspaceGraphSearchProjectSource { request ->
        if (!request.hasProjectOwnedSearch()) {
            emptyList()
        } else {
            loader
                .loadSearchCatalog(site)
                ?.catalog
                ?.let { catalog ->
                    loader
                        .search(
                            site = site,
                            catalog = catalog,
                            query = request.view.filter.query,
                            resultLimitPerGroup = MAX_STATIC_SEARCH_RESULTS_PER_GROUP,
                        ).groups
                        .flatMap { group -> group.results }
                        .mapNotNull { entry -> entry.target.location.projectId }
                        .distinct()
                        .sorted()
                        .take(MAX_STATIC_SEARCH_RESULTS_PER_GROUP)
                }.orEmpty()
        }
    }

private fun WorkspaceGraphRequest.hasProjectOwnedSearch(): Boolean =
    view.filter.query.isNotBlank() && view.filter.mayMatchProjectOwnedFacts()

private data object BrowserWorkspaceGraphHttpTransport : WorkspaceGraphHttpTransport {
    @OptIn(ExperimentalWasmJsInterop::class)
    override suspend fun post(
        endpoint: String,
        body: String,
    ): WorkspaceGraphHttpResponse {
        val packed: JsString = postWorkspaceGraph(endpoint, body).await()
        val value = packed.toString()
        val separator = value.indexOf('\n')
        require(separator > 0) { "Workspace-graph HTTP response is malformed" }
        return WorkspaceGraphHttpResponse(
            statusCode = value.substring(0, separator).toInt(),
            body = value.substring(separator + 1),
        )
    }
}

@OptIn(ExperimentalWasmJsInterop::class)
@Suppress("UnusedParameter")
private fun postWorkspaceGraph(
    endpoint: String,
    body: String,
): Promise<JsString> =
    js(
        """window.fetch(endpoint, {
            method: "POST",
            credentials: "same-origin",
            redirect: "error",
            headers: {
                "Accept": "application/json",
                "Content-Type": "application/json"
            },
            body: body
        }).then(async (response) => String(response.status) + "\n" + await response.text())""",
    )

private const val MAX_STATIC_SEARCH_RESULTS_PER_GROUP: Int = 8
