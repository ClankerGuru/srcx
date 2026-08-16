@file:Suppress("FunctionNaming")

package zone.clanker.docx.web.atlas.dom

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.WebElementView
import org.w3c.dom.HTMLDivElement
import zone.clanker.docx.web.atlas.AtlasController
import zone.clanker.docx.web.atlas.AtlasLayoutMode
import zone.clanker.docx.web.atlas.activeSelectionAtlasFrame
import zone.clanker.docx.web.atlas.projection.filteredOverview
import zone.clanker.docx.web.atlas.semanticMapDelivery
import zone.clanker.docx.web.atlas.updateLayoutMode
import zone.clanker.docx.web.state.ViewerState
import zone.clanker.docx.web.state.WorkspaceGlobalSearchState
import zone.clanker.report.model.AtlasFrame
import zone.clanker.report.model.AtlasFrameJson
import zone.clanker.report.model.ProjectGraphShard
import zone.clanker.report.model.ProjectSnapshot
import kotlin.math.roundToInt

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun AtlasDomSurface(
    state: ViewerState.Ready,
    controller: AtlasController,
    bindings: AtlasDomSurfaceBindings,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val frameModel = atlasDomFrameModel(state, controller)
    val frame = frameModel.frame
    val detailedOverview =
        state.site.atlasOverview?.let { overview ->
            controller.session?.filters?.let { filters -> overview.filteredOverview(filters) } ?: overview
        }
    val detailedOverviewJson =
        remember(state.site.manifest.generationId, detailedOverview) {
            detailedOverview?.let(AtlasFrameJson::encode)
        }
    val runtime = remember { AtlasDomRuntime() }
    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxSize()
                .onSizeChanged { size ->
                    bindings.onContentHeightChanged((size.height / density.density).roundToInt())
                },
    ) {
        val layoutMode = if (maxWidth >= WIDE_LAYOUT_BREAKPOINT) AtlasLayoutMode.WIDE else AtlasLayoutMode.COMPACT
        SideEffect {
            controller.updateLayoutMode(layoutMode)
            frame?.let { settledFrame ->
                controller.graph.updateFrame(
                    revision = frameModel.requestedFrameRevision,
                    totalNodes = settledFrame.totalNodeCount,
                    visibleNodes = settledFrame.nodes.map { node -> node.id },
                    pages = settledFrame.pageCount,
                    actualPage = settledFrame.pageIndex,
                )
            }
        }
        WebElementView<HTMLDivElement>(
            factory = ::createAtlasInteropHost,
            modifier = Modifier.fillMaxSize(),
            update = { root ->
                val startedAt = atlasDomTimestamp()
                val globalSearch = bindings.globalSearch()
                val observedModel = atlasDomSurfaceModel(state, globalSearch, detailedOverviewJson, controller)
                val modelReadyAt = atlasDomTimestamp()
                runtime.update(root, observedModel, controller, bindings.actions)
                root.setAttribute("data-docx-atlas-model-update-ms", (modelReadyAt - startedAt).toString())
                root.setAttribute("data-docx-atlas-surface-update-ms", (atlasDomTimestamp() - startedAt).toString())
            },
            onRelease = runtime::release,
        )
    }
}

internal data class AtlasDomSurfaceBindings(
    val globalSearch: () -> WorkspaceGlobalSearchState,
    val actions: AtlasDomActions,
    val onContentHeightChanged: (Int) -> Unit,
)

private fun atlasDomSurfaceModel(
    state: ViewerState.Ready,
    globalSearch: WorkspaceGlobalSearchState,
    detailedOverviewJson: String?,
    controller: AtlasController,
): AtlasDomSurfaceModel {
    val frameModel = atlasDomFrameModel(state, controller)
    val semanticMapDelivery = controller.semanticMapDelivery()
    val slice = semanticMapDelivery?.slice
    val preserveDetailedOverview = controller.scopeSelection.isOverview
    return AtlasDomSurfaceModel(
        summary = state.site.summary,
        dashboard = state.site.dashboard,
        generationId = state.site.manifest.generationId,
        projectShardReferences = state.site.manifest.projectShards,
        selectedProject =
            state.site.summary.projects
                .firstOrNull { project -> project.id == controller.selectedProjectId },
        selectedBuildId = controller.selectedBuildId,
        selectedBuildIds = controller.selectedBuildIds,
        selectedProjectIds = controller.selectedProjectIds,
        activeSection = controller.activeSection,
        requestedSection = controller.requestedSection,
        sectionNavigationRevision = controller.sectionNavigationRevision,
        project = state.project,
        projectLoading = state.projectLoading,
        projectError = state.projectError,
        buildProjects = state.buildProjects,
        buildLoading = state.buildLoading,
        buildError = state.buildError,
        evidence = state.evidence,
        globalSearch = globalSearch,
        frame = frameModel.frame,
        frameJson = detailedOverviewJson.takeIf { preserveDetailedOverview },
        slice = slice,
        semanticMapDelivery = semanticMapDelivery,
        preserveDetailedOverview = preserveDetailedOverview,
        requestedFrameRevision = frameModel.requestedFrameRevision,
        bridgeEnabled = controller.graph.bridgeEnabled,
    )
}

private fun atlasDomFrameModel(
    state: ViewerState.Ready,
    controller: AtlasController,
): AtlasDomFrameModel {
    val selection = controller.scopeSelection
    val expectedProjectIds =
        selection
            .selectedProjects(state.site.summary)
            .mapTo(mutableSetOf(), ProjectSnapshot::id)
    val loadedProjectIds = state.buildProjects.mapTo(mutableSetOf(), ProjectGraphShard::projectId)
    val activeFrame =
        state.buildProjects
            .takeIf {
                !selection.isOverview &&
                    expectedProjectIds.isNotEmpty() &&
                    state.loadedProjectIds == expectedProjectIds &&
                    loadedProjectIds == expectedProjectIds
            }?.let { projects ->
                activeSelectionAtlasFrame(state.site.summary, selection, projects, controller.graph)
            }
    val frame =
        activeFrame?.frame
            ?: state.site.atlasOverview.takeIf { selection.isOverview }
    return AtlasDomFrameModel(
        frame = frame,
        requestedFrameRevision = activeFrame?.requestedRevision ?: controller.graph.requestedFrameRevision,
    )
}

private data class AtlasDomFrameModel(
    val frame: AtlasFrame?,
    val requestedFrameRevision: Int,
)

private val WIDE_LAYOUT_BREAKPOINT = 980.dp
