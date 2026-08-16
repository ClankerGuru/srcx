package zone.clanker.docx.web.atlas.hierarchy

import zone.clanker.report.model.ProjectGraphShard
import zone.clanker.report.model.WorkspaceGraphFacet
import zone.clanker.report.model.WorkspaceGraphFilter
import zone.clanker.report.model.WorkspaceGraphNode
import zone.clanker.report.model.WorkspaceGraphNodeRole
import zone.clanker.report.model.WorkspaceGraphRelation
import zone.clanker.report.model.WorkspaceGraphRelationEndpoints
import zone.clanker.report.model.WorkspaceGraphRelationFacts
import zone.clanker.report.model.WorkspaceGraphRelationKind
import zone.clanker.report.model.WorkspaceGraphRequest
import zone.clanker.report.model.WorkspaceGraphSlice
import zone.clanker.report.model.WorkspaceGraphSliceContent
import zone.clanker.report.model.WorkspaceGraphSliceCounts
import zone.clanker.report.model.WorkspaceGraphSliceTarget
import zone.clanker.report.model.WorkspaceGraphSliceViewport
import zone.clanker.report.model.WorkspaceSummaryShard

/** Projects one deterministic, bounded source hierarchy without depending on a renderer or platform API. */
internal fun workspaceSourceHierarchySlice(
    summary: WorkspaceSummaryShard,
    generationId: String,
    request: WorkspaceGraphRequest,
    loadedProjects: List<ProjectGraphShard> = emptyList(),
): WorkspaceGraphSlice = WorkspaceSourceHierarchyProjector(summary, generationId, request, loadedProjects).project()

private class WorkspaceSourceHierarchyProjector(
    private val summary: WorkspaceSummaryShard,
    private val generationId: String,
    private val request: WorkspaceGraphRequest,
    loadedProjects: List<ProjectGraphShard>,
) {
    private val loadedFacts = WorkspaceSourceHierarchyLoadedFacts(summary, loadedProjects)
    private val catalog = WorkspaceSourceHierarchyCatalog(summary, loadedFacts)
    private val evidence = WorkspaceSourceHierarchyEvidence(summary, loadedFacts, catalog)

    fun project(): WorkspaceGraphSlice {
        validateRequest()
        val facts = evidence.facts()
        val hierarchy = WorkspaceSourceHierarchyViewport(catalog, request, facts)
        val relationProjection =
            WorkspaceSourceHierarchyRelationProjector(
                facts = facts,
                filter = request.view.filter,
                hierarchy = hierarchy,
                relationLimit = request.limits.relationLimit,
                evidenceLimit = request.limits.evidencePerRelationLimit,
            ).project()
        return WorkspaceGraphSlice(
            target = WorkspaceGraphSliceTarget(summary.workspace.id, generationId, WorkspaceGraphFacet.SOURCE),
            viewport = WorkspaceGraphSliceViewport(scopeRootIds = request.view.selection.scopeRootIds),
            content =
                WorkspaceGraphSliceContent(
                    availability = evidence.availability(),
                    nodes = hierarchy.nodes(),
                    relations = relationProjection.relations,
                ),
            counts =
                WorkspaceGraphSliceCounts(
                    matchingPrimaryNodeCount = hierarchy.matchingPrimaryNodeCount,
                    matchingRelationCount = relationProjection.matchingRelationCount,
                ),
            limits = request.limits,
        )
    }

    private fun validateRequest() {
        require(request.workspaceId == summary.workspace.id) {
            "Workspace source-hierarchy request does not target the supplied summary"
        }
        require(request.generationId == null || request.generationId == generationId) {
            "Workspace source-hierarchy request targets a different generation"
        }
        require(request.facet == WorkspaceGraphFacet.SOURCE) {
            "Workspace source-hierarchy projector only supports the SOURCE facet"
        }
        require(request.view.continuationToken == null) {
            "Workspace source-hierarchy continuation is not available in the first bounded projection"
        }
    }
}

private class WorkspaceSourceHierarchyViewport(
    private val catalog: WorkspaceSourceHierarchyCatalog,
    private val request: WorkspaceGraphRequest,
    facts: List<WorkspaceSourceHierarchyFact>,
) {
    private val rolesById = mutableMapOf<String, MutableSet<WorkspaceGraphNodeRole>>()
    private val countMatchedPrimaryIds = mutableSetOf<String>()
    private val relationshipFilter = WorkspaceSourceHierarchyRelationshipFilter(request.view.filter, facts)
    val matchingPrimaryNodeCount: Long

    init {
        val selection = request.view.selection
        val scopeRootIds = selection.scopeRootIds.ifEmpty { listOf(catalog.workspaceId) }
        val requestedIds = scopeRootIds + selection.expandedNodeIds + selection.focusNodeIds
        requestedIds.forEach(catalog::requireRecord)

        scopeRootIds.forEach { id -> includeWithAncestors(id, WorkspaceGraphNodeRole.PRIMARY) }
        selection.expandedNodeIds.forEach { id -> includeWithAncestors(id, WorkspaceGraphNodeRole.PRIMARY) }
        selection.focusNodeIds.forEach { id -> includeWithAncestors(id, WorkspaceGraphNodeRole.FOCUS) }
        require(rolesById.size <= request.limits.nodeLimit) {
            "Workspace source-hierarchy scope, expansion, focus, and ancestors exceed the requested node limit"
        }

        val matchingChildren =
            (scopeRootIds + selection.expandedNodeIds)
                .distinct()
                .flatMap(catalog::children)
                .distinctBy(WorkspaceSourceHierarchyRecord::id)
                .filter { record ->
                    request.view.filter.matchesNode(record) && relationshipFilter.matchesCount(record.id)
                }.sortedBy(WorkspaceSourceHierarchyRecord::id)
        countMatchedPrimaryIds += matchingChildren.map(WorkspaceSourceHierarchyRecord::id)
        val requestedPrimaryIds = (scopeRootIds + selection.expandedNodeIds).toSet()
        matchingPrimaryNodeCount = (requestedPrimaryIds + matchingChildren.map { record -> record.id }).size.toLong()
        matchingChildren.forEach { child ->
            if (child.id in rolesById || rolesById.size < request.limits.nodeLimit) {
                addRole(child.id, WorkspaceGraphNodeRole.PRIMARY)
            }
        }
    }

    fun relationAnchorIds(): Set<String> {
        val focusIds =
            rolesById
                .filterValues { roles -> WorkspaceGraphNodeRole.FOCUS in roles }
                .keys
        if (relationshipFilter.enabled) {
            return countMatchedPrimaryIds.filterTo(mutableSetOf(), rolesById::containsKey) + focusIds
        }
        return rolesById
            .filterValues { roles -> WorkspaceGraphNodeRole.PRIMARY in roles || WorkspaceGraphNodeRole.FOCUS in roles }
            .keys
    }

    fun allowsRelationshipContext(
        fact: WorkspaceSourceHierarchyFact,
        anchorIds: Set<String>,
    ): Boolean = relationshipFilter.allowsContext(fact, anchorIds)

    fun includeEndpointClosure(
        sourceId: String,
        targetId: String,
    ): Boolean {
        val endpointPaths = listOf(catalog.path(sourceId), catalog.path(targetId))
        val closureIds = endpointPaths.flatten().mapTo(mutableSetOf(), WorkspaceSourceHierarchyRecord::id)
        val missingCount = closureIds.count { id -> id !in rolesById }
        if (rolesById.size + missingCount > request.limits.nodeLimit) return false
        endpointPaths.forEach { path ->
            path.dropLast(1).forEach { record -> addRole(record.id, WorkspaceGraphNodeRole.ANCESTOR) }
            addRole(path.last().id, WorkspaceGraphNodeRole.ENDPOINT)
        }
        return true
    }

    fun nodes(): List<WorkspaceGraphNode> =
        rolesById
            .map { (id, roles) -> catalog.node(id, roles) }
            .sortedBy(WorkspaceGraphNode::id)

    private fun includeWithAncestors(
        id: String,
        role: WorkspaceGraphNodeRole,
    ) {
        val path = catalog.path(id)
        path.dropLast(1).forEach { record -> addRole(record.id, WorkspaceGraphNodeRole.ANCESTOR) }
        addRole(path.last().id, role)
    }

    private fun addRole(
        id: String,
        role: WorkspaceGraphNodeRole,
    ) {
        rolesById.getOrPut(id) { mutableSetOf() }.add(role)
    }
}

private class WorkspaceSourceHierarchyRelationProjector(
    private val facts: List<WorkspaceSourceHierarchyFact>,
    private val filter: WorkspaceGraphFilter,
    private val hierarchy: WorkspaceSourceHierarchyViewport,
    private val relationLimit: Int,
    private val evidenceLimit: Int,
) {
    fun project(): WorkspaceSourceHierarchyRelationProjection {
        val aggregates = aggregateFacts()
        val relations = mutableListOf<WorkspaceGraphRelation>()
        aggregates.forEach { aggregate ->
            if (
                relations.size < relationLimit &&
                hierarchy.includeEndpointClosure(aggregate.key.sourceId, aggregate.key.targetId)
            ) {
                relations += aggregate.relation()
            }
        }
        return WorkspaceSourceHierarchyRelationProjection(
            relations = relations.sortedBy(WorkspaceGraphRelation::id),
            matchingRelationCount = aggregates.size.toLong(),
        )
    }

    private fun aggregateFacts(): List<WorkspaceSourceHierarchyRelationAggregate> {
        val anchors = hierarchy.relationAnchorIds()
        val factKeysById = mutableMapOf<String, WorkspaceSourceHierarchyRelationKey>()
        val aggregates = mutableMapOf<WorkspaceSourceHierarchyRelationKey, WorkspaceSourceRelationAccumulator>()
        facts
            .asSequence()
            .filter { fact -> filter.relationKinds.isEmpty() || fact.kind in filter.relationKinds }
            .filter { fact -> hierarchy.allowsRelationshipContext(fact, anchors) }
            .mapNotNull { fact -> fact.rollUp(anchors) }
            .forEach { rolledFact ->
                val previousKey = factKeysById.put(rolledFact.factId, rolledFact.key)
                require(previousKey == null || previousKey == rolledFact.key) {
                    "Workspace source-hierarchy fact belongs to conflicting aggregate relations: ${rolledFact.factId}"
                }
                if (previousKey == null) {
                    aggregates
                        .getOrPut(rolledFact.key) { WorkspaceSourceRelationAccumulator(evidenceLimit) }
                        .add(rolledFact.factId)
                }
            }
        return aggregates
            .map { (key, accumulator) -> accumulator.aggregate(key) }
            .sortedBy { aggregate -> aggregate.relationId }
    }
}

private data class WorkspaceSourceHierarchyRelationProjection(
    val relations: List<WorkspaceGraphRelation>,
    val matchingRelationCount: Long,
)

private data class WorkspaceSourceHierarchyRelationKey(
    val kind: WorkspaceGraphRelationKind,
    val sourceId: String,
    val targetId: String,
) {
    val relationId: String =
        "source-rollup:${kind.name}:${sourceId.length}:$sourceId:${targetId.length}:$targetId"
}

private data class WorkspaceSourceHierarchyRolledFact(
    val factId: String,
    val key: WorkspaceSourceHierarchyRelationKey,
)

private data class WorkspaceSourceHierarchyRelationAggregate(
    val key: WorkspaceSourceHierarchyRelationKey,
    val factCount: Long,
    val sampleFactIds: List<String>,
) {
    val relationId: String
        get() = key.relationId

    fun relation(): WorkspaceGraphRelation =
        WorkspaceGraphRelation(
            id = relationId,
            endpoints = WorkspaceGraphRelationEndpoints(key.sourceId, key.targetId),
            kind = key.kind,
            facts = WorkspaceGraphRelationFacts(factCount = factCount, sampleFactIds = sampleFactIds),
        )
}

private class WorkspaceSourceRelationAccumulator(
    private val evidenceLimit: Int,
) {
    private val sampleFactIds = mutableListOf<String>()
    private var factCount: Long = 0

    fun add(factId: String) {
        factCount += 1
        if (sampleFactIds.size < evidenceLimit) sampleFactIds += factId
    }

    fun aggregate(key: WorkspaceSourceHierarchyRelationKey): WorkspaceSourceHierarchyRelationAggregate =
        WorkspaceSourceHierarchyRelationAggregate(
            key = key,
            factCount = factCount,
            sampleFactIds = sampleFactIds.sorted(),
        )
}

private fun WorkspaceSourceHierarchyFact.rollUp(
    anchorIds: Set<String>,
): WorkspaceSourceHierarchyRolledFact? {
    val sourceAnchorLevel = sourcePath.lastOrNull { record -> record.id in anchorIds }?.semanticLevel
    val targetAnchorLevel = targetPath.lastOrNull { record -> record.id in anchorIds }?.semanticLevel
    val projectionLevel = listOfNotNull(sourceAnchorLevel, targetAnchorLevel).maxOrNull() ?: return null
    val sourceId = sourcePath.last { record -> record.semanticLevel <= projectionLevel }.id
    val targetId = targetPath.last { record -> record.semanticLevel <= projectionLevel }.id
    if (sourceId == targetId) return null
    return WorkspaceSourceHierarchyRolledFact(
        factId = factId,
        key = WorkspaceSourceHierarchyRelationKey(kind, sourceId, targetId),
    )
}
