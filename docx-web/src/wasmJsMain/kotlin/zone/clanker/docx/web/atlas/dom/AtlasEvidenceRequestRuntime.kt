package zone.clanker.docx.web.atlas.dom

import zone.clanker.docx.web.evidence.WorkspaceEvidenceInspectionRequest
import zone.clanker.docx.web.state.EvidenceSourceTarget
import zone.clanker.docx.web.state.evidenceSourceTarget
import zone.clanker.report.model.AtlasNodeType
import zone.clanker.report.model.RelationshipKind
import zone.clanker.report.model.WorkspaceEvidenceTarget
import zone.clanker.report.model.WorkspaceGraphNode
import zone.clanker.report.model.WorkspaceGraphNodeKind
import zone.clanker.report.model.WorkspaceGraphRelationKind
import zone.clanker.report.model.WorkspaceRelationshipOccurrenceSelector

internal object AtlasEvidenceRequestRuntime {
    data class SourceRequestPolicy(
        val retryFailure: Boolean,
        val hasExactInspection: Boolean,
    )

    fun requestProject(
        model: AtlasDomSurfaceModel,
        selection: GraphSelection,
        actions: AtlasDomEvidenceActions,
    ) {
        val projectId = model.selectedProjectId(selection) ?: return
        if (model.projectEvidenceIsAvailable(projectId)) return
        val directSource = model.directSourceTarget(selection)
        if (directSource != null && model.hasSourceReference(directSource)) return
        actions.onProjectRequested(projectId)
    }

    fun requestSource(
        model: AtlasDomSurfaceModel,
        selection: GraphSelection,
        policy: SourceRequestPolicy,
        actions: AtlasDomEvidenceActions,
    ) {
        val projects = model.evidenceProjects()
        val target =
            if (policy.hasExactInspection) {
                null
            } else {
                model.directSourceTarget(selection)
                    ?: evidenceSourceTarget(
                        projects = projects,
                        selectedNodeId = selection.nodeId,
                        selectedRelationshipIds = model.relationshipIds(selection.edgeId),
                        preferredRelationshipId = selection.edgeId,
                    )
            }
        val file =
            target?.let { selected ->
                projects
                    .firstOrNull { project -> project.projectId == selected.projectId }
                    ?.files
                    ?.firstOrNull { candidate -> candidate.id == selected.fileId }
            }
        val directlyLoadable = target?.takeIf(model::hasSourceReference)
        val unloadedFile = file?.takeIf { candidate -> candidate.content == null }
        val canLoad = directlyLoadable != null || unloadedFile != null
        if (
            target != null &&
            canLoad &&
            !model.evidence.source.requestIsSatisfied(target.projectId, target.fileId, policy.retryFailure)
        ) {
            actions.onSourceRequested(target.projectId, target.fileId)
        }
    }

    fun inspectionRequest(
        model: AtlasDomSurfaceModel,
        selection: GraphSelection,
    ): WorkspaceEvidenceInspectionRequest? {
        val target = WorkspaceEvidenceTarget(model.summary.workspace.id, model.generationId)
        return relationshipInspectionRequest(model, selection, target)
            ?: symbolInspectionRequest(model, selection, target)
    }
}

private fun AtlasDomSurfaceModel.directSourceTarget(selection: GraphSelection): EvidenceSourceTarget? {
    val frameNode = selection.nodeId?.let { nodeId -> frame?.nodes?.firstOrNull { node -> node.id == nodeId } }
    val frameProjectId = frameNode?.projectId
    if (frameNode?.type == AtlasNodeType.FILE && frameProjectId != null) {
        return EvidenceSourceTarget(frameProjectId, frameNode.sourceFileId ?: frameNode.id)
    }
    val semanticNode =
        selection.nodeId?.let { nodeId ->
            slice?.content?.nodes?.firstOrNull { node -> node.id == nodeId }
        }
    return semanticNode
        ?.takeIf { node -> node.kind == WorkspaceGraphNodeKind.FILE }
        ?.let { node -> sliceProjectId(selection)?.let { projectId -> EvidenceSourceTarget(projectId, node.id) } }
}

private fun AtlasDomSurfaceModel.hasSourceReference(target: EvidenceSourceTarget): Boolean =
    projectShardReferences
        .firstOrNull { project -> project.projectId == target.projectId }
        ?.sourceContents
        ?.any { source -> source.fileId == target.fileId } == true

private fun relationshipInspectionRequest(
    model: AtlasDomSurfaceModel,
    selection: GraphSelection,
    target: WorkspaceEvidenceTarget,
): WorkspaceEvidenceInspectionRequest.Relationship? {
    val edgeId = selection.edgeId
    val relation =
        edgeId
            ?.let { id ->
                model.slice
                    ?.content
                    ?.relations
                    ?.firstOrNull { candidate -> candidate.id == id }
            }
    val kind = relation?.kind?.relationshipKind
    return if (edgeId == null || relation == null || kind == null) {
        null
    } else {
        WorkspaceEvidenceInspectionRequest.Relationship(
            target = target,
            edgeId = edgeId,
            selector =
                WorkspaceRelationshipOccurrenceSelector(
                    kind = kind,
                    sourceNodeId = relation.endpoints.sourceNodeId,
                    targetNodeId = relation.endpoints.targetNodeId,
                ),
        )
    }
}

private fun symbolInspectionRequest(
    model: AtlasDomSurfaceModel,
    selection: GraphSelection,
    target: WorkspaceEvidenceTarget,
): WorkspaceEvidenceInspectionRequest.Symbol? {
    val nodeId = selection.nodeId ?: return null
    val semanticNode =
        model.slice
            ?.content
            ?.nodes
            ?.firstOrNull { candidate -> candidate.id == nodeId }
    val isSemanticSymbol =
        semanticNode?.kind == WorkspaceGraphNodeKind.TYPE || semanticNode?.kind == WorkspaceGraphNodeKind.MEMBER
    val evidenceProjects = model.evidenceProjects()
    val isEvidenceFile =
        semanticNode == null &&
            evidenceProjects.any { project -> nodeId in project.ownedFileIds || project.files.any { it.id == nodeId } }
    val isEvidenceSymbol =
        semanticNode == null &&
            !isEvidenceFile &&
            evidenceProjects.any { project ->
                nodeId in project.ownedSymbolIds || project.symbols.any { it.id == nodeId }
            }
    val isSymbol = isSemanticSymbol || isEvidenceSymbol
    return WorkspaceEvidenceInspectionRequest.Symbol(target, nodeId).takeIf { isSymbol }
}

private fun AtlasDomSurfaceModel.projectEvidenceIsAvailable(projectId: String): Boolean {
    if (evidenceProjects().any { project -> project.projectId == projectId }) return true
    return evidence.requestedProjectId == projectId && (evidence.loading || evidence.project != null)
}

private fun zone.clanker.docx.web.state.EvidenceSourceContentState.requestIsSatisfied(
    projectId: String,
    fileId: String,
    retryFailure: Boolean,
): Boolean =
    matches(projectId, fileId) &&
        (loading || content != null || (error != null && !retryFailure))

private fun AtlasDomSurfaceModel.selectedProjectId(selection: GraphSelection): String? =
    sliceProjectId(selection) ?: frameProjectId(selection)

private fun AtlasDomSurfaceModel.frameProjectId(selection: GraphSelection): String? {
    val currentFrame = frame ?: return null
    val selectedNode = selection.nodeId?.let { id -> currentFrame.nodes.firstOrNull { node -> node.id == id } }
    if (selectedNode?.type == AtlasNodeType.FILE || selectedNode?.type == AtlasNodeType.SYMBOL) {
        return selectedNode.projectId
    }
    val selectedEdge =
        selection.edgeId?.let { id ->
            currentFrame.edges.firstOrNull { edge -> edge.id == id || id in edge.relationshipIds }
        }
    return selectedEdge
        ?.takeIf { edge -> edge.relationshipIds.isNotEmpty() }
        ?.let { edge -> currentFrame.nodes.firstOrNull { node -> node.id == edge.sourceId } }
        ?.projectId
}

private fun AtlasDomSurfaceModel.relationshipIds(selectedEdgeId: String?): Set<String> {
    if (selectedEdgeId == null) return emptySet()
    val aggregate =
        frame?.edges?.firstOrNull { edge ->
            edge.id == selectedEdgeId || selectedEdgeId in edge.relationshipIds
        }
    return aggregate?.relationshipIds.orEmpty().toSet() + selectedEdgeId
}

private val WorkspaceGraphRelationKind.relationshipKind: RelationshipKind?
    get() = RelationshipKind.entries.firstOrNull { kind -> kind.name == name }

private fun AtlasDomSurfaceModel.sliceProjectId(selection: GraphSelection): String? {
    val semanticSlice = slice ?: return null
    val nodesById = semanticSlice.content.nodes.associateBy(WorkspaceGraphNode::id)
    val selectedNodeId =
        selection.nodeId
            ?: selection.edgeId
                ?.let { edgeId -> semanticSlice.content.relations.firstOrNull { relation -> relation.id == edgeId } }
                ?.endpoints
                ?.sourceNodeId
            ?: return null
    return generateSequence(nodesById[selectedNodeId]) { node -> node.hierarchy.parentId?.let(nodesById::get) }
        .firstOrNull { node -> node.kind == WorkspaceGraphNodeKind.PROJECT }
        ?.id
}
