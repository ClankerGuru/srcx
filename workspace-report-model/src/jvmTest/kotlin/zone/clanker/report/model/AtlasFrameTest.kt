package zone.clanker.report.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AtlasFrameTest {
    @Test
    fun roundTripsAndIgnoresFutureEnvelopeFields() {
        val frame = frame(selectedEdgeId = "relationship:b")
        val fields = Json.parseToJsonElement(AtlasFrameJson.encode(frame)).jsonObject.toMutableMap()
        fields["futureViewportHint"] = JsonPrimitive("ignored")

        assertEquals(frame, AtlasFrameJson.decode(JsonObject(fields).toString()))
        assertEquals("relationship:b", frame.selectedEdgeId)
    }

    @Test
    fun countsDrawnAndNodeInternalRelationshipRecordsExactlyOnce() {
        val frame = frame()

        assertEquals(2, frame.edges.sumOf(AtlasEdge::recordCount))
        assertEquals(1, frame.nodes.sumOf(AtlasNode::internalRecordCount))
        assertEquals(3, frame.shownRelationshipRecordCount)
        assertFailsWith<IllegalArgumentException> {
            frame.copy(shownRelationshipRecordCount = 2)
        }
    }

    @Test
    fun requiresDeterministicIdsAndAcceptsAFullProjectScope() {
        val frame = frame()
        assertFailsWith<IllegalArgumentException> {
            frame.copy(nodes = frame.nodes.reversed())
        }
        val template = frame.nodes.first().copy(relationshipRecordCount = 0, internalRecordCount = 0)
        val fullScope =
            (0 until FULL_SCOPE_NODE_COUNT).map { index ->
                val id = "file:overflow:${index.toString().padStart(2, '0')}"
                template.copy(id = id, name = "Overflow$index.kt", sourceFileId = id)
            }
        val expanded =
            frame.copy(
                frameId = "atlas:overflow",
                nodes = fullScope,
                edges = emptyList(),
                totalNodeCount = fullScope.size,
                matchingNodeCount = fullScope.size,
                totalRelationshipRecordCount = 0,
                shownRelationshipRecordCount = 0,
                selectedEdgeId = null,
            )

        assertEquals(FULL_SCOPE_NODE_COUNT, expanded.nodes.size)
        assertEquals(expanded, AtlasFrameJson.decode(AtlasFrameJson.encode(expanded)))
    }

    private fun frame(selectedEdgeId: String? = null): AtlasFrame {
        val first = fileNode("file:a", internalRecordCount = 1, relationshipRecordCount = 3)
        val second = fileNode("file:b", internalRecordCount = 0, relationshipRecordCount = 2)
        val edge =
            AtlasEdge(
                id = "relationship:a",
                sourceId = first.id,
                targetId = second.id,
                category = AtlasEdgeCategory.CALLS,
                relationshipIds = listOf("relationship:a", "relationship:b"),
                referenceIds = listOf("reference:a", "reference:b"),
                recordCount = 2,
                kindCounts = listOf(AtlasCount("CALL", "Call", 2)),
                evidenceCounts = listOf(AtlasCount("DIRECT", "Direct", 2)),
            )
        return AtlasFrame(
            frameId = "atlas:fixture:files:0",
            scope =
                AtlasScope(
                    kind = AtlasScopeKind.PROJECT,
                    workspaceId = "workspace:fixture",
                    workspaceName = "Fixture",
                    buildId = BUILD_ID,
                    projectId = PROJECT_ID,
                ),
            lens = AtlasLens.FILES,
            builds = listOf(AtlasBuild(BUILD_ID, "fixture", "Root build", "hsl(0 58% 66%)")),
            nodes = listOf(first, second),
            edges = listOf(edge),
            totalNodeCount = 2,
            matchingNodeCount = 2,
            pageIndex = 0,
            pageCount = 1,
            totalRelationshipRecordCount = 3,
            shownRelationshipRecordCount = 3,
            selectedEdgeId = selectedEdgeId,
        )
    }

    private fun fileNode(
        id: String,
        internalRecordCount: Int,
        relationshipRecordCount: Int,
    ): AtlasNode =
        AtlasNode(
            id = id,
            type = AtlasNodeType.FILE,
            name = id.substringAfter(':'),
            path = "src/main/kotlin/${id.substringAfter(':')}.kt",
            buildId = BUILD_ID,
            buildName = "fixture",
            projectId = PROJECT_ID,
            projectPath = ":app",
            sourceSet = "main",
            sourceFileId = id,
            language = "Kotlin",
            kind = "KOTLIN",
            relationshipRecordCount = relationshipRecordCount,
            internalRecordCount = internalRecordCount,
        )

    private companion object {
        const val BUILD_ID = "build:fixture"
        const val PROJECT_ID = "project:fixture"
        const val FULL_SCOPE_NODE_COUNT = 64
    }
}
