package zone.clanker.docx.web.atlas.projection

import zone.clanker.report.model.DeclarationSemantic
import zone.clanker.report.model.ProjectGraphShard
import zone.clanker.report.model.ReferenceKind
import zone.clanker.report.model.ReferenceSnapshot
import zone.clanker.report.model.RelationshipEvidence
import zone.clanker.report.model.RelationshipKind
import zone.clanker.report.model.RelationshipSnapshot
import zone.clanker.report.model.SourceFileSnapshot
import zone.clanker.report.model.SourceLanguage
import zone.clanker.report.model.SourceSetSnapshot
import zone.clanker.report.model.SymbolKind
import zone.clanker.report.model.SymbolSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BoundedGraphFrameTest {
    @Test
    fun boundsASymbolPageWhileRetainingOnlyTheEndpointClosureThatFits() {
        val project = highCardinalityProject()

        val firstPage = boundedGraphFrame(project, GraphFrameRequest(query = "", requestedPage = 0))
        val secondPage = boundedGraphFrame(project, GraphFrameRequest(query = "", requestedPage = 1))
        val preferredRelationship = relationshipId(RELATIONSHIP_COUNT - 1)
        val preferredFrame =
            boundedGraphFrame(
                project,
                GraphFrameRequest(
                    query = "",
                    requestedPage = 0,
                    preferredRelationshipId = preferredRelationship,
                ),
            )

        assertEquals((0 until PRIMARY_PAGE_SIZE).map(::symbolId), firstPage.primaryNodeIds)
        assertEquals(
            firstPage.primaryNodeIds +
                (ENDPOINT_START_INDEX until ENDPOINT_START_INDEX + EXPANDED_ENDPOINT_COUNT).map(::symbolId),
            firstPage.visibleNodeIds,
        )
        assertEquals(MAX_VISIBLE_NODES, firstPage.visibleNodeIds.size)
        assertEquals(
            (0 until EXPANDED_ENDPOINT_COUNT).map(::relationshipId),
            firstPage.visibleRelationshipIds,
        )
        assertEquals(TOTAL_ENTRY_COUNT, firstPage.totalNodeCount)
        assertEquals(TOTAL_ENTRY_COUNT, firstPage.matchingNodeCount)
        assertEquals(0, firstPage.pageIndex)
        assertEquals(UNFILTERED_PAGE_COUNT, firstPage.pageCount)

        assertEquals(
            (PRIMARY_PAGE_SIZE until PRIMARY_PAGE_SIZE * 2).map(::symbolId),
            secondPage.primaryNodeIds,
        )
        assertTrue(
            firstPage.primaryNodeIds
                .toSet()
                .intersect(secondPage.primaryNodeIds.toSet())
                .isEmpty(),
        )
        assertEquals(secondPage, boundedGraphFrame(project, GraphFrameRequest(query = "", requestedPage = 1)))
        assertEquals(MAX_VISIBLE_NODES, preferredFrame.visibleNodeIds.size)
        assertTrue(preferredRelationship in preferredFrame.visibleRelationshipIds)
        assertTrue(symbolId(ENDPOINT_START_INDEX + RELATIONSHIP_COUNT - 1) in preferredFrame.visibleNodeIds)
    }

    @Test
    fun refillsSymbolPagesFromAllQueryMatchesAndLocatesTheirUnfilteredPages() {
        val project = highCardinalityProject()

        val firstMatchPage =
            boundedGraphFrame(project, GraphFrameRequest(query = MATCH_QUERY, requestedPage = 0))
        val secondMatchPage =
            boundedGraphFrame(project, GraphFrameRequest(query = MATCH_QUERY, requestedPage = 1))
        val symbolsById = project.symbols.associateBy(SymbolSnapshot::id)

        assertEquals(TOTAL_MATCHING_COUNT, firstMatchPage.matchingNodeCount)
        assertEquals(TOTAL_ENTRY_COUNT, firstMatchPage.totalNodeCount)
        assertEquals(MATCHING_PAGE_COUNT, firstMatchPage.pageCount)
        assertEquals(PRIMARY_PAGE_SIZE, firstMatchPage.primaryNodeIds.size)
        assertEquals(symbolId(FIRST_MATCH_PAGE_LAST_INDEX), firstMatchPage.primaryNodeIds.last())
        assertTrue(
            firstMatchPage.primaryNodeIds.all { symbolId ->
                symbolsById.getValue(symbolId).name.startsWith(MATCH_NAME_PREFIX)
            },
        )
        assertEquals(TOTAL_MATCHING_COUNT - PRIMARY_PAGE_SIZE, secondMatchPage.primaryNodeIds.size)
        assertEquals(LOCATED_PAGE_INDEX, pageContainingNode(project, symbolId(LOCATED_ENTRY_INDEX)))
    }

    @Test
    fun boundsAndRefillsFilePagesUsingResolvedRelationshipEndpoints() {
        val project = highCardinalityProject()

        val firstPage = boundedFileGraphFrame(project, GraphFrameRequest(query = "", requestedPage = 0))
        val firstMatchPage =
            boundedFileGraphFrame(project, GraphFrameRequest(query = FILE_MATCH_QUERY, requestedPage = 0))
        val secondPage = boundedFileGraphFrame(project, GraphFrameRequest(query = "", requestedPage = 1))
        val preferredRelationship = relationshipId(RELATIONSHIP_COUNT - 1)
        val preferredFrame =
            boundedFileGraphFrame(
                project,
                GraphFrameRequest(
                    query = "",
                    requestedPage = 0,
                    preferredRelationshipId = preferredRelationship,
                ),
            )

        assertEquals((0 until PRIMARY_PAGE_SIZE).map(::fileId), firstPage.primaryFileIds)
        assertEquals(
            firstPage.primaryFileIds +
                (ENDPOINT_START_INDEX until ENDPOINT_START_INDEX + EXPANDED_ENDPOINT_COUNT).map(::fileId),
            firstPage.visibleFileIds,
        )
        assertEquals(MAX_VISIBLE_NODES, firstPage.visibleFileIds.size)
        assertEquals((0 until EXPANDED_ENDPOINT_COUNT).map(::relationshipId), firstPage.visibleRelationshipIds)
        assertEquals(TOTAL_ENTRY_COUNT, firstPage.totalFileCount)
        assertEquals(TOTAL_ENTRY_COUNT, firstPage.matchingFileCount)
        assertEquals(UNFILTERED_PAGE_COUNT, firstPage.pageCount)

        assertEquals(TOTAL_MATCHING_COUNT, firstMatchPage.matchingFileCount)
        assertEquals(MATCHING_PAGE_COUNT, firstMatchPage.pageCount)
        assertEquals(PRIMARY_PAGE_SIZE, firstMatchPage.primaryFileIds.size)
        assertEquals(fileId(FIRST_MATCH_PAGE_LAST_INDEX), firstMatchPage.primaryFileIds.last())
        assertEquals(
            (PRIMARY_PAGE_SIZE until PRIMARY_PAGE_SIZE * 2).map(::fileId),
            secondPage.primaryFileIds,
        )
        assertEquals(secondPage, boundedFileGraphFrame(project, GraphFrameRequest(query = "", requestedPage = 1)))
        assertEquals(LOCATED_PAGE_INDEX, pageContainingFile(project, fileId(LOCATED_ENTRY_INDEX)))
        assertEquals(MAX_VISIBLE_NODES, preferredFrame.visibleFileIds.size)
        assertTrue(preferredRelationship in preferredFrame.visibleRelationshipIds)
        assertTrue(fileId(ENDPOINT_START_INDEX + RELATIONSHIP_COUNT - 1) in preferredFrame.visibleFileIds)
    }

    private fun highCardinalityProject(): ProjectGraphShard =
        ProjectGraphShard(
            projectId = PROJECT_ID,
            sourceSets =
                listOf(
                    SourceSetSnapshot(
                        id = SOURCE_SET_ID,
                        projectId = PROJECT_ID,
                        name = "main",
                        sourceDirectories = listOf("src/main/kotlin"),
                    ),
                ),
            files = (0 until TOTAL_ENTRY_COUNT).map(::sourceFile),
            symbols = (0 until TOTAL_ENTRY_COUNT).map(::symbol),
            references = (0 until RELATIONSHIP_COUNT).map(::reference),
            relationships = (0 until RELATIONSHIP_COUNT).map(::relationship),
        )

    private fun sourceFile(index: Int): SourceFileSnapshot =
        SourceFileSnapshot(
            id = fileId(index),
            sourceSetId = SOURCE_SET_ID,
            projectRelativePath = filePath(index),
            language = SourceLanguage.KOTLIN,
        )

    private fun symbol(index: Int): SymbolSnapshot =
        SymbolSnapshot(
            id = symbolId(index),
            fileId = fileId(index),
            name = symbolName(index),
            qualifiedName = qualifiedName(index),
            packageName = "fixture.slot${padded(index)}",
            kind = SymbolKind.CLASS,
            declarationSemantic = DeclarationSemantic.CONCRETE_CLASS,
            declarationLine = 1,
        )

    private fun reference(index: Int): ReferenceSnapshot {
        val targetIndex = ENDPOINT_START_INDEX + index
        return ReferenceSnapshot(
            id = "reference:${padded(index)}",
            sourceFileId = fileId(index),
            sourceSymbolId = symbolId(index),
            line = 1,
            context = "${symbolName(targetIndex)}()",
            targetName = symbolName(targetIndex),
            targetQualifiedName = qualifiedName(targetIndex),
            kind = ReferenceKind.CALL,
            evidence = RelationshipEvidence.DIRECT,
        )
    }

    private fun relationship(index: Int): RelationshipSnapshot =
        RelationshipSnapshot(
            id = relationshipId(index),
            referenceId = "reference:${padded(index)}",
            sourceSymbolId = symbolId(index),
            targetSymbolId = symbolId(ENDPOINT_START_INDEX + index),
            kind = RelationshipKind.CALL,
            resolutionEvidence = RelationshipEvidence.DIRECT,
        )

    private fun filePath(index: Int): String {
        val matchMarker = if (index % MATCH_INTERVAL == 0) "-selected" else ""
        return "src/main/kotlin/fixture/Node${padded(index)}$matchMarker.kt"
    }

    private fun symbolName(index: Int): String =
        if (index % MATCH_INTERVAL == 0) "$MATCH_NAME_PREFIX${padded(index)}" else "Node${padded(index)}"

    private fun qualifiedName(index: Int): String = "fixture.slot${padded(index)}.${symbolName(index)}"

    private fun fileId(index: Int): String = "file:${padded(index)}"

    private fun symbolId(index: Int): String = "symbol:${padded(index)}"

    private fun relationshipId(index: Int): String = "relationship:${padded(index)}"

    private fun padded(index: Int): String = index.toString().padStart(ID_WIDTH, '0')

    private companion object {
        const val PROJECT_ID = "project:bounded-frame"
        const val SOURCE_SET_ID = "source-set:bounded-frame:main"
        const val TOTAL_ENTRY_COUNT = 120
        const val RELATIONSHIP_COUNT = 20
        const val ENDPOINT_START_INDEX = 100
        const val EXPANDED_ENDPOINT_COUNT = MAX_VISIBLE_NODES - PRIMARY_PAGE_SIZE
        const val MATCH_INTERVAL = 3
        const val TOTAL_MATCHING_COUNT = 40
        const val FIRST_MATCH_PAGE_LAST_INDEX = 81
        const val LOCATED_ENTRY_INDEX = 84
        const val LOCATED_PAGE_INDEX = 3
        const val UNFILTERED_PAGE_COUNT = 5
        const val MATCHING_PAGE_COUNT = 2
        const val ID_WIDTH = 3
        const val MATCH_QUERY = "match"
        const val MATCH_NAME_PREFIX = "Match"
        const val FILE_MATCH_QUERY = "selected"
    }
}
