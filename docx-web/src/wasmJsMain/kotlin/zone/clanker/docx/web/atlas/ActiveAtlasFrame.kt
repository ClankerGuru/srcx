package zone.clanker.docx.web.atlas

import zone.clanker.docx.web.atlas.projection.AtlasProjectFrameRequest
import zone.clanker.docx.web.atlas.projection.AtlasRelationshipCountFilter
import zone.clanker.docx.web.atlas.projection.AtlasScopeSelection
import zone.clanker.docx.web.atlas.projection.atlasBuildFrame
import zone.clanker.docx.web.atlas.projection.atlasProjectFrame
import zone.clanker.docx.web.atlas.projection.atlasSelectionFrame
import zone.clanker.report.model.AtlasFrame
import zone.clanker.report.model.ProjectGraphShard
import zone.clanker.report.model.WorkspaceSummaryShard

internal data class ActiveAtlasFrame(
    val frame: AtlasFrame,
    val requestedRevision: Int,
)

internal fun activeAtlasFrame(
    summary: WorkspaceSummaryShard,
    project: ProjectGraphShard,
    controller: AtlasGraphController,
): ActiveAtlasFrame {
    val frame =
        atlasProjectFrame(
            summary = summary,
            project = project,
            request = controller.atlasFrameRequest(),
        )
    return ActiveAtlasFrame(frame, controller.requestedFrameRevision)
}

internal fun activeBuildAtlasFrame(
    summary: WorkspaceSummaryShard,
    buildId: String,
    projects: List<ProjectGraphShard>,
    controller: AtlasGraphController,
): ActiveAtlasFrame {
    val frame =
        atlasBuildFrame(
            summary = summary,
            buildId = buildId,
            projects = projects,
            request = controller.atlasFrameRequest(),
        )
    return ActiveAtlasFrame(frame, controller.requestedFrameRevision)
}

internal fun activeSelectionAtlasFrame(
    summary: WorkspaceSummaryShard,
    selection: AtlasScopeSelection,
    projects: List<ProjectGraphShard>,
    controller: AtlasGraphController,
): ActiveAtlasFrame {
    val frame =
        atlasSelectionFrame(
            summary = summary,
            selection = selection,
            projects = projects,
            request = controller.atlasFrameRequest(),
        )
    return ActiveAtlasFrame(frame, controller.requestedFrameRevision)
}

private fun AtlasGraphController.atlasFrameRequest(): AtlasProjectFrameRequest =
    AtlasProjectFrameRequest(
        lens = lens.atlasLens,
        query = searchQuery,
        searchTargets = searchTargets,
        sourceSetIds = sourceSetIds,
        symbolKinds = allowedSymbolKinds,
        allowedKinds = allowedRelationshipKinds,
        relationshipCountFilter =
            relationshipCountMoreThan?.let { moreThan ->
                AtlasRelationshipCountFilter(moreThan, relationshipDirection)
            },
        selectedNodeIds = listOfNotNull(selectedNodeId),
        selectedRelationshipId = selectedEdgeId,
    )
