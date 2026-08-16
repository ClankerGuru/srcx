package zone.clanker.docx.web.atlas.projection

import zone.clanker.report.model.AtlasFrame
import zone.clanker.report.model.AtlasLens
import zone.clanker.report.model.ProjectGraphShard
import zone.clanker.report.model.WorkspaceSummaryShard

/** Projects one complete selected project/source scope into the typed Atlas renderer envelope. */
fun atlasProjectFrame(
    summary: WorkspaceSummaryShard,
    project: ProjectGraphShard,
    request: AtlasProjectFrameRequest,
): AtlasFrame = AtlasProjector(summary, project).projectFrame(request)

/** Projects a bounded file frame into the only envelope accepted by the Atlas D3 bridge. */
fun atlasFileFrame(
    summary: WorkspaceSummaryShard,
    project: ProjectGraphShard,
    frame: BoundedFileGraphFrame,
    selectedNodeIds: List<String> = emptyList(),
    selectedRelationshipId: String? = null,
): AtlasFrame = AtlasProjector(summary, project).files(frame, selectedNodeIds, selectedRelationshipId)

/** Projects a bounded symbol frame into the only envelope accepted by the Atlas D3 bridge. */
fun atlasSymbolFrame(
    summary: WorkspaceSummaryShard,
    project: ProjectGraphShard,
    frame: BoundedGraphFrame,
    selectedNodeIds: List<String> = emptyList(),
    selectedRelationshipId: String? = null,
): AtlasFrame = AtlasProjector(summary, project).symbols(frame, selectedNodeIds, selectedRelationshipId)

private class AtlasProjector(
    summary: WorkspaceSummaryShard,
    project: ProjectGraphShard,
) {
    private val context = AtlasProjectionContext(summary, project)
    private val fileProjector = AtlasFileFrameProjector(context)
    private val symbolProjector = AtlasSymbolFrameProjector(context)
    private val mixedProjector = AtlasMixedFrameProjector(context)

    fun projectFrame(request: AtlasProjectFrameRequest): AtlasFrame {
        val relationships = context.eligibleRelationships(request)
        return when (request.lens) {
            AtlasLens.FILES ->
                fileProjector.project(
                    frame = fileProjector.complete(request, relationships),
                    selection = AtlasRequestedSelection(request.selectedNodeIds, request.selectedRelationshipId),
                    totalRelationshipRecordCount = relationships.size,
                )

            AtlasLens.SYMBOLS ->
                symbolProjector.project(
                    frame = symbolProjector.complete(request, relationships),
                    selection = AtlasRequestedSelection(request.selectedNodeIds, request.selectedRelationshipId),
                    totalRelationshipRecordCount = relationships.count { it.sourceSymbolId != null },
                )

            AtlasLens.PROBLEMS -> mixedProjector.problems(request, relationships)
            AtlasLens.CYCLES -> mixedProjector.cycles(request, relationships)
        }
    }

    fun files(
        frame: BoundedFileGraphFrame,
        selectedNodeIds: List<String>,
        selectedRelationshipId: String?,
    ): AtlasFrame =
        fileProjector.project(
            frame = frame,
            selection = AtlasRequestedSelection(selectedNodeIds, selectedRelationshipId),
        )

    fun symbols(
        frame: BoundedGraphFrame,
        selectedNodeIds: List<String>,
        selectedRelationshipId: String?,
    ): AtlasFrame =
        symbolProjector.project(
            frame = frame,
            selection = AtlasRequestedSelection(selectedNodeIds, selectedRelationshipId),
        )
}
