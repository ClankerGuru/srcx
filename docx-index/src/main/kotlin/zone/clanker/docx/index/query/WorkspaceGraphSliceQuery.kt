package zone.clanker.docx.index.query

import zone.clanker.docx.index.WorkspaceGraphCancellation
import zone.clanker.docx.index.database.SqliteDatabase
import zone.clanker.docx.index.database.queryOne
import zone.clanker.report.model.WorkspaceGraphAvailabilityReason
import zone.clanker.report.model.WorkspaceGraphAvailabilityState
import zone.clanker.report.model.WorkspaceGraphFacet
import zone.clanker.report.model.WorkspaceGraphFactAvailability
import zone.clanker.report.model.WorkspaceGraphFactKind
import zone.clanker.report.model.WorkspaceGraphLimits
import zone.clanker.report.model.WorkspaceGraphNode
import zone.clanker.report.model.WorkspaceGraphRequest
import zone.clanker.report.model.WorkspaceGraphSlice
import zone.clanker.report.model.WorkspaceGraphSliceContent
import zone.clanker.report.model.WorkspaceGraphSliceCounts
import zone.clanker.report.model.WorkspaceGraphSliceTarget
import zone.clanker.report.model.WorkspaceGraphSliceViewport
import java.sql.Connection

internal fun SqliteDatabase.queryGraphSlice(
    workspaceId: String,
    request: WorkspaceGraphRequest,
    cancellation: WorkspaceGraphCancellation,
): WorkspaceGraphSlice =
    snapshot { connection ->
        cancellation.checkActive()
        val generation =
            requireNotNull(connection.resolveGraphGeneration(workspaceId)) {
                "Workspace has no current indexed generation: $workspaceId"
            }
        validateGraphRequest(generation, request)
        val hierarchy =
            connection.projectGraphHierarchy(
                GraphHierarchyQuery(
                    generation = generation,
                    request = request,
                    cancellation = cancellation,
                ),
            )
        val (relations, matchingRelationCount) =
            connection.projectGraphRelations(
                GraphRelationQuery(
                    generation = generation,
                    hierarchy = hierarchy,
                    request = request,
                    cancellation = cancellation,
                ),
            )
        cancellation.checkActive()
        WorkspaceGraphSlice(
            target =
                WorkspaceGraphSliceTarget(
                    workspaceId = generation.sourceWorkspaceId,
                    generationId = generation.generationId,
                    facet = WorkspaceGraphFacet.SOURCE,
                ),
            viewport = WorkspaceGraphSliceViewport(scopeRootIds = request.view.selection.scopeRootIds),
            content =
                WorkspaceGraphSliceContent(
                    availability = connection.graphAvailability(generation),
                    nodes =
                        hierarchy.rolesByKey
                            .map { (key, roles) -> hierarchy.nodesByKey.getValue(key).node(roles) }
                            .sortedBy(WorkspaceGraphNode::id),
                    relations = relations,
                ),
            counts =
                WorkspaceGraphSliceCounts(
                    matchingPrimaryNodeCount = hierarchy.matchingPrimaryNodeCount,
                    matchingRelationCount = matchingRelationCount,
                ),
            limits = request.limits,
        )
    }

private fun validateGraphRequest(
    generation: GraphGeneration,
    request: WorkspaceGraphRequest,
) {
    require(request.workspaceId == generation.sourceWorkspaceId) {
        "Workspace graph request does not target the indexed source workspace"
    }
    require(request.generationId == null || request.generationId == generation.generationId) {
        "Workspace graph request targets a different generation"
    }
    require(request.facet == WorkspaceGraphFacet.SOURCE) {
        "Live workspace graph currently supports only the SOURCE facet"
    }
    require(request.view.continuationToken == null) {
        "Live workspace graph continuation is not available"
    }
    require(request.limits.nodeLimit <= WorkspaceGraphLimits.MAX_NODE_LIMIT) {
        "Workspace graph node limit exceeds the live hard bound"
    }
    require(request.limits.relationLimit <= WorkspaceGraphLimits.MAX_RELATION_LIMIT) {
        "Workspace graph relation limit exceeds the live hard bound"
    }
}

private fun Connection.graphAvailability(generation: GraphGeneration): List<WorkspaceGraphFactAvailability> {
    val counts =
        mapOf(
            WorkspaceGraphFactKind.STRUCTURE to countGraphRows(generation, "graph_nodes"),
            WorkspaceGraphFactKind.PACKAGES to
                countGraphNodes(generation, "kind = 'PACKAGE' AND semantic_id NOT LIKE 'source-unknown-package:%'"),
            WorkspaceGraphFactKind.SYMBOL_DECLARATIONS to
                countGraphNodes(generation, "kind IN ('TYPE','MEMBER')"),
            WorkspaceGraphFactKind.CODE_RELATIONSHIPS to
                countGraphFacts(generation, "kind <> 'BUILD_DEPENDS_ON'"),
            WorkspaceGraphFactKind.PROBLEMS to countGraphNodes(generation, "kind = 'PROBLEM'"),
            WorkspaceGraphFactKind.CYCLES to countGraphNodes(generation, "kind = 'CYCLE'"),
            WorkspaceGraphFactKind.BUILD_DEPENDENCIES to
                countGraphFacts(generation, "kind = 'BUILD_DEPENDS_ON'"),
        )
    return WorkspaceGraphFactKind.entries.sortedBy(Enum<*>::name).map { fact ->
        when (fact) {
            WorkspaceGraphFactKind.STRUCTURE,
            WorkspaceGraphFactKind.BUILD_DEPENDENCIES,
            -> complete(fact, counts.getValue(fact))

            WorkspaceGraphFactKind.PACKAGES,
            WorkspaceGraphFactKind.SYMBOL_DECLARATIONS,
            WorkspaceGraphFactKind.CODE_RELATIONSHIPS,
            WorkspaceGraphFactKind.PROBLEMS,
            WorkspaceGraphFactKind.CYCLES,
            ->
                if (generation.projectCount == 0) {
                    unavailable(fact, WorkspaceGraphAvailabilityReason.NOT_INDEXED)
                } else {
                    WorkspaceGraphFactAvailability(
                        fact = fact,
                        state = WorkspaceGraphAvailabilityState.PARTIAL,
                        observedFactCount = counts.getValue(fact),
                        reason = WorkspaceGraphAvailabilityReason.LEGACY_SNAPSHOT,
                    )
                }

            WorkspaceGraphFactKind.MEMBER_OWNERSHIP,
            WorkspaceGraphFactKind.PROJECT_DEPENDENCIES,
            WorkspaceGraphFactKind.SOURCE_SET_DEPENDENCIES,
            WorkspaceGraphFactKind.VARIANTS,
            WorkspaceGraphFactKind.VARIANT_SOURCE_SETS,
            WorkspaceGraphFactKind.GRADLE_TASKS,
            WorkspaceGraphFactKind.TASK_RELATIONSHIPS,
            WorkspaceGraphFactKind.DECLARED_DEPENDENCIES,
            WorkspaceGraphFactKind.RESOLVED_DEPENDENCIES,
            WorkspaceGraphFactKind.DEPENDENCY_UPGRADES,
            -> unavailable(fact, WorkspaceGraphAvailabilityReason.NOT_CAPTURED)
        }
    }
}

private fun Connection.countGraphRows(
    generation: GraphGeneration,
    table: String,
): Long =
    queryOne(
        "SELECT COUNT(*) FROM $table WHERE workspace_id = ? AND generation_id = ?",
        listOf(generation.registeredWorkspaceId, generation.generationId),
    ) { result -> result.getLong(1) } ?: 0

private fun Connection.countGraphNodes(
    generation: GraphGeneration,
    predicate: String,
): Long =
    queryOne(
        "SELECT COUNT(*) FROM graph_nodes WHERE workspace_id = ? AND generation_id = ? AND $predicate",
        listOf(generation.registeredWorkspaceId, generation.generationId),
    ) { result -> result.getLong(1) } ?: 0

private fun Connection.countGraphFacts(
    generation: GraphGeneration,
    predicate: String,
): Long =
    queryOne(
        "SELECT COUNT(*) FROM graph_relation_facts WHERE workspace_id = ? AND generation_id = ? AND $predicate",
        listOf(generation.registeredWorkspaceId, generation.generationId),
    ) { result -> result.getLong(1) } ?: 0

private fun complete(
    fact: WorkspaceGraphFactKind,
    count: Long,
): WorkspaceGraphFactAvailability =
    WorkspaceGraphFactAvailability(
        fact = fact,
        state = WorkspaceGraphAvailabilityState.COMPLETE,
        observedFactCount = count,
    )

private fun unavailable(
    fact: WorkspaceGraphFactKind,
    reason: WorkspaceGraphAvailabilityReason,
): WorkspaceGraphFactAvailability =
    WorkspaceGraphFactAvailability(
        fact = fact,
        state = WorkspaceGraphAvailabilityState.UNAVAILABLE,
        observedFactCount = 0,
        reason = reason,
    )
