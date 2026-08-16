package zone.clanker.docx.web.atlas.projection

import zone.clanker.docx.web.fixture.DocxWebFixture
import zone.clanker.report.model.AtlasEdgeCategory
import zone.clanker.report.model.AtlasFrameJson
import zone.clanker.report.model.AtlasLens
import zone.clanker.report.model.ReferenceKind
import zone.clanker.report.model.ReferenceSnapshot
import zone.clanker.report.model.RelationshipEvidence
import zone.clanker.report.model.RelationshipKind
import zone.clanker.report.model.RelationshipSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AtlasFrameProjectorTest {
    @Test
    fun aggregatesNonImportFileRecordsAndKeepsInternalRecordsOnTheirNode() {
        val project = projectWithImportAggregateAndInternalRecord()

        val atlas =
            atlasProjectFrame(
                summary = DocxWebFixture.summary,
                project = project,
                request =
                    AtlasProjectFrameRequest(
                        lens = AtlasLens.FILES,
                        selectedRelationshipId = NAME_RELATIONSHIP_ID,
                    ),
            )

        assertEquals(2, atlas.nodes.size)
        assertEquals(1, atlas.edges.size)
        val edge = atlas.edges.single()
        assertEquals(CONSTRUCTOR_RELATIONSHIP_ID, edge.id)
        assertEquals(listOf(CONSTRUCTOR_RELATIONSHIP_ID, NAME_RELATIONSHIP_ID), edge.relationshipIds)
        assertEquals(listOf(CONSTRUCTOR_REFERENCE_ID, NAME_REFERENCE_ID), edge.referenceIds)
        assertEquals(2, edge.recordCount)
        assertEquals(AtlasEdgeCategory.CALLS, edge.category)
        assertTrue(edge.hasHeuristic)
        assertFalse(IMPORT_RELATIONSHIP_ID in edge.relationshipIds)
        assertEquals(NAME_RELATIONSHIP_ID, atlas.selectedEdgeId)
        assertEquals(3, atlas.totalRelationshipRecordCount)
        assertEquals(3, atlas.shownRelationshipRecordCount)
        val consumer = atlas.nodes.single { it.id == CONSUMER_FILE_ID }
        assertEquals(1, consumer.internalRecordCount)
        assertEquals(3, consumer.relationshipRecordCount)
        assertEquals(CONSUMER_FILE_ID, consumer.sourceFileId)
        assertEquals(":app", consumer.projectPath)
        assertEquals("fixture", consumer.buildName)
    }

    @Test
    fun symbolProjectionUsesRawInspectorIdsAndKotlinxSerializationRoundTrips() {
        val project = projectWithImportAggregateAndInternalRecord()

        val atlas =
            atlasProjectFrame(
                summary = DocxWebFixture.summary,
                project = project,
                request =
                    AtlasProjectFrameRequest(
                        lens = AtlasLens.SYMBOLS,
                        selectedNodeIds = listOf(TARGET_SYMBOL_ID, CONSUMER_SYMBOL_ID, CONSUMER_SYMBOL_ID),
                        selectedRelationshipId = NAME_RELATIONSHIP_ID,
                    ),
            )
        val decoded = AtlasFrameJson.decode(AtlasFrameJson.encode(atlas))

        assertEquals(atlas, decoded)
        assertEquals(listOf(CONSUMER_SYMBOL_ID, TARGET_SYMBOL_ID), atlas.selectedNodeIds)
        assertEquals(listOf(CONSTRUCTOR_RELATIONSHIP_ID, NAME_RELATIONSHIP_ID), atlas.edges.single().relationshipIds)
        assertEquals(1, atlas.nodes.single { it.id == CONSUMER_SYMBOL_ID }.internalRecordCount)
        assertEquals(1, atlas.nodes.single { it.id == TARGET_SYMBOL_ID }.sourceFileFindingCount)
        assertTrue(atlas.nodes.all { it.id.startsWith("symbol:") })
        assertTrue(atlas.nodes.all { it.semantic != null })
    }

    @Test
    fun importOnlyContextDoesNotCreateAProjectFileEdgeOrContextNode() {
        val project = projectWithImportAggregateAndInternalRecord()
        val importOnly =
            project.copy(
                references = project.references.filter { it.id == IMPORT_REFERENCE_ID },
                relationships = project.relationships.filter { it.id == IMPORT_RELATIONSHIP_ID },
                findings = emptyList(),
            )
        val atlas =
            atlasProjectFrame(
                summary = DocxWebFixture.summary,
                project = importOnly,
                request = AtlasProjectFrameRequest(lens = AtlasLens.FILES, query = "Consumer.kt"),
            )

        assertEquals(listOf(CONSUMER_FILE_ID), atlas.nodes.map { it.id })
        assertTrue(atlas.edges.isEmpty())
        assertEquals(0, atlas.totalRelationshipRecordCount)
    }

    private fun projectWithImportAggregateAndInternalRecord() =
        DocxWebFixture.project.copy(
            references =
                (DocxWebFixture.project.references + additionalReferences())
                    .sortedBy(ReferenceSnapshot::id),
            relationships =
                (DocxWebFixture.project.relationships + additionalRelationships())
                    .sortedBy(RelationshipSnapshot::id),
        )

    private fun additionalReferences(): List<ReferenceSnapshot> =
        listOf(
            ReferenceSnapshot(
                id = IMPORT_REFERENCE_ID,
                sourceFileId = CONSUMER_FILE_ID,
                line = 1,
                context = "import fixture.Target",
                targetName = "Target",
                targetQualifiedName = "fixture.Target",
                kind = ReferenceKind.IMPORT,
                evidence = RelationshipEvidence.DIRECT,
            ),
            ReferenceSnapshot(
                id = NAME_REFERENCE_ID,
                sourceFileId = CONSUMER_FILE_ID,
                sourceSymbolId = CONSUMER_SYMBOL_ID,
                line = 3,
                context = "val target: Target",
                targetName = "Target",
                targetQualifiedName = "fixture.Target",
                kind = ReferenceKind.NAME_REFERENCE,
                evidence = RelationshipEvidence.HEURISTIC,
            ),
            ReferenceSnapshot(
                id = SELF_REFERENCE_ID,
                sourceFileId = CONSUMER_FILE_ID,
                sourceSymbolId = CONSUMER_SYMBOL_ID,
                line = 3,
                context = "Consumer()",
                targetName = "Consumer",
                targetQualifiedName = "fixture.Consumer",
                kind = ReferenceKind.CALL,
                evidence = RelationshipEvidence.DERIVED,
            ),
        )

    private fun additionalRelationships(): List<RelationshipSnapshot> =
        listOf(
            RelationshipSnapshot(
                id = IMPORT_RELATIONSHIP_ID,
                referenceId = IMPORT_REFERENCE_ID,
                targetSymbolId = TARGET_SYMBOL_ID,
                kind = RelationshipKind.IMPORT,
                resolutionEvidence = RelationshipEvidence.DIRECT,
            ),
            RelationshipSnapshot(
                id = NAME_RELATIONSHIP_ID,
                referenceId = NAME_REFERENCE_ID,
                sourceSymbolId = CONSUMER_SYMBOL_ID,
                targetSymbolId = TARGET_SYMBOL_ID,
                kind = RelationshipKind.NAME_REFERENCE,
                resolutionEvidence = RelationshipEvidence.HEURISTIC,
            ),
            RelationshipSnapshot(
                id = SELF_RELATIONSHIP_ID,
                referenceId = SELF_REFERENCE_ID,
                sourceSymbolId = CONSUMER_SYMBOL_ID,
                targetSymbolId = CONSUMER_SYMBOL_ID,
                kind = RelationshipKind.CALL,
                resolutionEvidence = RelationshipEvidence.DERIVED,
            ),
        )

    private companion object {
        const val CONSUMER_FILE_ID = "file:fixture:consumer"
        const val TARGET_FILE_ID = "file:fixture:target"
        const val CONSUMER_SYMBOL_ID = "symbol:fixture:consumer"
        const val TARGET_SYMBOL_ID = "symbol:fixture:target"
        const val CONSTRUCTOR_REFERENCE_ID = "reference:fixture:constructor"
        const val IMPORT_REFERENCE_ID = "reference:fixture:import"
        const val NAME_REFERENCE_ID = "reference:fixture:name"
        const val SELF_REFERENCE_ID = "reference:fixture:self"
        const val CONSTRUCTOR_RELATIONSHIP_ID = "relationship:fixture:constructor"
        const val IMPORT_RELATIONSHIP_ID = "relationship:fixture:import"
        const val NAME_RELATIONSHIP_ID = "relationship:fixture:name"
        const val SELF_RELATIONSHIP_ID = "relationship:fixture:self"
    }
}
