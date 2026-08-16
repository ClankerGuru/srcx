@file:Suppress("FunctionNaming")

package zone.clanker.docx.web.application

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.yield
import zone.clanker.docx.web.atlas.AtlasController
import zone.clanker.docx.web.atlas.session.AtlasRequestCancelled
import zone.clanker.docx.web.atlas.session.AtlasRequestFailed
import zone.clanker.docx.web.atlas.session.AtlasRequestStarted
import zone.clanker.docx.web.atlas.session.AtlasRequestState
import zone.clanker.docx.web.atlas.session.AtlasRequestSucceeded
import zone.clanker.docx.web.atlas.session.workspaceGraphRequest
import zone.clanker.docx.web.atlas.source.browserWorkspaceGraphSliceSource
import zone.clanker.docx.web.site.GenerationProjectCache
import zone.clanker.docx.web.site.WorkspaceSiteLoader
import zone.clanker.docx.web.state.ViewerState
import zone.clanker.report.model.WorkspaceGraphFacet

@Composable
internal fun WorkspaceGraphEffects(
    loader: WorkspaceSiteLoader,
    projectCache: GenerationProjectCache,
    state: ViewerState,
    controller: AtlasController,
) {
    val ready = state as? ViewerState.Ready
    val source =
        remember(loader, projectCache, ready?.site?.manifest?.generationId) {
            ready?.let { loaded -> browserWorkspaceGraphSliceSource(loader, loaded.site, projectCache) }
        }
    LaunchedEffect(ready?.site?.manifest?.generationId, source) {
        if (ready == null || source == null) return@LaunchedEffect
        snapshotFlow { controller.session?.workspaceGraphRequest(WorkspaceGraphFacet.SOURCE) }
            .filterNotNull()
            .collectLatest { request ->
                controller.dispatch(AtlasRequestStarted(request))
                val pending = controller.session?.request as? AtlasRequestState.Pending ?: return@collectLatest
                yield()
                val result = runCatching { source.load(pending.request) }
                val cancellation = result.exceptionOrNull() as? CancellationException
                if (cancellation != null) {
                    controller.dispatch(AtlasRequestCancelled(pending.token))
                    throw cancellation
                }
                result.fold(
                    onSuccess = { slice ->
                        controller.dispatch(AtlasRequestSucceeded(pending.token, slice))
                    },
                    onFailure = { error ->
                        controller.dispatch(
                            AtlasRequestFailed(
                                token = pending.token,
                                message = error.message ?: "The bounded Atlas query failed",
                            ),
                        )
                    },
                )
            }
    }
}
