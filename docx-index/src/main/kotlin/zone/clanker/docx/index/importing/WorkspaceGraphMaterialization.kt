package zone.clanker.docx.index.importing

import zone.clanker.docx.index.database.SqliteDatabase
import zone.clanker.docx.index.database.bind
import zone.clanker.docx.index.database.executeBatches
import zone.clanker.docx.index.database.query
import zone.clanker.docx.index.database.update
import zone.clanker.docx.index.generation.IndexGenerationKey
import zone.clanker.report.model.WorkspaceGraphNodeKind
import zone.clanker.report.model.WorkspaceSummaryShard
import java.sql.Connection

internal fun materializeWorkspaceGraph(
    database: SqliteDatabase,
    key: IndexGenerationKey,
    summary: WorkspaceSummaryShard,
) {
    database.transaction { connection ->
        val rows = WorkspaceGraphSourceRows(connection, key)
        val descriptors = indexedGraphDescriptors(summary, rows)
        validateDescriptors(summary.workspace.id, descriptors)
        val facts = indexedGraphFacts(summary, rows, descriptors)
        connection.replaceGraph(key, descriptors, facts)
    }
}

private fun Connection.replaceGraph(
    key: IndexGenerationKey,
    descriptors: List<IndexedGraphDescriptor>,
    facts: List<IndexedGraphFact>,
) {
    update(
        "DELETE FROM graph_nodes WHERE workspace_id = ? AND generation_id = ?",
        key.values,
    )
    val directChildCounts = descriptors.groupingBy(IndexedGraphDescriptor::parentId).eachCount()
    val descendantCounts = descendantCounts(descriptors)
    insertGraphNodes(key, descriptors, directChildCounts, descendantCounts)
    val nodeKeys = graphNodeKeys(key)
    updateGraphParents(descriptors, nodeKeys)
    insertGraphClosure(descriptors, nodeKeys)
    insertGraphSearch(descriptors, nodeKeys)
    insertGraphDeclarations(descriptors, nodeKeys)
    insertGraphFacts(key, facts, nodeKeys)
    materializeGraphDegrees(key)
}

private fun Connection.insertGraphNodes(
    key: IndexGenerationKey,
    descriptors: List<IndexedGraphDescriptor>,
    directChildCounts: Map<String?, Int>,
    descendantCounts: Map<String, Long>,
) {
    val orderedDescriptors =
        descriptors.sortedWith(
            compareBy(IndexedGraphDescriptor::depth, IndexedGraphDescriptor::id),
        )
    prepareStatement(INSERT_GRAPH_NODE_SQL).use { statement ->
        statement.executeBatches(orderedDescriptors) { descriptor ->
            bind(
                key.values +
                    listOf(
                        descriptor.id,
                        descriptor.kind.name,
                        descriptor.depth,
                        descriptor.semanticLevel,
                        directChildCounts[descriptor.id] ?: 0,
                        descendantCounts[descriptor.id] ?: 0,
                        descriptor.label,
                        descriptor.secondaryLabel,
                    ),
            )
        }
    }
}

private fun Connection.graphNodeKeys(key: IndexGenerationKey): Map<String, Long> =
    query(
        """
        SELECT semantic_id, node_key
        FROM graph_nodes
        WHERE workspace_id = ? AND generation_id = ?
        """.trimIndent(),
        key.values,
    ) { result -> result.getString(1) to result.getLong(2) }.toMap()

private fun Connection.updateGraphParents(
    descriptors: List<IndexedGraphDescriptor>,
    nodeKeys: Map<String, Long>,
) {
    prepareStatement("UPDATE graph_nodes SET parent_node_key = ? WHERE node_key = ?").use { statement ->
        statement.executeBatches(descriptors.filter { descriptor -> descriptor.parentId != null }) { descriptor ->
            bind(listOf(nodeKeys.getValue(requireNotNull(descriptor.parentId)), nodeKeys.getValue(descriptor.id)))
        }
    }
}

private fun Connection.insertGraphClosure(
    descriptors: List<IndexedGraphDescriptor>,
    nodeKeys: Map<String, Long>,
) {
    val descriptorsById = descriptors.associateBy(IndexedGraphDescriptor::id)
    val entries =
        sequence {
            descriptors.sortedBy(IndexedGraphDescriptor::id).forEach { descriptor ->
                var ancestor: IndexedGraphDescriptor? = descriptor
                var distance = 0
                while (ancestor != null) {
                    yield(GraphClosureEntry(nodeKeys.getValue(ancestor.id), nodeKeys.getValue(descriptor.id), distance))
                    ancestor = ancestor.parentId?.let(descriptorsById::getValue)
                    distance += 1
                }
            }
        }
    prepareStatement(INSERT_GRAPH_CLOSURE_SQL).use { statement ->
        statement.executeBatches(entries.asIterable()) { entry ->
            bind(listOf(entry.ancestorNodeKey, entry.descendantNodeKey, entry.distance))
        }
    }
}

private fun Connection.insertGraphSearch(
    descriptors: List<IndexedGraphDescriptor>,
    nodeKeys: Map<String, Long>,
) {
    val entries =
        descriptors
            .asSequence()
            .flatMap { descriptor ->
                descriptor.searchFields.asSequence().flatMap { (target, values) ->
                    values.asSequence().map { value ->
                        GraphSearchEntry(nodeKeys.getValue(descriptor.id), target.name, value.lowercase())
                    }
                }
            }.distinct()
            .sortedWith(compareBy(GraphSearchEntry::nodeKey, GraphSearchEntry::target, GraphSearchEntry::value))
    prepareStatement(INSERT_GRAPH_SEARCH_SQL).use { statement ->
        statement.executeBatches(entries.asIterable()) { entry ->
            bind(listOf(entry.nodeKey, entry.target, entry.value))
        }
    }
}

private fun Connection.insertGraphDeclarations(
    descriptors: List<IndexedGraphDescriptor>,
    nodeKeys: Map<String, Long>,
) {
    val entries =
        descriptors
            .asSequence()
            .flatMap { descriptor ->
                descriptor.declarationKinds.asSequence().map { declaration ->
                    GraphDeclarationEntry(nodeKeys.getValue(descriptor.id), declaration.name)
                }
            }.sortedWith(compareBy(GraphDeclarationEntry::nodeKey, GraphDeclarationEntry::kind))
    prepareStatement(INSERT_GRAPH_DECLARATION_SQL).use { statement ->
        statement.executeBatches(entries.asIterable()) { entry -> bind(listOf(entry.nodeKey, entry.kind)) }
    }
}

private fun Connection.insertGraphFacts(
    key: IndexGenerationKey,
    facts: List<IndexedGraphFact>,
    nodeKeys: Map<String, Long>,
) {
    prepareStatement(INSERT_GRAPH_FACT_SQL).use { statement ->
        statement.executeBatches(facts) { fact ->
            bind(
                key.values +
                    listOf(
                        fact.id,
                        fact.kind.name,
                        nodeKeys.getValue(fact.sourceId),
                        nodeKeys.getValue(fact.targetId),
                    ),
            )
        }
    }
}

private fun Connection.materializeGraphDegrees(key: IndexGenerationKey) {
    update(MATERIALIZE_GRAPH_DEGREES_SQL, key.values + key.values)
}

private fun validateDescriptors(
    workspaceId: String,
    descriptors: List<IndexedGraphDescriptor>,
) {
    val descriptorsById = descriptors.associateBy(IndexedGraphDescriptor::id)
    require(descriptorsById.size == descriptors.size) { "Indexed graph semantic IDs must be globally unique" }
    descriptors.forEach { descriptor ->
        if (descriptor.parentId == null) {
            require(
                descriptor.id == workspaceId &&
                    descriptor.kind == WorkspaceGraphNodeKind.WORKSPACE &&
                    descriptor.depth == 0,
            ) {
                "Indexed graph must have exactly one workspace root"
            }
        } else {
            val parent =
                requireNotNull(descriptorsById[descriptor.parentId]) {
                    "Indexed graph node has an unknown parent: ${descriptor.id}"
                }
            require(descriptor.depth == parent.depth + 1) { "Indexed graph depth does not follow its parent" }
        }
    }
    require(descriptors.count { descriptor -> descriptor.parentId == null } == 1) {
        "Indexed graph must have exactly one root"
    }
}

private fun descendantCounts(descriptors: List<IndexedGraphDescriptor>): Map<String, Long> {
    val descriptorsById = descriptors.associateBy(IndexedGraphDescriptor::id)
    val counts = mutableMapOf<String, Long>()
    descriptors.forEach { descriptor ->
        var ancestorId = descriptor.parentId
        while (ancestorId != null) {
            counts[ancestorId] = (counts[ancestorId] ?: 0) + 1
            ancestorId = descriptorsById.getValue(ancestorId).parentId
        }
    }
    return counts
}

private data class GraphClosureEntry(
    val ancestorNodeKey: Long,
    val descendantNodeKey: Long,
    val distance: Int,
)

private data class GraphSearchEntry(
    val nodeKey: Long,
    val target: String,
    val value: String,
)

private data class GraphDeclarationEntry(
    val nodeKey: Long,
    val kind: String,
)

private val IndexGenerationKey.values: List<String>
    get() = listOf(workspaceId, generationId)

private const val INSERT_GRAPH_NODE_SQL =
    """
    INSERT INTO graph_nodes (
        workspace_id, generation_id, semantic_id, kind, parent_node_key, depth,
        semantic_level, direct_child_count, descendant_count, label, secondary_label
    ) VALUES (?, ?, ?, ?, NULL, ?, ?, ?, ?, ?, ?)
    """

private const val INSERT_GRAPH_CLOSURE_SQL =
    """
    INSERT INTO graph_node_closure (ancestor_node_key, descendant_node_key, distance)
    VALUES (?, ?, ?)
    """

private const val INSERT_GRAPH_SEARCH_SQL =
    """
    INSERT INTO graph_node_search (node_key, target, normalized_value)
    VALUES (?, ?, ?)
    """

private const val INSERT_GRAPH_DECLARATION_SQL =
    """
    INSERT INTO graph_node_declarations (node_key, declaration_kind)
    VALUES (?, ?)
    """

private const val INSERT_GRAPH_FACT_SQL =
    """
    INSERT INTO graph_relation_facts (
        workspace_id, generation_id, fact_id, kind, source_node_key, target_node_key
    ) VALUES (?, ?, ?, ?, ?, ?)
    """

private const val MATERIALIZE_GRAPH_DEGREES_SQL =
    """
    INSERT INTO graph_relation_degrees (
        node_key, relation_kind, outgoing_count, incoming_count, any_count
    )
    WITH contributions AS (
        SELECT f.fact_key, f.kind, closure.ancestor_node_key AS node_key, 1 AS outgoing, 0 AS incoming
        FROM graph_relation_facts f
        JOIN graph_node_closure closure ON closure.descendant_node_key = f.source_node_key
        WHERE f.workspace_id = ? AND f.generation_id = ?
        UNION ALL
        SELECT f.fact_key, f.kind, closure.ancestor_node_key AS node_key, 0 AS outgoing, 1 AS incoming
        FROM graph_relation_facts f
        JOIN graph_node_closure closure ON closure.descendant_node_key = f.target_node_key
        WHERE f.workspace_id = ? AND f.generation_id = ?
    ), per_fact AS (
        SELECT fact_key, kind, node_key, MAX(outgoing) AS outgoing, MAX(incoming) AS incoming
        FROM contributions
        GROUP BY fact_key, kind, node_key
    )
    SELECT node_key, kind, SUM(outgoing), SUM(incoming), COUNT(*)
    FROM per_fact
    GROUP BY node_key, kind
    """
