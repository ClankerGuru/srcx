@file:Suppress("LongMethod", "TooManyFunctions")

package zone.clanker.docx.web.atlas.projection

import zone.clanker.docx.web.fixture.DocxWebFixture
import zone.clanker.report.model.ArchitectureComponentSnapshot
import zone.clanker.report.model.ArchitectureCycleSnapshot
import zone.clanker.report.model.ArchitectureDependencySnapshot
import zone.clanker.report.model.ArchitectureLayer
import zone.clanker.report.model.AtlasFrame
import zone.clanker.report.model.AtlasFrameJson
import zone.clanker.report.model.AtlasLens
import zone.clanker.report.model.AtlasNodeType
import zone.clanker.report.model.CycleSnapshot
import zone.clanker.report.model.DeclarationSemantic
import zone.clanker.report.model.FindingSeverity
import zone.clanker.report.model.FindingSnapshot
import zone.clanker.report.model.ProjectAnalysisSnapshot
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AtlasProjectFrameAcceptanceTest {
    @Test
    fun returnsEveryMatchingFileAndSymbolInOneProjectSourceScopeFrame() {
        val project = highCardinalityProject()
        val expectedFileIds = (0 until MAIN_NODE_COUNT).map(::fileId)
        val expectedSymbolIds = (0 until MAIN_NODE_COUNT).map(::symbolId)
        val files =
            atlasProjectFrame(
                summary = DocxWebFixture.summary,
                project = project,
                request =
                    AtlasProjectFrameRequest(
                        lens = AtlasLens.FILES,
                        query = MAIN_NODE_PREFIX,
                        sourceSetIds = setOf(MAIN_SOURCE_SET_ID),
                    ),
            )
        val symbols =
            atlasProjectFrame(
                summary = DocxWebFixture.summary,
                project = project,
                request =
                    AtlasProjectFrameRequest(
                        lens = AtlasLens.SYMBOLS,
                        query = MAIN_NODE_PREFIX,
                        sourceSetIds = setOf(MAIN_SOURCE_SET_ID),
                    ),
            )

        assertEquals(expectedFileIds, files.nodes.map { node -> node.id })
        assertEquals(expectedSymbolIds, symbols.nodes.map { node -> node.id })
        assertTrue(files.nodes.all { node -> node.type == AtlasNodeType.FILE })
        assertTrue(symbols.nodes.all { node -> node.type == AtlasNodeType.SYMBOL })
        assertFullSingleFrame(files, TOTAL_NODE_COUNT, MAIN_NODE_COUNT)
        assertFullSingleFrame(symbols, TOTAL_NODE_COUNT, MAIN_NODE_COUNT)
        assertEquals(files, AtlasFrameJson.decode(AtlasFrameJson.encode(files)))
        assertEquals(symbols, AtlasFrameJson.decode(AtlasFrameJson.encode(symbols)))
    }

    @Test
    fun problemsLensUsesOnlySerializedFindingEvidenceAndRawNodeIds() {
        val project = evidenceProject()
        val expectedNodeIds =
            listOf(
                CONSUMER_FILE_ID,
                TARGET_FILE_ID,
                CONSUMER_SYMBOL_ID,
                TARGET_SYMBOL_ID,
            )
        val frame =
            atlasProjectFrame(
                summary = DocxWebFixture.summary,
                project = project,
                request =
                    AtlasProjectFrameRequest(
                        lens = AtlasLens.PROBLEMS,
                        selectedNodeIds = listOf(TARGET_FILE_ID, CONSUMER_SYMBOL_ID),
                        selectedRelationshipId = REVERSE_RELATIONSHIP_ID,
                    ),
            )

        assertEquals(AtlasLens.PROBLEMS, frame.lens)
        assertEquals(expectedNodeIds, frame.nodes.map { node -> node.id })
        assertEquals(listOf(TARGET_FILE_ID, CONSUMER_SYMBOL_ID), frame.selectedNodeIds)
        assertEquals(REVERSE_RELATIONSHIP_ID, frame.selectedEdgeId)
        assertEquals(
            setOf(DIRECT_FINDING_ID, ANALYSIS_FINDING_ID),
            frame.nodes.flatMap { node -> node.findingIds }.toSet(),
        )
        assertTrue(frame.nodes.all { node -> node.hasAnalyzerFinding })
        assertEquals(
            project.relationships.map(RelationshipSnapshot::id).toSet(),
            frame.edges.flatMap { edge -> edge.relationshipIds }.toSet(),
        )
        assertFullSingleFrame(frame, expectedNodeIds.size, expectedNodeIds.size)
        assertEquals(frame, AtlasFrameJson.decode(AtlasFrameJson.encode(frame)))
    }

    @Test
    fun cyclesLensCombinesObservedAndAnalysisEvidenceWithoutInventingCountedEdges() {
        val project = evidenceProject()
        val expectedNodeIds = listOf(TARGET_FILE_ID, CONSUMER_SYMBOL_ID, TARGET_SYMBOL_ID)
        val frame =
            atlasProjectFrame(
                summary = DocxWebFixture.summary,
                project = project,
                request =
                    AtlasProjectFrameRequest(
                        lens = AtlasLens.CYCLES,
                        selectedNodeIds = listOf(TARGET_FILE_ID),
                        selectedRelationshipId = REVERSE_RELATIONSHIP_ID,
                    ),
            )

        assertEquals(AtlasLens.CYCLES, frame.lens)
        assertEquals(expectedNodeIds, frame.nodes.map { node -> node.id })
        assertEquals(listOf(TARGET_FILE_ID), frame.selectedNodeIds)
        assertEquals(REVERSE_RELATIONSHIP_ID, frame.selectedEdgeId)
        assertTrue(frame.nodes.single { node -> node.id == CONSUMER_SYMBOL_ID }.hasAnalysisCycle)
        assertTrue(frame.nodes.single { node -> node.id == TARGET_FILE_ID }.hasAnalysisCycle)
        assertFalse(frame.nodes.single { node -> node.id == TARGET_SYMBOL_ID }.hasAnalysisCycle)
        assertTrue(frame.edges.all { edge -> edge.isObservedCycleEdge })
        assertTrue(frame.edges.none { edge -> edge.isAnalysisCycleEdge })
        assertEquals(
            project.cycles
                .single()
                .relationshipIds
                .toSet(),
            frame.edges
                .flatMap { edge -> edge.relationshipIds }
                .toSet(),
        )
        assertFullSingleFrame(frame, expectedNodeIds.size, expectedNodeIds.size)
        assertEquals(frame, AtlasFrameJson.decode(AtlasFrameJson.encode(frame)))
    }

    private fun assertFullSingleFrame(
        frame: AtlasFrame,
        expectedTotal: Int,
        expectedMatching: Int,
    ) {
        assertEquals(expectedTotal, frame.totalNodeCount)
        assertEquals(expectedMatching, frame.matchingNodeCount)
        assertEquals(0, frame.pageIndex)
        assertEquals(if (expectedMatching == 0) 0 else 1, frame.pageCount)
    }

    private fun highCardinalityProject(): ProjectGraphShard {
        val sourceSets =
            listOf(
                SourceSetSnapshot(MAIN_SOURCE_SET_ID, PROJECT_ID, "main"),
                SourceSetSnapshot(TEST_SOURCE_SET_ID, PROJECT_ID, "test"),
            )
        return ProjectGraphShard(
            projectId = PROJECT_ID,
            sourceSets = sourceSets,
            files = (0 until TOTAL_NODE_COUNT).map(::sourceFile),
            symbols = (0 until TOTAL_NODE_COUNT).map(::symbol),
            relationships = emptyList(),
        )
    }

    private fun sourceFile(index: Int): SourceFileSnapshot {
        val main = index < MAIN_NODE_COUNT
        val prefix = if (main) MAIN_NODE_PREFIX else TEST_NODE_PREFIX
        val sourceSet = if (main) MAIN_SOURCE_SET_ID else TEST_SOURCE_SET_ID
        val directory = if (main) "main" else "test"
        return SourceFileSnapshot(
            id = fileId(index),
            sourceSetId = sourceSet,
            projectRelativePath = "src/$directory/kotlin/fixture/$prefix${padded(index)}.kt",
            language = SourceLanguage.KOTLIN,
        )
    }

    private fun symbol(index: Int): SymbolSnapshot {
        val prefix = if (index < MAIN_NODE_COUNT) MAIN_NODE_PREFIX else TEST_NODE_PREFIX
        val name = "$prefix${padded(index)}"
        return SymbolSnapshot(
            id = symbolId(index),
            fileId = fileId(index),
            name = name,
            qualifiedName = "fixture.$name",
            packageName = "fixture",
            kind = SymbolKind.CLASS,
            declarationSemantic = DeclarationSemantic.CONCRETE_CLASS,
            declarationLine = 1,
        )
    }

    private fun evidenceProject(): ProjectGraphShard {
        val project = DocxWebFixture.project
        val reverseReference =
            ReferenceSnapshot(
                id = REVERSE_REFERENCE_ID,
                sourceFileId = TARGET_FILE_ID,
                sourceSymbolId = TARGET_SYMBOL_ID,
                line = DECLARATION_LINE,
                context = "Consumer()",
                targetName = "Consumer",
                targetQualifiedName = "fixture.Consumer",
                kind = ReferenceKind.CALL,
                evidence = RelationshipEvidence.DIRECT,
            )
        val reverseRelationship =
            RelationshipSnapshot(
                id = REVERSE_RELATIONSHIP_ID,
                referenceId = REVERSE_REFERENCE_ID,
                sourceSymbolId = TARGET_SYMBOL_ID,
                targetSymbolId = CONSUMER_SYMBOL_ID,
                kind = RelationshipKind.CALL,
                resolutionEvidence = RelationshipEvidence.DIRECT,
            )
        return project.copy(
            references = (project.references + reverseReference).sortedBy(ReferenceSnapshot::id),
            relationships = (project.relationships + reverseRelationship).sortedBy(RelationshipSnapshot::id),
            cycles =
                listOf(
                    CycleSnapshot(
                        id = OBSERVED_CYCLE_ID,
                        symbolIds = listOf(CONSUMER_SYMBOL_ID, TARGET_SYMBOL_ID, CONSUMER_SYMBOL_ID),
                        relationshipIds = listOf(FORWARD_RELATIONSHIP_ID, REVERSE_RELATIONSHIP_ID),
                    ),
                ),
            analysis = projectAnalysis(),
        )
    }

    private fun projectAnalysis(): ProjectAnalysisSnapshot =
        ProjectAnalysisSnapshot(
            id = ANALYSIS_ID,
            projectId = PROJECT_ID,
            findings =
                listOf(
                    FindingSnapshot(
                        id = ANALYSIS_FINDING_ID,
                        severity = FindingSeverity.INFO,
                        message = "The target file participates in an inferred component boundary.",
                        suggestion = "Review the component ownership before changing the boundary.",
                        resolvedComponentIds = listOf(TARGET_COMPONENT_ID),
                    ),
                ),
            components =
                listOf(
                    ArchitectureComponentSnapshot(
                        id = CONSUMER_COMPONENT_ID,
                        componentId = "fixture.Consumer",
                        name = "Consumer",
                        packageName = "fixture",
                        packageGroup = "fixture",
                        role = "consumer",
                        layer = ArchitectureLayer.DOMAIN,
                        filePath = "src/main/kotlin/fixture/Consumer.kt",
                        line = DECLARATION_LINE,
                        isTest = false,
                        sourceFileId = CONSUMER_FILE_ID,
                        symbolId = CONSUMER_SYMBOL_ID,
                    ),
                    ArchitectureComponentSnapshot(
                        id = TARGET_COMPONENT_ID,
                        componentId = "fixture.Target",
                        name = "Target file",
                        packageName = "fixture",
                        packageGroup = "fixture",
                        role = "target",
                        layer = ArchitectureLayer.DOMAIN,
                        filePath = "src/main/kotlin/fixture/Target.kt",
                        line = DECLARATION_LINE,
                        isTest = false,
                        sourceFileId = TARGET_FILE_ID,
                    ),
                ),
            dependencies =
                listOf(
                    ArchitectureDependencySnapshot(
                        id = FORWARD_DEPENDENCY_ID,
                        sourceComponentId = CONSUMER_COMPONENT_ID,
                        targetComponentId = TARGET_COMPONENT_ID,
                    ),
                    ArchitectureDependencySnapshot(
                        id = REVERSE_DEPENDENCY_ID,
                        sourceComponentId = TARGET_COMPONENT_ID,
                        targetComponentId = CONSUMER_COMPONENT_ID,
                    ),
                ),
            cycles =
                listOf(
                    ArchitectureCycleSnapshot(
                        id = ANALYSIS_CYCLE_ID,
                        componentIds = listOf(CONSUMER_COMPONENT_ID, TARGET_COMPONENT_ID, CONSUMER_COMPONENT_ID),
                    ),
                ),
        )

    private fun fileId(index: Int): String = "file:scope:${padded(index)}"

    private fun symbolId(index: Int): String = "symbol:scope:${padded(index)}"

    private fun padded(index: Int): String = index.toString().padStart(ID_WIDTH, '0')

    private companion object {
        const val PROJECT_ID = "project:fixture:app"
        const val MAIN_SOURCE_SET_ID = "source-set:fixture:main"
        const val TEST_SOURCE_SET_ID = "source-set:fixture:test"
        const val MAIN_NODE_COUNT = 64
        const val TEST_NODE_COUNT = 8
        const val TOTAL_NODE_COUNT = MAIN_NODE_COUNT + TEST_NODE_COUNT
        const val ID_WIDTH = 3
        const val MAIN_NODE_PREFIX = "MainNode"
        const val TEST_NODE_PREFIX = "TestNode"
        const val DECLARATION_LINE = 3
        const val CONSUMER_FILE_ID = "file:fixture:consumer"
        const val TARGET_FILE_ID = "file:fixture:target"
        const val CONSUMER_SYMBOL_ID = "symbol:fixture:consumer"
        const val TARGET_SYMBOL_ID = "symbol:fixture:target"
        const val FORWARD_RELATIONSHIP_ID = "relationship:fixture:constructor"
        const val REVERSE_REFERENCE_ID = "reference:fixture:return"
        const val REVERSE_RELATIONSHIP_ID = "relationship:fixture:return"
        const val DIRECT_FINDING_ID = "finding:fixture:boundary"
        const val ANALYSIS_FINDING_ID = "finding:fixture:analysis-component"
        const val OBSERVED_CYCLE_ID = "cycle:fixture:observed"
        const val ANALYSIS_ID = "analysis:fixture"
        const val CONSUMER_COMPONENT_ID = "component:fixture:consumer"
        const val TARGET_COMPONENT_ID = "component:fixture:target-file"
        const val FORWARD_DEPENDENCY_ID = "dependency:fixture:consumer-target"
        const val REVERSE_DEPENDENCY_ID = "dependency:fixture:target-consumer"
        const val ANALYSIS_CYCLE_ID = "cycle:fixture:analysis"
    }
}
