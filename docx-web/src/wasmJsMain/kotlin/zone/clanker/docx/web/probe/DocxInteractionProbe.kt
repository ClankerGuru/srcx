package zone.clanker.docx.web.probe

import zone.clanker.docx.web.atlas.AtlasController
import zone.clanker.docx.web.atlas.AtlasGraphController
import zone.clanker.docx.web.atlas.inspectorContent
import zone.clanker.docx.web.atlas.settledGraphSlice
import zone.clanker.docx.web.catalog.visibleProjects
import zone.clanker.docx.web.state.ViewerState

internal data class DocxInteractionProbe(
    val workspace: DocxWorkspaceProbe,
    val graphScope: DocxGraphScopeProbe,
    val graphFrame: DocxGraphFrameProbe,
    val graphViewport: DocxGraphViewportProbe,
    val selection: DocxSelectionProbe,
    val command: DocxCommandProbe,
)

internal data class DocxWorkspaceProbe(
    val layoutMode: String,
    val filterQuery: String,
    val visibleProjectIds: List<String>,
    val selectedBuildId: String,
    val selectedProjectId: String,
    val selectedBuildIds: List<String>,
    val selectedProjectIds: List<String>,
    val focusMap: Boolean,
)

internal data class DocxGraphScopeProbe(
    val scope: String,
    val lens: String,
    val sourceSet: String,
    val kindFilter: String,
    val searchQuery: String,
    val filters: DocxGraphFilterProbe,
)

internal data class DocxGraphFilterProbe(
    val searchTargets: String,
    val relationshipCountMoreThan: String,
    val relationshipDirection: String,
)

internal data class DocxGraphFrameProbe(
    val totalNodes: Int,
    val visibleNodeIds: List<String>,
    val page: Int,
    val pageCount: Int,
    val settled: Boolean,
)

internal data class DocxGraphViewportProbe(
    val scale: Float,
    val panX: Float,
    val panY: Float,
    val mode: String,
    val height: Float,
)

internal data class DocxSelectionProbe(
    val selectedNodeId: String,
    val selectedEdgeId: String,
    val inspectorContent: String,
)

internal data class DocxCommandProbe(
    val revision: Int,
    val error: String?,
)

internal fun ViewerState.interactionProbe(controller: AtlasController): DocxInteractionProbe? =
    (this as? ViewerState.Ready)
        ?.takeUnless { state ->
            state.projectLoading ||
                state.projectError != null ||
                state.buildLoading ||
                state.buildError != null
        }?.takeIf { state -> state.interactionScopeIsSettled(controller) }
        ?.buildInteractionProbe(controller)

private fun ViewerState.Ready.interactionScopeIsSettled(controller: AtlasController): Boolean {
    val selection = controller.scopeSelection
    val kind =
        when {
            selection.isOverview -> InteractionProbeScopeReadiness.Kind.OVERVIEW
            else -> InteractionProbeScopeReadiness.Kind.BOUNDED_GRAPH
        }
    return InteractionProbeScopeReadiness(
        kind = kind,
        atlasOverviewAvailable = site.atlasOverview != null,
        canonicalGraphSettled = controller.settledGraphSlice != null,
    ).isSettled()
}

private fun ViewerState.Ready.buildInteractionProbe(controller: AtlasController): DocxInteractionProbe =
    DocxInteractionProbe(
        workspace = workspaceProbe(controller),
        graphScope = controller.graphScopeProbe(),
        graphFrame = controller.graph.frameProbe(),
        graphViewport = controller.graph.viewportProbe(),
        selection = selectionProbe(controller.graph),
        command = controller.commandProbe(),
    )

private fun ViewerState.Ready.workspaceProbe(controller: AtlasController): DocxWorkspaceProbe {
    val filteredProjects = visibleProjects(site.summary, controller.projectFilter)
    return DocxWorkspaceProbe(
        layoutMode = controller.layoutMode.attributeValue,
        filterQuery = controller.projectFilter,
        visibleProjectIds = filteredProjects.map { project -> project.id },
        selectedBuildId = controller.selectedBuildId.orEmpty(),
        selectedProjectId = controller.selectedProjectId.orEmpty(),
        selectedBuildIds = controller.selectedBuildIds.sorted(),
        selectedProjectIds = controller.selectedProjectIds.sorted(),
        focusMap = controller.focusMap,
    )
}

private fun AtlasController.graphScopeProbe(): DocxGraphScopeProbe =
    DocxGraphScopeProbe(
        scope =
            when {
                selectedProjectIds.size == 1 -> "project"
                selectedProjectIds.isNotEmpty() -> "workspace"
                selectedBuildIds.size == 1 -> "build"
                else -> "workspace"
            },
        lens = graph.lens.attributeValue,
        sourceSet = graph.sourceSetIds.sorted().joinToString("|"),
        kindFilter = graph.relationshipFilterAttributeValue(),
        searchQuery = graph.searchQuery,
        filters = graph.filterProbe(),
    )

private fun AtlasGraphController.filterProbe(): DocxGraphFilterProbe =
    DocxGraphFilterProbe(
        searchTargets =
            searchTargets
                .map { target -> target.name.lowercase() }
                .sorted()
                .joinToString("|"),
        relationshipCountMoreThan = relationshipCountMoreThan?.toString().orEmpty(),
        relationshipDirection = relationshipDirection.name.lowercase(),
    )

private fun AtlasGraphController.frameProbe(): DocxGraphFrameProbe =
    DocxGraphFrameProbe(
        totalNodes = totalNodeCount,
        visibleNodeIds = visibleNodeIds,
        page = if (pageCount == 0) 0 else pageIndex + 1,
        pageCount = pageCount,
        settled = frameIsSettled,
    )

private fun AtlasGraphController.viewportProbe(): DocxGraphViewportProbe =
    DocxGraphViewportProbe(
        scale = scale,
        panX = pan.x,
        panY = pan.y,
        mode = mode.attributeValue,
        height = viewportHeightCss,
    )

private fun ViewerState.Ready.selectionProbe(graph: AtlasGraphController): DocxSelectionProbe =
    DocxSelectionProbe(
        selectedNodeId = graph.selectedNodeId.orEmpty(),
        selectedEdgeId = graph.selectedEdgeId.orEmpty(),
        inspectorContent = inspectorContent(listOfNotNull(project) + buildProjects, graph),
    )

private fun AtlasController.commandProbe(): DocxCommandProbe =
    DocxCommandProbe(completedCommandRevision, commandError)

private fun AtlasGraphController.relationshipFilterAttributeValue(): String =
    if (allRelationshipFiltersSelected) {
        "all"
    } else {
        relationshipFilters.map { filter -> filter.name.lowercase() }.sorted().joinToString("|")
    }
