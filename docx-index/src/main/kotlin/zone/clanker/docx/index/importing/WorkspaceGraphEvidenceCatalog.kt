package zone.clanker.docx.index.importing

import zone.clanker.report.model.BuildEdgeSnapshot
import zone.clanker.report.model.RelationshipKind
import zone.clanker.report.model.WorkspaceGraphRelationKind
import zone.clanker.report.model.WorkspaceSummaryShard

internal fun indexedGraphFacts(
    summary: WorkspaceSummaryShard,
    rows: WorkspaceGraphSourceRows,
    descriptors: List<IndexedGraphDescriptor>,
): List<IndexedGraphFact> {
    val descriptorIds = descriptors.mapTo(mutableSetOf(), IndexedGraphDescriptor::id)
    val sourceSetsByFileId =
        rows.files.associate { file -> file.id to file.sourceSetId }
    val facts =
        summary.buildEdges.map(BuildEdgeSnapshot::indexedFact) +
            rows.relationships.map { relationship ->
                val sourceId =
                    relationship.sourceSymbolId?.takeIf(descriptorIds::contains)
                        ?: relationship.sourceFileId.takeIf(descriptorIds::contains)
                        ?: sourceSetsByFileId.getValue(relationship.sourceFileId)
                require(sourceId in descriptorIds && relationship.targetSymbolId in descriptorIds) {
                    "Indexed graph relationship lacks complete endpoint closure: ${relationship.id}"
                }
                IndexedGraphFact(
                    id = relationship.id,
                    kind = relationship.kind.graphKind(),
                    sourceId = sourceId,
                    targetId = relationship.targetSymbolId,
                )
            }
    val factsById = mutableMapOf<String, IndexedGraphFact>()
    facts.sortedWith(compareBy(IndexedGraphFact::id, { fact -> fact.kind.name })).forEach { fact ->
        val previous = factsById.put(fact.id, fact)
        require(previous == null || previous == fact) { "Indexed graph fact ID has conflicting evidence: ${fact.id}" }
    }
    return factsById.values.sortedWith(compareBy(IndexedGraphFact::id, { fact -> fact.kind.name }))
}

private fun BuildEdgeSnapshot.indexedFact(): IndexedGraphFact =
    IndexedGraphFact(
        id = id,
        kind = WorkspaceGraphRelationKind.BUILD_DEPENDS_ON,
        sourceId = sourceBuildId,
        targetId = targetBuildId,
    )

private fun RelationshipKind.graphKind(): WorkspaceGraphRelationKind =
    when (this) {
        RelationshipKind.IMPORT -> WorkspaceGraphRelationKind.IMPORT
        RelationshipKind.EXTENDS -> WorkspaceGraphRelationKind.EXTENDS
        RelationshipKind.IMPLEMENTS -> WorkspaceGraphRelationKind.IMPLEMENTS
        RelationshipKind.CALL -> WorkspaceGraphRelationKind.CALL
        RelationshipKind.CONSTRUCTOR -> WorkspaceGraphRelationKind.CONSTRUCTOR
        RelationshipKind.NAME_REFERENCE -> WorkspaceGraphRelationKind.NAME_REFERENCE
        RelationshipKind.TYPE_REFERENCE -> WorkspaceGraphRelationKind.TYPE_REFERENCE
        RelationshipKind.PROPERTY_TYPE -> WorkspaceGraphRelationKind.PROPERTY_TYPE
        RelationshipKind.PARAMETER_TYPE -> WorkspaceGraphRelationKind.PARAMETER_TYPE
        RelationshipKind.RETURN_TYPE -> WorkspaceGraphRelationKind.RETURN_TYPE
    }
