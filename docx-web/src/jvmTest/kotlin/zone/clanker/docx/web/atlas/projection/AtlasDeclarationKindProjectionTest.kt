package zone.clanker.docx.web.atlas.projection

import zone.clanker.report.model.AtlasLens
import zone.clanker.report.model.AtlasNode
import zone.clanker.report.model.SymbolKind
import kotlin.test.Test
import kotlin.test.assertContentEquals

class AtlasDeclarationKindProjectionTest {
    @Test
    fun projectsInterfacePrimariesConsistentlyAcrossEveryLens() {
        expectedInterfacePrimaryIds.forEach { (lens, expectedIds) ->
            assertContentEquals(expectedIds, declarationFrame(lens, SymbolKind.INTERFACE).primaryNodeIds())
        }
    }

    @Test
    fun keepsClassAndInterfacePrimariesDistinctAcrossEveryLens() {
        expectedClassPrimaryIds.forEach { (lens, expectedIds) ->
            assertContentEquals(expectedIds, declarationFrame(lens, SymbolKind.CLASS).primaryNodeIds())
        }
    }

    @Test
    fun retainsAnIncidentOwningFileOnlyAsNonPrimaryClosure() {
        val frame = declarationFrame(AtlasLens.FILES, SymbolKind.INTERFACE)

        assertContentEquals(listOf(AtlasTypedFilterFixture.BETA_FILE_ID), frame.primaryNodeIds())
        assertContentEquals(
            listOf(AtlasTypedFilterFixture.ALPHA_FILE_ID),
            frame.nodes.filterNot(AtlasNode::primary).map(AtlasNode::id),
        )
    }
}

private fun declarationFrame(
    lens: AtlasLens,
    symbolKind: SymbolKind,
) =
    atlasProjectFrame(
        summary = AtlasTypedFilterFixture.summary,
        project = AtlasTypedFilterFixture.project,
        request = AtlasProjectFrameRequest(lens = lens, symbolKinds = setOf(symbolKind)),
    )

private val expectedInterfacePrimaryIds =
    mapOf(
        AtlasLens.FILES to listOf(AtlasTypedFilterFixture.BETA_FILE_ID),
        AtlasLens.SYMBOLS to listOf(AtlasTypedFilterFixture.BETA_SYMBOL_ID),
        AtlasLens.PROBLEMS to listOf(AtlasTypedFilterFixture.BETA_SYMBOL_ID),
        AtlasLens.CYCLES to listOf(AtlasTypedFilterFixture.BETA_SYMBOL_ID),
    )

private val expectedClassPrimaryIds =
    mapOf(
        AtlasLens.FILES to listOf(AtlasTypedFilterFixture.ALPHA_FILE_ID),
        AtlasLens.SYMBOLS to listOf(AtlasTypedFilterFixture.ALPHA_SYMBOL_ID),
        AtlasLens.PROBLEMS to listOf(AtlasTypedFilterFixture.ALPHA_SYMBOL_ID),
        AtlasLens.CYCLES to listOf(AtlasTypedFilterFixture.ALPHA_SYMBOL_ID),
    )
