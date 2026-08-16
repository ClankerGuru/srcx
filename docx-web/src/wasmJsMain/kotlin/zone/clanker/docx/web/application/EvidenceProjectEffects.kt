@file:Suppress("FunctionNaming")

package zone.clanker.docx.web.application

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import zone.clanker.docx.web.site.GenerationProjectCache
import zone.clanker.docx.web.site.LoadedWorkspaceSite
import zone.clanker.docx.web.site.WorkspaceSiteLoader
import zone.clanker.docx.web.state.EvidenceProjectState
import zone.clanker.docx.web.state.ViewerState

@Composable
internal fun EvidenceProjectEffects(
    loader: WorkspaceSiteLoader,
    cache: GenerationProjectCache,
    readyProvider: () -> ViewerState.Ready?,
    onEvidenceChange: (generationId: String, evidence: EvidenceProjectState) -> Unit,
) {
    val currentReadyProvider = rememberUpdatedState(readyProvider)
    val currentOnEvidenceChange = rememberUpdatedState(onEvidenceChange)
    LaunchedEffect(loader, cache) {
        snapshotFlow { currentReadyProvider.value()?.evidenceProjectRequest() }
            .filterNotNull()
            .collectLatest { request ->
                loadEvidenceProject(loader, cache, request, currentOnEvidenceChange.value)
            }
    }
}

private fun ViewerState.Ready.evidenceProjectRequest(): EvidenceProjectRequest? =
    evidence
        .takeIf(EvidenceProjectState::loading)
        ?.requestedProjectId
        ?.let { projectId ->
            EvidenceProjectRequest(
                site = site,
                projectId = projectId,
                evidence = evidence,
            )
        }

private suspend fun loadEvidenceProject(
    loader: WorkspaceSiteLoader,
    cache: GenerationProjectCache,
    request: EvidenceProjectRequest,
    onEvidenceChange: (generationId: String, evidence: EvidenceProjectState) -> Unit,
) {
    val generationId = request.site.manifest.generationId
    val result =
        runCatching {
            cache.get(generationId, request.projectId)
                ?: loader.loadProjectResource(request.site, request.projectId).let { resource ->
                    cache.put(generationId, resource)
                    resource.shard
                }
        }
    result.fold(
        onSuccess = { project ->
            onEvidenceChange(
                generationId,
                request.evidence.complete(request.projectId, project),
            )
        },
        onFailure = { error ->
            if (error is CancellationException) throw error
            onEvidenceChange(
                generationId,
                request.evidence.fail(
                    request.projectId,
                    error.message ?: "The selected evidence shard could not be loaded",
                ),
            )
        },
    )
}

private data class EvidenceProjectRequest(
    val site: LoadedWorkspaceSite,
    val projectId: String,
    val evidence: EvidenceProjectState,
)
