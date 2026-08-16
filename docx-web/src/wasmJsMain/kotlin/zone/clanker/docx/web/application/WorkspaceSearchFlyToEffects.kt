@file:Suppress("FunctionNaming")

package zone.clanker.docx.web.application

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import zone.clanker.docx.web.atlas.AtlasController
import zone.clanker.docx.web.atlas.session.AtlasRequestState
import zone.clanker.report.model.WorkspaceSearchEntry

@Composable
internal fun WorkspaceSearchFlyToEffects(
    entry: WorkspaceSearchEntry?,
    controller: AtlasController,
    onCompleted: () -> Unit,
) {
    val request = controller.session?.request
    val slice = (request as? AtlasRequestState.Settled)?.slice
    val targetVisible = entry != null && slice?.content?.nodes?.any { node -> node.id == entry.id } == true
    LaunchedEffect(entry?.key, targetVisible) {
        val target = entry ?: return@LaunchedEffect
        if (!targetVisible) return@LaunchedEffect
        controller.graph.flyToNode(target.id)
        onCompleted()
    }
}
