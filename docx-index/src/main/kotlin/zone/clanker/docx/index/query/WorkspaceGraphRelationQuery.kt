@file:Suppress("MagicNumber")

package zone.clanker.docx.index.query

import zone.clanker.docx.index.WorkspaceGraphCancellation
import zone.clanker.docx.index.database.query
import zone.clanker.report.model.WorkspaceGraphFilter
import zone.clanker.report.model.WorkspaceGraphNodeRole
import zone.clanker.report.model.WorkspaceGraphRelation
import zone.clanker.report.model.WorkspaceGraphRelationEndpoints
import zone.clanker.report.model.WorkspaceGraphRelationFacts
import zone.clanker.report.model.WorkspaceGraphRelationKind
import zone.clanker.report.model.WorkspaceGraphRelationshipDirection
import zone.clanker.report.model.WorkspaceGraphRequest
import java.sql.Connection

internal data class GraphRelationQuery(
    val generation: GraphGeneration,
    val hierarchy: GraphHierarchyProjection,
    val request: WorkspaceGraphRequest,
    val cancellation: WorkspaceGraphCancellation,
)

internal data class GraphRelationQueryResult(
    val relations: List<WorkspaceGraphRelation>,
    val matchingRelationCount: Long,
)

@Suppress("LongMethod", "NestedBlockDepth")
internal fun Connection.projectGraphRelations(query: GraphRelationQuery): GraphRelationQueryResult {
    val generation = query.generation
    val hierarchy = query.hierarchy
    val request = query.request
    val filter = request.view.filter
    val relationLimit = request.limits.relationLimit
    val evidenceLimit = request.limits.evidencePerRelationLimit
    val nodeLimit = request.limits.nodeLimit
    val cancellation = query.cancellation
    cancellation.checkActive()
    if (hierarchy.relationAnchorKeys.isEmpty()) return GraphRelationQueryResult(emptyList(), 0)
    val projection = aggregateGraphRelations(generation, hierarchy.relationAnchorKeys, filter, evidenceLimit)
    val endpointKeys =
        projection.aggregates.flatMapTo(mutableSetOf()) { aggregate ->
            listOf(aggregate.sourceKey, aggregate.targetKey)
        }
    val endpointPaths =
        loadGraphPathEntries(generation, endpointKeys)
            .groupBy(GraphPathEntry::endpointKey)
            .mapValues { (_, entries) -> entries.map(GraphPathEntry::node).sortedBy(GraphNodeRow::depth) }
    val relations = mutableListOf<WorkspaceGraphRelation>()
    projection.aggregates.forEach { aggregate ->
        cancellation.checkActive()
        if (relations.size == relationLimit) return@forEach
        val sourcePath = endpointPaths.getValue(aggregate.sourceKey)
        val targetPath = endpointPaths.getValue(aggregate.targetKey)
        val closure = (sourcePath + targetPath).associateBy(GraphNodeRow::key)
        val missing = closure.keys.count { key -> key !in hierarchy.rolesByKey }
        if (hierarchy.rolesByKey.size + missing <= nodeLimit) {
            listOf(sourcePath, targetPath).forEach { path ->
                path.dropLast(1).forEach { node ->
                    hierarchy.nodesByKey[node.key] = node
                    hierarchy.rolesByKey
                        .getOrPut(node.key) { mutableSetOf() }
                        .add(WorkspaceGraphNodeRole.ANCESTOR)
                }
                val endpoint = path.last()
                hierarchy.nodesByKey[endpoint.key] = endpoint
                hierarchy.rolesByKey
                    .getOrPut(endpoint.key) { mutableSetOf() }
                    .add(WorkspaceGraphNodeRole.ENDPOINT)
            }
            relations +=
                WorkspaceGraphRelation(
                    id = aggregate.id,
                    endpoints = WorkspaceGraphRelationEndpoints(aggregate.sourceId, aggregate.targetId),
                    kind = aggregate.kind,
                    facts =
                        WorkspaceGraphRelationFacts(
                            factCount = aggregate.factCount,
                            sampleFactIds = aggregate.sampleFactIds.sorted(),
                        ),
                )
        }
    }
    return GraphRelationQueryResult(
        relations = relations.sortedBy(WorkspaceGraphRelation::id),
        matchingRelationCount = projection.matchingRelationCount,
    )
}

@Suppress("LongMethod")
private fun Connection.aggregateGraphRelations(
    generation: GraphGeneration,
    anchorKeys: Set<Long>,
    filter: WorkspaceGraphFilter,
    evidenceLimit: Int,
): GraphRelationProjection {
    val anchors = anchorKeys.sorted()
    val anchorValues = anchors.joinToString(",") { "(?)" }
    val relationKindClause =
        if (filter.relationKinds.isEmpty()) {
            ""
        } else {
            "AND fact.kind IN (${filter.relationKinds.joinToString(",") { "?" }})"
        }
    val contextClause =
        filter.relationshipCountFilter
            ?.takeUnless { requested -> requested.keepInverseContext }
            ?.let { requested ->
                when (requested.direction) {
                    WorkspaceGraphRelationshipDirection.ANY -> ""
                    WorkspaceGraphRelationshipDirection.OUTGOING -> "AND source_level IS NOT NULL"
                    WorkspaceGraphRelationshipDirection.INCOMING -> "AND target_level IS NOT NULL"
                }
            }.orEmpty()
    val parameters =
        anchors +
            listOf(generation.registeredWorkspaceId, generation.generationId) +
            filter.relationKinds.map(WorkspaceGraphRelationKind::name) +
            listOf(evidenceLimit, MAX_AGGREGATE_ROWS)
    val rows =
        query(
            """
            WITH anchor(node_key) AS (VALUES $anchorValues),
            anchored AS (
                SELECT fact.fact_id, fact.kind, fact.source_node_key, fact.target_node_key,
                       (
                           SELECT MAX(node.semantic_level)
                           FROM anchor
                           JOIN graph_node_closure closure
                             ON closure.ancestor_node_key = anchor.node_key
                            AND closure.descendant_node_key = fact.source_node_key
                           JOIN graph_nodes node ON node.node_key = anchor.node_key
                       ) AS source_level,
                       (
                           SELECT MAX(node.semantic_level)
                           FROM anchor
                           JOIN graph_node_closure closure
                             ON closure.ancestor_node_key = anchor.node_key
                            AND closure.descendant_node_key = fact.target_node_key
                           JOIN graph_nodes node ON node.node_key = anchor.node_key
                       ) AS target_level
                FROM graph_relation_facts fact
                WHERE fact.workspace_id = ? AND fact.generation_id = ?
                  $relationKindClause
                  AND (
                      EXISTS (
                          SELECT 1 FROM anchor
                          JOIN graph_node_closure closure
                            ON closure.ancestor_node_key = anchor.node_key
                           AND closure.descendant_node_key = fact.source_node_key
                      ) OR EXISTS (
                          SELECT 1 FROM anchor
                          JOIN graph_node_closure closure
                            ON closure.ancestor_node_key = anchor.node_key
                           AND closure.descendant_node_key = fact.target_node_key
                      )
                  )
            ), contextual AS (
                SELECT *, MAX(COALESCE(source_level, -1), COALESCE(target_level, -1)) AS projection_level
                FROM anchored
                WHERE (source_level IS NOT NULL OR target_level IS NOT NULL) $contextClause
            ), projected AS (
                SELECT fact_id, kind,
                       (
                           SELECT node.node_key
                           FROM graph_node_closure closure
                           JOIN graph_nodes node ON node.node_key = closure.ancestor_node_key
                           WHERE closure.descendant_node_key = contextual.source_node_key
                             AND node.semantic_level <= contextual.projection_level
                           ORDER BY node.semantic_level DESC, node.depth DESC, node.semantic_id DESC
                           LIMIT 1
                       ) AS source_key,
                       (
                           SELECT node.node_key
                           FROM graph_node_closure closure
                           JOIN graph_nodes node ON node.node_key = closure.ancestor_node_key
                           WHERE closure.descendant_node_key = contextual.target_node_key
                             AND node.semantic_level <= contextual.projection_level
                           ORDER BY node.semantic_level DESC, node.depth DESC, node.semantic_id DESC
                           LIMIT 1
                       ) AS target_key
                FROM contextual
            ), ranked AS (
                SELECT *, ROW_NUMBER() OVER (
                    PARTITION BY kind, source_key, target_key ORDER BY fact_id
                ) AS sample_rank
                FROM projected
                WHERE source_key <> target_key
            ), aggregates AS (
                SELECT kind, source_key, target_key, COUNT(*) AS fact_count,
                       group_concat(hex(fact_id), ',') FILTER (WHERE sample_rank <= ?) AS samples
                FROM ranked
                GROUP BY kind, source_key, target_key
            )
            SELECT 'source-rollup:' || aggregates.kind || ':' || length(source.semantic_id) || ':' ||
                       source.semantic_id || ':' || length(target.semantic_id) || ':' || target.semantic_id AS relation_id,
                   aggregates.kind, aggregates.source_key, source.semantic_id,
                   aggregates.target_key, target.semantic_id, aggregates.fact_count,
                   aggregates.samples, COUNT(*) OVER ()
            FROM aggregates
            JOIN graph_nodes source ON source.node_key = aggregates.source_key
            JOIN graph_nodes target ON target.node_key = aggregates.target_key
            ORDER BY relation_id
            LIMIT ?
            """.trimIndent(),
            parameters,
        ) { result ->
            val kind = enumValueOf<WorkspaceGraphRelationKind>(result.getString(2))
            GraphRelationAggregate(
                id = result.getString(1),
                kind = kind,
                sourceKey = result.getLong(3),
                sourceId = result.getString(4),
                targetKey = result.getLong(5),
                targetId = result.getString(6),
                factCount = result.getLong(7),
                sampleFactIds = result.getString(8)?.hexStringList().orEmpty(),
                matchingRelationCount = result.getLong(9),
            )
        }
    return GraphRelationProjection(
        aggregates = rows.sortedBy(GraphRelationAggregate::id),
        matchingRelationCount = rows.firstOrNull()?.matchingRelationCount ?: 0,
    )
}

private data class GraphPathEntry(
    val endpointKey: Long,
    val node: GraphNodeRow,
)

private fun Connection.loadGraphPathEntries(
    generation: GraphGeneration,
    endpointKeys: Collection<Long>,
): List<GraphPathEntry> {
    if (endpointKeys.isEmpty()) return emptyList()
    return endpointKeys.chunked(SQLITE_KEY_CHUNK).flatMap { chunk ->
        val placeholders = chunk.joinToString(",") { "?" }
        query(
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
            ORDER BY closure.descendant_node_key, node.depth
            """.trimIndent(),
            chunk + listOf(generation.registeredWorkspaceId, generation.generationId),
        ) { result ->
            GraphPathEntry(
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
}

private fun String.hexStringList(): List<String> =
    split(',').filter(String::isNotEmpty).map { encoded ->
        encoded
            .chunked(2)
            .map { byte -> byte.toInt(16).toByte() }
            .toByteArray()
            .decodeToString()
    }

private const val SQLITE_KEY_CHUNK: Int = 500
private const val MAX_AGGREGATE_ROWS: Int = 5_000
