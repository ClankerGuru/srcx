@file:Suppress("MagicNumber")

package zone.clanker.docx.index.query

import zone.clanker.docx.index.WorkspaceGraphCancellation
import zone.clanker.docx.index.database.query
import zone.clanker.docx.index.database.queryOne
import zone.clanker.report.model.WorkspaceGraphDeclarationKind
import zone.clanker.report.model.WorkspaceGraphFilter
import zone.clanker.report.model.WorkspaceGraphNodeKind
import zone.clanker.report.model.WorkspaceGraphNodeRole
import zone.clanker.report.model.WorkspaceGraphRelationshipDirection
import zone.clanker.report.model.WorkspaceGraphRequest
import zone.clanker.report.model.WorkspaceGraphSearchTarget
import java.sql.Connection
import java.sql.ResultSet

internal data class GraphHierarchyProjection(
    val rolesByKey: MutableMap<Long, MutableSet<WorkspaceGraphNodeRole>>,
    val nodesByKey: MutableMap<Long, GraphNodeRow>,
    val matchingPrimaryNodeCount: Long,
    val relationAnchorKeys: Set<Long>,
)

internal data class GraphHierarchyQuery(
    val generation: GraphGeneration,
    val request: WorkspaceGraphRequest,
    val cancellation: WorkspaceGraphCancellation,
)

internal fun Connection.resolveGraphGeneration(workspaceId: String): GraphGeneration? =
    queryOne(
        """
        SELECT generation.workspace_id, generation.source_workspace_id,
               generation.generation_id, generation.expected_project_count
        FROM active_workspace_generations active
        JOIN workspace_generations generation
          ON generation.workspace_id = active.workspace_id
         AND generation.generation_id = active.generation_id
        WHERE active.workspace_id = ? AND generation.state = 'READY'
        """.trimIndent(),
        listOf(workspaceId),
    ) { result ->
        GraphGeneration(
            registeredWorkspaceId = result.getString(1),
            sourceWorkspaceId = result.getString(2),
            generationId = result.getString(3),
            projectCount = result.getInt(4),
        )
    }

@Suppress("LongMethod")
internal fun Connection.projectGraphHierarchy(query: GraphHierarchyQuery): GraphHierarchyProjection {
    val generation = query.generation
    val request = query.request
    val cancellation = query.cancellation
    val selection = request.view.selection
    val filter = request.view.filter
    val nodeLimit = request.limits.nodeLimit
    cancellation.checkActive()
    val effectiveScopeIds = selection.scopeRootIds.ifEmpty { listOf(generation.sourceWorkspaceId) }
    val requestedIds =
        (effectiveScopeIds + selection.expandedNodeIds + selection.focusNodeIds)
            .distinct()
            .sorted()
    val requestedNodes = loadGraphNodesByIds(generation, requestedIds)
    require(requestedNodes.size == requestedIds.size) {
        val found = requestedNodes.mapTo(mutableSetOf(), GraphNodeRow::id)
        "Unknown workspace-graph node IDs: ${requestedIds.filterNot(found::contains)}"
    }
    val pathEntries = loadRequestedGraphPathEntries(generation, requestedNodes.map(GraphNodeRow::key))
    val pathsByEndpoint =
        pathEntries
            .groupBy(RequestedGraphPathEntry::endpointKey)
            .mapValues { (_, entries) -> entries.map(RequestedGraphPathEntry::node).sortedBy(GraphNodeRow::depth) }
    val nodesByKey = pathEntries.map(RequestedGraphPathEntry::node).associateByTo(mutableMapOf(), GraphNodeRow::key)
    val rolesByKey = mutableMapOf<Long, MutableSet<WorkspaceGraphNodeRole>>()
    val requestedById = requestedNodes.associateBy(GraphNodeRow::id)

    fun includePath(id: String, role: WorkspaceGraphNodeRole) {
        val endpoint = requestedById.getValue(id)
        pathsByEndpoint.getValue(endpoint.key).forEach { node ->
            rolesByKey
                .getOrPut(node.key) { mutableSetOf() }
                .add(if (node.key == endpoint.key) role else WorkspaceGraphNodeRole.ANCESTOR)
        }
    }
    effectiveScopeIds.forEach { id -> includePath(id, WorkspaceGraphNodeRole.PRIMARY) }
    selection.expandedNodeIds.forEach { id -> includePath(id, WorkspaceGraphNodeRole.PRIMARY) }
    selection.focusNodeIds.forEach { id -> includePath(id, WorkspaceGraphNodeRole.FOCUS) }
    require(rolesByKey.size <= nodeLimit) {
        "Workspace graph scope, expansion, focus, and ancestors exceed the requested node limit"
    }

    cancellation.checkActive()
    val parentKeys =
        (effectiveScopeIds + selection.expandedNodeIds)
            .distinct()
            .map { id -> requestedById.getValue(id).key }
    val primaryRequestedKeys = parentKeys.toSet()
    val matches = matchingChildren(generation, parentKeys, primaryRequestedKeys, filter, nodeLimit)
    val matchingPrimaryCount = primaryRequestedKeys.size.toLong() + matches.totalCount - matches.requestedOverlapCount
    val countMatchedKeys = mutableSetOf<Long>()
    matches.rows.forEach { child ->
        countMatchedKeys += child.key
        if (child.key in rolesByKey || rolesByKey.size < nodeLimit) {
            nodesByKey[child.key] = child
            rolesByKey.getOrPut(child.key) { mutableSetOf() }.add(WorkspaceGraphNodeRole.PRIMARY)
        }
    }
    val focusKeys = selection.focusNodeIds.mapTo(mutableSetOf()) { id -> requestedById.getValue(id).key }
    val relationAnchorKeys =
        if (filter.relationshipCountFilter == null) {
            rolesByKey
                .filterValues { roles ->
                    WorkspaceGraphNodeRole.PRIMARY in roles || WorkspaceGraphNodeRole.FOCUS in roles
                }.keys
        } else {
            countMatchedKeys.filterTo(mutableSetOf(), rolesByKey::containsKey) + focusKeys
        }
    return GraphHierarchyProjection(
        rolesByKey = rolesByKey,
        nodesByKey = nodesByKey,
        matchingPrimaryNodeCount = matchingPrimaryCount,
        relationAnchorKeys = relationAnchorKeys,
    )
}

private data class MatchingGraphNodes(
    val rows: List<GraphNodeRow>,
    val totalCount: Long,
    val requestedOverlapCount: Long,
)

private fun Connection.matchingChildren(
    generation: GraphGeneration,
    parentKeys: List<Long>,
    primaryRequestedKeys: Set<Long>,
    filter: WorkspaceGraphFilter,
    limit: Int,
): MatchingGraphNodes {
    if (parentKeys.isEmpty()) return MatchingGraphNodes(emptyList(), 0, 0)
    val predicate = filter.graphNodePredicate("node")
    val parentPlaceholders = parentKeys.joinToString(",") { "?" }
    val primaryPlaceholders = primaryRequestedKeys.joinToString(",") { "?" }
    val rows =
        query(
            """
            SELECT node.node_key, node.semantic_id, node.kind, node.parent_node_key,
                   parent.semantic_id, node.depth, node.semantic_level,
                   node.direct_child_count, node.descendant_count,
                   node.label, node.secondary_label, COUNT(*) OVER (),
                   SUM(CASE WHEN node.node_key IN ($primaryPlaceholders) THEN 1 ELSE 0 END) OVER ()
            FROM graph_nodes node
            LEFT JOIN graph_nodes parent ON parent.node_key = node.parent_node_key
            WHERE node.workspace_id = ? AND node.generation_id = ?
              AND node.parent_node_key IN ($parentPlaceholders)
              AND ${predicate.sql}
            ORDER BY node.semantic_id
            LIMIT ?
            """.trimIndent(),
            primaryRequestedKeys.sorted() +
                listOf(generation.registeredWorkspaceId, generation.generationId) +
                parentKeys + predicate.parameters + limit,
        ) { result -> Triple(result.graphNodeRow(), result.getLong(12), result.getLong(13)) }
    return MatchingGraphNodes(
        rows = rows.map(Triple<GraphNodeRow, Long, Long>::first),
        totalCount = rows.firstOrNull()?.second ?: 0,
        requestedOverlapCount = rows.firstOrNull()?.third ?: 0,
    )
}

internal fun Connection.loadGraphNodesByIds(
    generation: GraphGeneration,
    ids: List<String>,
): List<GraphNodeRow> {
    if (ids.isEmpty()) return emptyList()
    val placeholders = ids.joinToString(",") { "?" }
    return query(
        """
        SELECT node.node_key, node.semantic_id, node.kind, node.parent_node_key,
               parent.semantic_id, node.depth, node.semantic_level,
               node.direct_child_count, node.descendant_count,
               node.label, node.secondary_label
        FROM graph_nodes node
        LEFT JOIN graph_nodes parent ON parent.node_key = node.parent_node_key
        WHERE node.workspace_id = ? AND node.generation_id = ?
          AND node.semantic_id IN ($placeholders)
        ORDER BY node.semantic_id
        """.trimIndent(),
        listOf(generation.registeredWorkspaceId, generation.generationId) + ids,
        ResultSet::graphNodeRow,
    )
}

private data class RequestedGraphPathEntry(
    val endpointKey: Long,
    val node: GraphNodeRow,
)

private fun Connection.loadRequestedGraphPathEntries(
    generation: GraphGeneration,
    endpointKeys: Collection<Long>,
): List<RequestedGraphPathEntry> {
    if (endpointKeys.isEmpty()) return emptyList()
    val placeholders = endpointKeys.joinToString(",") { "?" }
    return query(
        """
        SELECT closure.descendant_node_key,
               node.node_key, node.semantic_id, node.kind, node.parent_node_key,
               parent.semantic_id, node.depth, node.semantic_level,
               node.direct_child_count, node.descendant_count,
               node.label, node.secondary_label
        FROM graph_node_closure closure
        JOIN graph_nodes node ON node.node_key = closure.ancestor_node_key
        LEFT JOIN graph_nodes parent ON parent.node_key = node.parent_node_key
        WHERE closure.descendant_node_key IN ($placeholders)
          AND node.workspace_id = ? AND node.generation_id = ?
        ORDER BY node.semantic_id
        """.trimIndent(),
        endpointKeys.toList() + listOf(generation.registeredWorkspaceId, generation.generationId),
    ) { result ->
        RequestedGraphPathEntry(
            endpointKey = result.getLong(1),
            node =
                GraphNodeRow(
                    key = result.getLong(2),
                    id = result.getString(3),
                    kind = enumValueOf(result.getString(4)),
                    parentKey = result.getLong(5).takeUnless { result.wasNull() },
                    parentId = result.getString(6),
                    depth = result.getInt(7),
                    semanticLevel = result.getInt(8),
                    directChildCount = result.getLong(9),
                    descendantCount = result.getLong(10),
                    label = result.getString(11),
                    secondaryLabel = result.getString(12),
                ),
        )
    }
}

internal fun ResultSet.graphNodeRow(): GraphNodeRow =
    GraphNodeRow(
        key = getLong(1),
        id = getString(2),
        kind = enumValueOf(getString(3)),
        parentKey = getLong(4).takeUnless { wasNull() },
        parentId = getString(5),
        depth = getInt(6),
        semanticLevel = getInt(7),
        directChildCount = getLong(8),
        descendantCount = getLong(9),
        label = getString(10),
        secondaryLabel = getString(11),
    )

private data class GraphSqlPredicate(
    val sql: String,
    val parameters: List<Any?>,
)

@Suppress("LongMethod")
private fun WorkspaceGraphFilter.graphNodePredicate(alias: String): GraphSqlPredicate {
    val clauses = mutableListOf<String>()
    val values = mutableListOf<Any?>()
    if (nodeKinds.isNotEmpty()) {
        clauses += "$alias.kind IN (${nodeKinds.joinToString(",") { "?" }})"
        values.addAll(nodeKinds.map(WorkspaceGraphNodeKind::name))
    }
    if (declarationKinds.isNotEmpty()) {
        clauses +=
            """
            ($alias.kind NOT IN ('TYPE','MEMBER','PROBLEM','CYCLE') OR EXISTS (
                SELECT 1 FROM graph_node_declarations declaration
                WHERE declaration.node_key = $alias.node_key
                  AND declaration.declaration_kind IN (${declarationKinds.joinToString(",") { "?" }})
            ))
            """.trimIndent()
        values.addAll(declarationKinds.map(WorkspaceGraphDeclarationKind::name))
    }
    val normalizedQuery = query.trim().lowercase()
    if (normalizedQuery.isNotEmpty()) {
        if (searchTargets.isEmpty()) {
            clauses +=
                """
                (instr(lower($alias.semantic_id), ?) > 0
                OR instr(lower($alias.label), ?) > 0
                OR instr(lower(COALESCE($alias.secondary_label, '')), ?) > 0
                OR EXISTS (
                    SELECT 1 FROM graph_node_search search
                    WHERE search.node_key = $alias.node_key
                      AND instr(search.normalized_value, ?) > 0
                ))
                """.trimIndent()
            repeat(4) { values += normalizedQuery }
        } else {
            val targetClauses =
                searchTargets.map { target ->
                    values += target.name
                    if (target == WorkspaceGraphSearchTarget.FILE_EXTENSION) {
                        values += normalizedQuery.removePrefix("*").removePrefix(".")
                        "(search.target = ? AND search.normalized_value = ? AND search.normalized_value <> '')"
                    } else {
                        values += normalizedQuery
                        "(search.target = ? AND instr(search.normalized_value, ?) > 0)"
                    }
                }
            clauses +=
                """
                EXISTS (
                    SELECT 1 FROM graph_node_search search
                    WHERE search.node_key = $alias.node_key
                      AND (${targetClauses.joinToString(" OR ")})
                )
                """.trimIndent()
        }
    }
    relationshipCountFilter?.let { requested ->
        val column =
            when (requested.direction) {
                WorkspaceGraphRelationshipDirection.ANY -> "any_count"
                WorkspaceGraphRelationshipDirection.OUTGOING -> "outgoing_count"
                WorkspaceGraphRelationshipDirection.INCOMING -> "incoming_count"
            }
        val kindClause =
            if (relationKinds.isEmpty()) {
                ""
            } else {
                values.addAll(relationKinds.map { kind -> kind.name })
                "AND degree.relation_kind IN (${relationKinds.joinToString(",") { "?" }})"
            }
        values += requested.moreThan
        clauses +=
            """
            COALESCE((
                SELECT SUM(degree.$column)
                FROM graph_relation_degrees degree
                WHERE degree.node_key = $alias.node_key $kindClause
            ), 0) > ?
            """.trimIndent()
    }
    return GraphSqlPredicate(clauses.joinToString(" AND ").ifEmpty { "1 = 1" }, values)
}
