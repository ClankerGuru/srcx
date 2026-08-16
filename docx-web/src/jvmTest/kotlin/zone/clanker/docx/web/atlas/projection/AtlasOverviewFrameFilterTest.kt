package zone.clanker.docx.web.atlas.projection

import zone.clanker.docx.web.atlas.session.AtlasDeclarationKind
import zone.clanker.docx.web.atlas.session.AtlasFilters
import zone.clanker.docx.web.atlas.session.AtlasRelationshipDirection
import zone.clanker.docx.web.atlas.session.AtlasRelationshipFilter
import zone.clanker.docx.web.atlas.session.AtlasSearchFilter
import zone.clanker.docx.web.atlas.session.AtlasSearchTarget
import zone.clanker.docx.web.fixture.DocxWebFixture
import zone.clanker.report.model.AtlasLens
import zone.clanker.report.model.WorkspaceGraphRelationKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AtlasOverviewFrameFilterTest {
    @Test
    fun filtersTheBoundedSymbolOverviewByDeclarationWithoutLoadingProjectShards() {
        val symbols = DocxWebFixture.atlasOverviews.frame(AtlasLens.SYMBOLS)
        val filtered =
            symbols.filteredOverview(
                overviewDefaultFilters().copy(declarations = listOf(AtlasDeclarationKind.INTERFACE)),
            )

        assertEquals(listOf("symbol:fixture:library-port"), filtered.nodes.map { node -> node.id })
        assertEquals(1, filtered.matchingNodeCount)
        assertTrue(filtered.edges.isEmpty())
    }

    @Test
    fun retainsExactInverseContextForTypedSearchAndMarksItAsContext() {
        val symbols = DocxWebFixture.atlasOverviews.frame(AtlasLens.SYMBOLS)
        val filtered =
            symbols.filteredOverview(
                overviewDefaultFilters().copy(
                    search = AtlasSearchFilter("Consumer", listOf(AtlasSearchTarget.SYMBOL)),
                ),
            )

        assertEquals(
            setOf("symbol:fixture:consumer", "symbol:fixture:target"),
            filtered.nodes.mapTo(mutableSetOf()) { it.id },
        )
        assertTrue(filtered.nodes.single { node -> node.id == "symbol:fixture:consumer" }.primary)
        assertFalse(filtered.nodes.single { node -> node.id == "symbol:fixture:target" }.primary)
        assertEquals(1, filtered.edges.single().recordCount)
    }

    @Test
    fun appliesRelationshipKindAndDirectionalStrictCountFiltersToTheVisibleFrame() {
        val files = DocxWebFixture.atlasOverviews.frame(AtlasLens.FILES)
        val withoutConstructors =
            files.filteredOverview(
                overviewDefaultFilters().copy(
                    relationships =
                        overviewDefaultFilters().relationships.copy(
                            kinds = listOf(WorkspaceGraphRelationKind.CALL),
                        ),
                ),
            )
        assertEquals(3, withoutConstructors.nodes.size)
        assertTrue(withoutConstructors.edges.isEmpty())
        assertEquals(0, withoutConstructors.shownRelationshipRecordCount)

        val outgoing =
            files.filteredOverview(
                overviewDefaultFilters().copy(
                    relationships =
                        overviewDefaultFilters().relationships.copy(
                            direction = AtlasRelationshipDirection.OUTGOING,
                            minimumCountExclusive = 0,
                        ),
                    search = AtlasSearchFilter("Consumer.kt", listOf(AtlasSearchTarget.FILE)),
                ),
            )
        assertEquals(1, outgoing.matchingNodeCount)
        assertEquals(
            setOf("file:fixture:consumer", "file:fixture:target"),
            outgoing.nodes.mapTo(mutableSetOf()) { it.id },
        )
        assertTrue(outgoing.nodes.single { node -> node.id == "file:fixture:consumer" }.primary)
        assertFalse(outgoing.nodes.single { node -> node.id == "file:fixture:target" }.primary)

        val incoming =
            files.filteredOverview(
                overviewDefaultFilters().copy(
                    relationships =
                        overviewDefaultFilters().relationships.copy(
                            direction = AtlasRelationshipDirection.INCOMING,
                            minimumCountExclusive = 0,
                        ),
                    search = AtlasSearchFilter("Target.kt", listOf(AtlasSearchTarget.FILE)),
                ),
            )
        assertTrue(incoming.nodes.single { node -> node.id == "file:fixture:target" }.primary)
        assertFalse(incoming.nodes.single { node -> node.id == "file:fixture:consumer" }.primary)
    }

    @Test
    fun declarationSelectionNeverErasesAFileOverview() {
        val files = DocxWebFixture.atlasOverviews.frame(AtlasLens.FILES)
        val filtered =
            files.filteredOverview(
                overviewDefaultFilters().copy(declarations = listOf(AtlasDeclarationKind.INTERFACE)),
            )

        assertEquals(files.nodes, filtered.nodes)
        assertEquals(files.edges, filtered.edges)
    }
}

private fun overviewDefaultFilters(): AtlasFilters =
    AtlasFilters(
        declarations = AtlasDeclarationKind.entries,
        relationships =
            AtlasRelationshipFilter(
                kinds = WorkspaceGraphRelationKind.entries.sortedBy { kind -> kind.name },
            ),
    )
