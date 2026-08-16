@file:Suppress("FunctionNaming")

package zone.clanker.docx.web.application

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import zone.clanker.docx.web.site.LoadedWorkspaceSite
import zone.clanker.docx.web.site.WorkspaceSiteLoader
import zone.clanker.docx.web.state.EvidenceSourceContentState
import zone.clanker.docx.web.state.ViewerState

@Composable
internal fun SourceContentEffects(
    loader: WorkspaceSiteLoader,
    readyProvider: () -> ViewerState.Ready?,
    onSourceChange: (generationId: String, source: EvidenceSourceContentState) -> Unit,
) {
    val currentReadyProvider = rememberUpdatedState(readyProvider)
    val currentOnSourceChange = rememberUpdatedState(onSourceChange)
    LaunchedEffect(loader) {
        snapshotFlow { currentReadyProvider.value()?.sourceContentRequest() }
            .filterNotNull()
            .collectLatest { request ->
                loadSourceContent(loader, request, currentOnSourceChange.value)
            }
    }
}

private fun ViewerState.Ready.sourceContentRequest(): SourceContentRequest? {
    val source = evidence.source.takeIf(EvidenceSourceContentState::loading)
    return source?.requestedProjectId?.let { projectId ->
        source.requestedFileId?.let { fileId ->
            SourceContentRequest(site, projectId, fileId, source)
        }
    }
}

private suspend fun loadSourceContent(
    loader: WorkspaceSiteLoader,
    request: SourceContentRequest,
    onSourceChange: (generationId: String, source: EvidenceSourceContentState) -> Unit,
) {
    val result = runCatching { loader.loadSourceContent(request.site, request.projectId, request.fileId) }
    result.fold(
        onSuccess = { loaded ->
            val nextSource =
                if (loaded == null) {
                    request.source.fail(
                        request.projectId,
                        request.fileId,
                        "Source text was not included for this file.",
                    )
                } else {
                    request.source.complete(
                        projectId = request.projectId,
                        fileId = request.fileId,
                        sourceContent = loaded.content,
                        sourceContentHash = loaded.contentHash,
                        sourceEncodedByteSize = loaded.encodedByteSize,
                    )
                }
            onSourceChange(request.site.manifest.generationId, nextSource)
        },
        onFailure = { error ->
            if (error is CancellationException) throw error
            onSourceChange(
                request.site.manifest.generationId,
                request.source.fail(
                    request.projectId,
                    request.fileId,
                    error.message ?: "The selected source body could not be loaded",
                ),
            )
        },
    )
}

private data class SourceContentRequest(
    val site: LoadedWorkspaceSite,
    val projectId: String,
    val fileId: String,
    val source: EvidenceSourceContentState,
)
