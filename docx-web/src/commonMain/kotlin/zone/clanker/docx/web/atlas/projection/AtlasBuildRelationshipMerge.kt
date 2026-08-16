package zone.clanker.docx.web.atlas.projection

import zone.clanker.report.model.AtlasCount
import zone.clanker.report.model.AtlasEdge
import zone.clanker.report.model.AtlasEdgeCategory
import zone.clanker.report.model.AtlasFrame
import zone.clanker.report.model.AtlasLens
import zone.clanker.report.model.AtlasNode
import zone.clanker.report.model.ProjectGraphShard
import zone.clanker.report.model.ReferenceSnapshot
import zone.clanker.report.model.RelationshipEvidence
import zone.clanker.report.model.RelationshipKind
import zone.clanker.report.model.RelationshipSnapshot

internal fun projectedRelationshipFacts(
    frames: List<Pair<ProjectGraphShard, AtlasFrame>>,
    request: AtlasProjectFrameRequest,
    evidence: AtlasBuildRawEvidence,
): List<AtlasBuildRelationshipFact> {
    val buildVisibleNodeIds =
        frames.flatMapTo(mutableSetOf()) { (_, frame) -> frame.nodes.map(AtlasNode::id) }
    return frames
        .flatMap { (project, frame) ->
            projectRelationshipFacts(project, frame, request, evidence, buildVisibleNodeIds)
        }.groupBy { fact -> fact.relationship.id }
        .entries
        .sortedBy(Map.Entry<String, List<AtlasBuildRelationshipFact>>::key)
        .map { (relationshipId, duplicates) -> mergeRelationshipFacts(relationshipId, duplicates, evidence) }
}

internal fun atlasBuildEdges(
    facts: List<AtlasBuildRelationshipFact>,
    nodes: List<AtlasNode>,
): List<AtlasEdge> {
    val nodesById = nodes.associateBy(AtlasNode::id)
    return facts
        .filter { fact -> fact.sourceId != fact.targetId }
        .groupBy { fact -> fact.sourceId to fact.targetId }
        .values
        .map { records -> mergedEdge(records.sortedBy { fact -> fact.relationship.id }, nodesById) }
        .sortedBy(AtlasEdge::id)
}

internal fun totalBuildRelationshipCount(
    projects: List<ProjectGraphShard>,
    request: AtlasProjectFrameRequest,
    completeFacts: List<AtlasBuildRelationshipFact>,
): Int =
    when (request.lens) {
        AtlasLens.FILES -> projects.eligibleRelationshipIds(request).size
        AtlasLens.SYMBOLS ->
            projects
                .flatMap(ProjectGraphShard::relationships)
                .filter { relationship -> relationship.isEligible(request) && relationship.sourceSymbolId != null }
                .mapTo(mutableSetOf(), RelationshipSnapshot::id)
                .size
        AtlasLens.PROBLEMS, AtlasLens.CYCLES -> completeFacts.size
    }

private fun projectRelationshipFacts(
    project: ProjectGraphShard,
    frame: AtlasFrame,
    request: AtlasProjectFrameRequest,
    evidence: AtlasBuildRawEvidence,
    buildVisibleNodeIds: Set<String>,
): List<AtlasBuildRelationshipFact> {
    val edgesByRelationshipId =
        frame.edges.flatMap { edge -> edge.relationshipIds.map { relationshipId -> relationshipId to edge } }.toMap()
    val visibleNodeIds = frame.nodes.mapTo(mutableSetOf(), AtlasNode::id)
    val local = AtlasLocalRelationshipIndex(project)
    return project.relationships.mapNotNull { relationship ->
        if (!local.isEligible(relationship, request)) {
            null
        } else {
            val edge = edgesByRelationshipId[relationship.id]
            val endpoints =
                if (request.lens == AtlasLens.PROBLEMS) {
                    local.visibleEndpoints(relationship, request.lens, buildVisibleNodeIds)
                } else {
                    edge?.let { it.sourceId to it.targetId }
                        ?: local.internalEndpoints(relationship, request.lens, visibleNodeIds)
                }
            endpoints?.let { (sourceId, targetId) ->
                AtlasBuildRelationshipFact(
                    relationship = evidence.relationshipsById.getValue(relationship.id),
                    sourceId = sourceId,
                    targetId = targetId,
                    isAnalysisCycleEdge = edge?.isAnalysisCycleEdge == true,
                    isObservedCycleEdge = relationship.id in evidence.observedRelationshipIds,
                )
            }
        }
    }
}

private fun mergeRelationshipFacts(
    relationshipId: String,
    facts: List<AtlasBuildRelationshipFact>,
    evidence: AtlasBuildRawEvidence,
): AtlasBuildRelationshipFact {
    require(facts.map { fact -> fact.sourceId to fact.targetId }.distinct().size == 1) {
        "Duplicate Atlas relationship $relationshipId must project to the same raw endpoints"
    }
    val first = facts.first()
    return first.copy(
        relationship = evidence.relationshipsById.getValue(relationshipId),
        isAnalysisCycleEdge = facts.any(AtlasBuildRelationshipFact::isAnalysisCycleEdge),
        isObservedCycleEdge = facts.any(AtlasBuildRelationshipFact::isObservedCycleEdge),
    )
}

private fun mergedEdge(
    facts: List<AtlasBuildRelationshipFact>,
    nodesById: Map<String, AtlasNode>,
): AtlasEdge {
    val relationships = facts.map(AtlasBuildRelationshipFact::relationship)
    val first = facts.first()
    return AtlasEdge(
        id = relationships.first().id,
        sourceId = first.sourceId,
        targetId = first.targetId,
        category = relationships.predominantCategory(),
        relationshipIds = relationships.map(RelationshipSnapshot::id),
        referenceIds = relationships.map(RelationshipSnapshot::referenceId).distinct().sorted(),
        recordCount = relationships.size,
        kindCounts = relationships.counts(RelationshipSnapshot::kind) { kind -> kind.label },
        evidenceCounts = relationships.counts(RelationshipSnapshot::resolutionEvidence) { item -> item.label },
        crossBuild = nodesById.getValue(first.sourceId).buildId != nodesById.getValue(first.targetId).buildId,
        hasHeuristic =
            relationships.any { relationship ->
                relationship.resolutionEvidence == RelationshipEvidence.HEURISTIC
            },
        isObservedCycleEdge = facts.any(AtlasBuildRelationshipFact::isObservedCycleEdge),
        isAnalysisCycleEdge = facts.any(AtlasBuildRelationshipFact::isAnalysisCycleEdge),
    )
}

private class AtlasLocalRelationshipIndex(
    project: ProjectGraphShard,
) {
    private val referencesById = project.references.associateBy(ReferenceSnapshot::id)
    private val fileIdBySymbolId = project.symbols.associate { symbol -> symbol.id to symbol.fileId }
    private val observedRelationshipIds = project.cycles.flatMapTo(mutableSetOf()) { cycle -> cycle.relationshipIds }

    fun isEligible(
        relationship: RelationshipSnapshot,
        request: AtlasProjectFrameRequest,
    ): Boolean =
        relationship.isEligible(request) &&
            (request.lens != AtlasLens.CYCLES || relationship.id in observedRelationshipIds)

    fun internalEndpoints(
        relationship: RelationshipSnapshot,
        lens: AtlasLens,
        visibleNodeIds: Set<String>,
    ): Pair<String, String>? =
        visibleEndpoints(relationship, lens, visibleNodeIds)?.takeIf { pair -> pair.first == pair.second }

    fun visibleEndpoints(
        relationship: RelationshipSnapshot,
        lens: AtlasLens,
        visibleNodeIds: Set<String>,
    ): Pair<String, String>? {
        val endpoints =
            when (lens) {
                AtlasLens.FILES -> fileEndpoints(relationship)
                AtlasLens.SYMBOLS -> symbolEndpoints(relationship)
                AtlasLens.PROBLEMS, AtlasLens.CYCLES ->
                    symbolEndpoints(relationship)?.takeIf { it.isVisible(visibleNodeIds) }
                        ?: fileEndpoints(relationship)
            }
        return endpoints?.takeIf { pair -> pair.isVisible(visibleNodeIds) }
    }

    private fun fileEndpoints(relationship: RelationshipSnapshot): Pair<String, String>? {
        val sourceId = referencesById[relationship.referenceId]?.sourceFileId
        val targetId = fileIdBySymbolId[relationship.targetSymbolId]
        return if (sourceId == null || targetId == null) null else sourceId to targetId
    }

    private fun symbolEndpoints(relationship: RelationshipSnapshot): Pair<String, String>? =
        relationship.sourceSymbolId?.let { sourceId -> sourceId to relationship.targetSymbolId }
}

private fun Pair<String, String>.isVisible(visibleNodeIds: Set<String>): Boolean =
    first in visibleNodeIds && second in visibleNodeIds

private fun RelationshipSnapshot.isEligible(request: AtlasProjectFrameRequest): Boolean =
    kind != RelationshipKind.IMPORT && kind in request.allowedKinds

private fun List<ProjectGraphShard>.eligibleRelationshipIds(request: AtlasProjectFrameRequest): Set<String> =
    flatMap(ProjectGraphShard::relationships)
        .filter { relationship -> relationship.isEligible(request) }
        .mapTo(mutableSetOf(), RelationshipSnapshot::id)

private fun RelationshipKind.atlasCategory(): AtlasEdgeCategory =
    when (this) {
        RelationshipKind.IMPORT -> AtlasEdgeCategory.IMPORTS
        RelationshipKind.EXTENDS, RelationshipKind.IMPLEMENTS -> AtlasEdgeCategory.INHERITANCE
        RelationshipKind.CALL, RelationshipKind.CONSTRUCTOR -> AtlasEdgeCategory.CALLS
        RelationshipKind.NAME_REFERENCE,
        RelationshipKind.TYPE_REFERENCE,
        RelationshipKind.PROPERTY_TYPE,
        RelationshipKind.PARAMETER_TYPE,
        RelationshipKind.RETURN_TYPE,
        -> AtlasEdgeCategory.REFERENCES
    }

private fun List<RelationshipSnapshot>.predominantCategory(): AtlasEdgeCategory =
    groupBy { relationship -> relationship.kind.atlasCategory() }
        .entries
        .sortedWith(
            compareByDescending<Map.Entry<AtlasEdgeCategory, List<RelationshipSnapshot>>> { (_, records) ->
                records.size
            }.thenBy { entry -> ATLAS_CATEGORY_PRIORITY.getValue(entry.key) },
        ).first()
        .key

private fun <T : Enum<T>> List<RelationshipSnapshot>.counts(
    selector: (RelationshipSnapshot) -> T,
    label: (T) -> String,
): List<AtlasCount> =
    groupBy(selector)
        .entries
        .sortedBy { (value) -> value.name }
        .map { (value, records) -> AtlasCount(value.name, label(value), records.size) }

private val ATLAS_CATEGORY_PRIORITY =
    mapOf(
        AtlasEdgeCategory.INHERITANCE to 0,
        AtlasEdgeCategory.CALLS to 1,
        AtlasEdgeCategory.REFERENCES to 2,
        AtlasEdgeCategory.IMPORTS to 3,
        AtlasEdgeCategory.STRUCTURAL to 4,
        AtlasEdgeCategory.MIXED to 5,
    )
