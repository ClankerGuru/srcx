package zone.clanker.docx.index

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.sql.Connection
import java.sql.DriverManager

class WorkspaceGraphQueryPlanTest :
    BehaviorSpec({
        given("a materialized generation-scoped graph") {
            val site = WorkspaceIndexFixture.createSite()
            val database = WorkspaceIndexFixture.createDatabase()
            WorkspaceIndexFixture.writeGeneration(site, GRAPH_PLAN_GENERATION_ID)
            WorkspaceReportIndex.open(database).use { index ->
                index.indexSite(WorkspaceIndexFixture.INDEX_WORKSPACE_ID, site)
            }

            then("hierarchy, closure, typed search, and relations use integer-keyed indexes") {
                DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
                    connection.createStatement().use { statement -> statement.execute("ANALYZE") }
                    connection.graphExplain(GRAPH_CHILD_PLAN_SQL) shouldUse "idx_graph_nodes_parent"
                    connection.graphExplain(GRAPH_CLOSURE_PLAN_SQL) shouldUse "idx_graph_closure_descendant"
                    connection.graphExplain(GRAPH_SEARCH_PLAN_SQL) shouldUse "idx_graph_search_target_value"
                    connection.graphExplain(GRAPH_RELATION_PLAN_SQL) shouldUse "idx_graph_relations_source"
                }
            }
        }
    })

private fun Connection.graphExplain(sql: String): List<String> =
    prepareStatement("EXPLAIN QUERY PLAN $sql").use { statement ->
        statement.setString(1, WorkspaceIndexFixture.INDEX_WORKSPACE_ID)
        statement.setString(2, GRAPH_PLAN_GENERATION_ID)
        statement.executeQuery().use { result ->
            buildList {
                while (result.next()) add(result.getString("detail"))
            }
        }
    }

private infix fun List<String>.shouldUse(indexName: String) {
    any { detail -> indexName in detail } shouldBe true
}

private const val GRAPH_CHILD_PLAN_SQL =
    """
    SELECT semantic_id FROM graph_nodes INDEXED BY idx_graph_nodes_parent
    WHERE workspace_id = ? AND generation_id = ? AND parent_node_key = 1
    ORDER BY semantic_id
    """

private const val GRAPH_CLOSURE_PLAN_SQL =
    """
    SELECT ancestor_node_key FROM graph_node_closure INDEXED BY idx_graph_closure_descendant
    WHERE descendant_node_key = ? AND distance >= ?
    """

private const val GRAPH_SEARCH_PLAN_SQL =
    """
    SELECT node_key FROM graph_node_search INDEXED BY idx_graph_search_target_value
    WHERE target = ? AND normalized_value = ?
    """

private const val GRAPH_RELATION_PLAN_SQL =
    """
    SELECT fact_key FROM graph_relation_facts INDEXED BY idx_graph_relations_source
    WHERE workspace_id = ? AND generation_id = ? AND kind = 'CALL' AND source_node_key = 1
    """

private const val GRAPH_PLAN_GENERATION_ID: String = "generation-graph-plan"
