package zone.clanker.docx.index

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.sql.Connection
import java.sql.DriverManager

class WorkspaceIndexQueryPlanTest :
    BehaviorSpec({
        given("an indexed generated site") {
            val site = WorkspaceIndexFixture.createSite()
            val database = WorkspaceIndexFixture.createDatabase()
            WorkspaceIndexFixture.writeGeneration(site, generationId = QUERY_PLAN_GENERATION_ID)
            WorkspaceReportIndex.open(database).use { index ->
                index.indexSite(WorkspaceIndexFixture.INDEX_WORKSPACE_ID, site)
            }

            then("SQLite uses FTS and the explicit scope, relationship, and finding indexes") {
                DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
                    connection.seedRelationshipPlannerFixture()
                    connection.seedFindingPlannerFixture()
                    val symbolPlan =
                        connection.explain(
                            SYMBOL_SCOPE_PLAN_SQL,
                            listOf(
                                WorkspaceIndexFixture.INDEX_WORKSPACE_ID,
                                WorkspaceIndexFixture.BUILD_ID,
                                WorkspaceIndexFixture.ALPHA_PROJECT_ID,
                                WorkspaceIndexFixture.ALPHA_SOURCE_SET_ID,
                                "CLASS",
                            ),
                        )
                    val relationshipPlan =
                        connection.explain(
                            RELATIONSHIP_SCOPE_PLAN_SQL,
                            listOf(
                                WorkspaceIndexFixture.INDEX_WORKSPACE_ID,
                                WorkspaceIndexFixture.BUILD_ID,
                                WorkspaceIndexFixture.ALPHA_PROJECT_ID,
                                WorkspaceIndexFixture.ALPHA_SOURCE_SET_ID,
                                "CALL",
                            ),
                        )
                    val findingPlan =
                        connection.explain(
                            FINDING_SCOPE_PLAN_SQL,
                            listOf(
                                WorkspaceIndexFixture.INDEX_WORKSPACE_ID,
                                WorkspaceIndexFixture.BUILD_ID,
                                WorkspaceIndexFixture.ALPHA_PROJECT_ID,
                                WorkspaceIndexFixture.ALPHA_SOURCE_SET_ID,
                                "WARNING",
                            ),
                        )
                    val ftsPlan =
                        connection.explain(
                            FTS_PLAN_SQL,
                            listOf(WorkspaceIndexFixture.INDEX_WORKSPACE_ID, "\"Alpha\"*"),
                        )

                    symbolPlan.uses("idx_symbols_scope_kind") shouldBe true
                    relationshipPlan.searches("idx_relationships_scope_kind") shouldBe true
                    findingPlan.searches("idx_findings_scope_severity") shouldBe true
                    ftsPlan.any { detail -> "VIRTUAL TABLE INDEX" in detail } shouldBe true
                }
            }
        }
    })

private fun Connection.explain(
    sql: String,
    values: List<Any>,
): List<String> =
    prepareStatement("EXPLAIN QUERY PLAN $sql").use { statement ->
        values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
        statement.executeQuery().use { result ->
            buildList {
                while (result.next()) add(result.getString("detail"))
            }
        }
    }

private fun List<String>.uses(indexName: String): Boolean = any { detail -> indexName in detail }

private fun List<String>.searches(indexName: String): Boolean =
    any { detail -> "SEARCH" in detail && indexName in detail }

private fun Connection.seedRelationshipPlannerFixture() {
    prepareStatement(INSERT_RELATIONSHIP_PLAN_ROW_SQL).use { statement ->
        repeat(QUERY_PLAN_FILLER_ROW_COUNT) { index ->
            statement.setString(1, WorkspaceIndexFixture.INDEX_WORKSPACE_ID)
            statement.setString(2, QUERY_PLAN_GENERATION_ID)
            statement.setString(3, "relationship:noise:$index")
            statement.setString(4, "build:noise:$index")
            statement.setString(5, "project:noise:$index")
            statement.setString(6, "source-set:noise:$index")
            statement.setString(7, "file:noise:$index")
            statement.setString(8, "reference:noise:$index")
            statement.setString(9, "symbol:noise-source:$index")
            statement.setString(10, "symbol:noise-target:$index")
            statement.setString(11, "CALL")
            statement.setString(12, "DIRECT")
            statement.addBatch()
        }
        statement.executeBatch().size shouldBe QUERY_PLAN_FILLER_ROW_COUNT
    }
    createStatement().use { statement -> statement.execute("ANALYZE relationships") }
}

private fun Connection.seedFindingPlannerFixture() {
    prepareStatement(INSERT_FINDING_PLAN_ROW_SQL).use { statement ->
        repeat(QUERY_PLAN_FILLER_ROW_COUNT) { index ->
            statement.setString(1, WorkspaceIndexFixture.INDEX_WORKSPACE_ID)
            statement.setString(2, QUERY_PLAN_GENERATION_ID)
            statement.setString(3, "finding:noise:$index")
            statement.setString(4, "build:noise:$index")
            statement.setString(5, "project:noise:$index")
            statement.setString(6, "source-set:noise:$index")
            statement.setString(7, "file:noise:$index")
            statement.setString(8, "src/noise/Type$index.kt")
            statement.setInt(9, 1)
            statement.setString(10, "WARNING")
            statement.setString(11, "Noise finding $index")
            statement.setString(12, "Noise suggestion $index")
            statement.addBatch()
        }
        statement.executeBatch().size shouldBe QUERY_PLAN_FILLER_ROW_COUNT
    }
    createStatement().use { statement -> statement.execute("ANALYZE findings") }
}

private const val SYMBOL_SCOPE_PLAN_SQL =
    """
    SELECT s.symbol_id
    FROM active_workspace_generations active
    JOIN symbols s
      ON s.workspace_id = active.workspace_id AND s.generation_id = active.generation_id
    WHERE active.workspace_id = ? AND s.build_id = ? AND s.project_id = ?
      AND s.source_set_id = ? AND s.kind = ?
    """

private const val RELATIONSHIP_SCOPE_PLAN_SQL =
    """
    SELECT r.relationship_id
    FROM active_workspace_generations active
    JOIN relationships r
      ON r.workspace_id = active.workspace_id AND r.generation_id = active.generation_id
    WHERE active.workspace_id = ? AND r.build_id = ? AND r.project_id = ?
      AND r.source_set_id = ? AND r.kind = ?
    """

private const val FINDING_SCOPE_PLAN_SQL =
    """
    SELECT f.finding_id
    FROM active_workspace_generations active
    JOIN findings f
      ON f.workspace_id = active.workspace_id AND f.generation_id = active.generation_id
    WHERE active.workspace_id = ? AND f.build_id = ? AND f.project_id = ?
      AND f.source_set_id = ? AND f.severity = ?
    """

private const val FTS_PLAN_SQL =
    """
    SELECT s.symbol_id
    FROM active_workspace_generations active
    JOIN symbols s
      ON s.workspace_id = active.workspace_id AND s.generation_id = active.generation_id
    JOIN symbol_fts
      ON symbol_fts.workspace_id = s.workspace_id
     AND symbol_fts.generation_id = s.generation_id
     AND symbol_fts.symbol_id = s.symbol_id
    WHERE active.workspace_id = ? AND symbol_fts MATCH ?
    """

private const val INSERT_RELATIONSHIP_PLAN_ROW_SQL =
    """
    INSERT INTO relationships (
        workspace_id, generation_id, relationship_id, build_id, project_id, source_set_id,
        source_file_id, reference_id, source_symbol_id, target_symbol_id, kind, evidence
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    """

private const val INSERT_FINDING_PLAN_ROW_SQL =
    """
    INSERT INTO findings (
        workspace_id, generation_id, finding_id, build_id, project_id, source_set_id,
        file_id, file_path, line, severity, message, suggestion
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    """

private const val QUERY_PLAN_GENERATION_ID = "generation-plan"
private const val QUERY_PLAN_FILLER_ROW_COUNT = 4_096
