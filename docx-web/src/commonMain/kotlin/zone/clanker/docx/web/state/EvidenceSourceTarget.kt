package zone.clanker.docx.web.state

import zone.clanker.report.model.ProjectGraphShard
import zone.clanker.report.model.ReferenceSnapshot

internal data class EvidenceSourceTarget(
    val projectId: String,
    val fileId: String,
)

internal fun evidenceSourceTarget(
    projects: List<ProjectGraphShard>,
    selectedNodeId: String?,
    selectedRelationshipIds: Set<String>,
    preferredRelationshipId: String?,
): EvidenceSourceTarget? =
    selectedNodeId?.let { nodeId -> projects.sourceTargetForNode(nodeId) }
        ?: projects.sourceTargetForRelationship(selectedRelationshipIds, preferredRelationshipId)

private fun List<ProjectGraphShard>.sourceTargetForNode(nodeId: String): EvidenceSourceTarget? =
    asSequence()
        .mapNotNull { project ->
            val fileId =
                project.files.firstOrNull { file -> file.id == nodeId }?.id
                    ?: project.symbols.firstOrNull { symbol -> symbol.id == nodeId }?.fileId
            fileId?.let { id ->
                NodeSourceCandidate(
                    target = EvidenceSourceTarget(project.ownerProjectId(id), id),
                    owned = id in project.ownedFileIds,
                )
            }
        }.sortedWith(
            compareBy<NodeSourceCandidate> { candidate -> !candidate.owned }
                .thenBy { candidate -> candidate.target.projectId }
                .thenBy { candidate -> candidate.target.fileId },
        ).firstOrNull()
        ?.target

private fun List<ProjectGraphShard>.sourceTargetForRelationship(
    relationshipIds: Set<String>,
    preferredRelationshipId: String?,
): EvidenceSourceTarget? =
    asSequence()
        .flatMap { project -> project.relationshipCandidates(relationshipIds).asSequence() }
        .sortedWith(
            compareBy<RelationshipSourceCandidate> { candidate -> !candidate.owned }
                .thenBy { candidate -> candidate.relationshipId != preferredRelationshipId }
                .thenBy(RelationshipSourceCandidate::relationshipId)
                .thenBy { candidate -> candidate.target.projectId },
        ).firstOrNull()
        ?.target

private fun ProjectGraphShard.relationshipCandidates(
    relationshipIds: Set<String>,
): List<RelationshipSourceCandidate> {
    if (relationshipIds.isEmpty()) return emptyList()
    val references = references.associateBy(ReferenceSnapshot::id)
    return relationships.mapNotNull { relationship ->
        if (relationship.id !in relationshipIds) return@mapNotNull null
        val sourceFileId = references[relationship.referenceId]?.sourceFileId ?: return@mapNotNull null
        RelationshipSourceCandidate(
            relationshipId = relationship.id,
            target = EvidenceSourceTarget(ownerProjectId(sourceFileId), sourceFileId),
            owned = sourceFileId in ownedFileIds,
        )
    }
}

private fun ProjectGraphShard.ownerProjectId(fileId: String): String {
    val sourceSetId = files.single { file -> file.id == fileId }.sourceSetId
    return sourceSets.single { sourceSet -> sourceSet.id == sourceSetId }.projectId
}

private data class NodeSourceCandidate(
    val target: EvidenceSourceTarget,
    val owned: Boolean,
)

private data class RelationshipSourceCandidate(
    val relationshipId: String,
    val target: EvidenceSourceTarget,
    val owned: Boolean,
)
