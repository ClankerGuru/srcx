package zone.clanker.docx.web.atlas.projection

import zone.clanker.report.model.AtlasLens
import zone.clanker.report.model.AtlasNode
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class AtlasRelationshipCountProjectionTest {
    @Test
    fun disablesRelationshipCountFilteringWhenTheThresholdIsAbsent() {
        assertContentEquals(allRelationshipNodeIds, relationshipFrame(filter = null).primaryNodeIds())
    }

    @Test
    fun appliesStrictMoreThanSemanticsInAnyDirection() {
        assertContentEquals(
            listOf(AtlasTypedFilterFixture.ALPHA_SYMBOL_ID),
            relationshipFrame(AtlasRelationshipCountFilter(moreThan = 3)).primaryNodeIds(),
        )
        assertEquals(
            emptyList(),
            relationshipFrame(AtlasRelationshipCountFilter(moreThan = 4)).primaryNodeIds(),
        )
    }

    @Test
    fun retainsExactNonPrimaryClosureForOutgoingMatches() {
        val filter =
            AtlasRelationshipCountFilter(
                moreThan = 1,
                direction = AtlasRelationshipDirection.OUTGOING,
            )
        val frame = relationshipFrame(filter)
        val mergedFrame = relationshipBuildFrame(filter)

        assertContentEquals(listOf(AtlasTypedFilterFixture.ALPHA_SYMBOL_ID), frame.primaryNodeIds())
        assertContentEquals(
            listOf(
                AtlasTypedFilterFixture.BETA_SYMBOL_ID,
                AtlasTypedFilterFixture.GAMMA_SYMBOL_ID,
            ),
            frame.nonPrimaryNodeIds(),
        )
        assertEquals(
            frame.nodes.mapTo(mutableSetOf(), AtlasNode::id),
            frame.edges.flatMap { edge -> listOf(edge.sourceId, edge.targetId) }.toSet(),
        )
        assertEquals(4, frame.shownRelationshipRecordCount)
        assertContentEquals(frame.primaryNodeIds(), mergedFrame.primaryNodeIds())
        assertContentEquals(
            frame.edges.flatMap { edge -> edge.relationshipIds },
            mergedFrame.edges.flatMap { edge -> edge.relationshipIds },
        )
    }

    @Test
    fun keepsIncomingAndOutgoingCountsDistinct() {
        val frame =
            relationshipFrame(
                AtlasRelationshipCountFilter(
                    moreThan = 1,
                    direction = AtlasRelationshipDirection.INCOMING,
                ),
            )

        assertContentEquals(
            listOf(
                AtlasTypedFilterFixture.ALPHA_SYMBOL_ID,
                AtlasTypedFilterFixture.BETA_SYMBOL_ID,
            ),
            frame.primaryNodeIds(),
        )
        assertContentEquals(
            listOf(AtlasTypedFilterFixture.GAMMA_SYMBOL_ID),
            frame.nonPrimaryNodeIds(),
        )
    }
}

private fun relationshipFrame(filter: AtlasRelationshipCountFilter?) =
    atlasProjectFrame(
        summary = AtlasTypedFilterFixture.summary,
        project = AtlasTypedFilterFixture.project,
        request = AtlasProjectFrameRequest(AtlasLens.SYMBOLS, relationshipCountFilter = filter),
    )

private fun relationshipBuildFrame(filter: AtlasRelationshipCountFilter) =
    atlasBuildFrame(
        summary = AtlasTypedFilterFixture.summary,
        buildId =
            AtlasTypedFilterFixture.summary.builds
                .single()
                .id,
        projects = listOf(AtlasTypedFilterFixture.project),
        request = AtlasProjectFrameRequest(AtlasLens.SYMBOLS, relationshipCountFilter = filter),
    )

private fun zone.clanker.report.model.AtlasFrame.nonPrimaryNodeIds(): List<String> =
    nodes.filterNot(AtlasNode::primary).map(AtlasNode::id)

private val allRelationshipNodeIds =
    listOf(
        AtlasTypedFilterFixture.ALPHA_SYMBOL_ID,
        AtlasTypedFilterFixture.BETA_SYMBOL_ID,
        AtlasTypedFilterFixture.GAMMA_SYMBOL_ID,
    )
