package zone.clanker.docx.web.atlas.projection

import zone.clanker.report.model.AtlasEdge
import zone.clanker.report.model.RelationshipEvidence
import zone.clanker.report.model.RelationshipSnapshot

internal class AtlasEdgeProjector(
    private val context: AtlasProjectionContext,
) {
    fun aggregate(
        relationships: List<RelationshipSnapshot>,
        endpoints: (RelationshipSnapshot) -> Pair<String, String>,
        analysisSteps: Set<Pair<String, String>>,
    ): List<AtlasEdge> =
        relationships
            .groupBy(endpoints)
            .values
            .map { records -> aggregateEdge(records.sortedBy(RelationshipSnapshot::id), endpoints, analysisSteps) }
            .sortedBy(AtlasEdge::id)

    fun visibleRelationships(
        ids: List<String>,
        predicate: (RelationshipSnapshot) -> Boolean,
    ): List<RelationshipSnapshot> =
        ids.mapNotNull(context.relationshipsById::get).filter(predicate).sortedBy(RelationshipSnapshot::id)

    fun requiredFileEndpoints(relationship: RelationshipSnapshot): Pair<String, String> =
        requireNotNull(context.fileEndpoints(relationship)) { "Atlas file relationship endpoints must be complete" }

    fun requiredSymbolEndpoints(relationship: RelationshipSnapshot): Pair<String, String> =
        requireNotNull(relationship.sourceSymbolId) to relationship.targetSymbolId

    private fun aggregateEdge(
        records: List<RelationshipSnapshot>,
        endpoints: (RelationshipSnapshot) -> Pair<String, String>,
        analysisSteps: Set<Pair<String, String>>,
    ): AtlasEdge {
        val (sourceId, targetId) = endpoints(records.first())
        val relationshipIds = records.map(RelationshipSnapshot::id)
        val referenceIds = records.map(RelationshipSnapshot::referenceId).distinct().sorted()
        val kindCounts = records.projectedCounts(RelationshipSnapshot::kind) { kind -> kind.label }
        val evidenceCounts =
            records.projectedCounts(RelationshipSnapshot::resolutionEvidence) { evidence -> evidence.label }
        return AtlasEdge(
            id = relationshipIds.first(),
            sourceId = sourceId,
            targetId = targetId,
            category = records.projectedCategory(),
            relationshipIds = relationshipIds,
            referenceIds = referenceIds,
            recordCount = records.size,
            kindCounts = kindCounts,
            evidenceCounts = evidenceCounts,
            crossBuild = context.buildIdForNode(sourceId) != context.buildIdForNode(targetId),
            hasHeuristic = records.any { it.resolutionEvidence == RelationshipEvidence.HEURISTIC },
            isObservedCycleEdge = records.any { it.id in context.cycles.observedRelationshipIds },
            isAnalysisCycleEdge = (sourceId to targetId) in analysisSteps,
        )
    }
}
