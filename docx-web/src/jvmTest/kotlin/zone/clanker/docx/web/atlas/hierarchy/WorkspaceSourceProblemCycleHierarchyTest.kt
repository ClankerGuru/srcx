package zone.clanker.docx.web.atlas.hierarchy

import zone.clanker.report.model.WorkspaceGraphAvailabilityReason
import zone.clanker.report.model.WorkspaceGraphAvailabilityState
import zone.clanker.report.model.WorkspaceGraphDeclarationKind
import zone.clanker.report.model.WorkspaceGraphFacet
import zone.clanker.report.model.WorkspaceGraphFactKind
import zone.clanker.report.model.WorkspaceGraphFilter
import zone.clanker.report.model.WorkspaceGraphLimits
import zone.clanker.report.model.WorkspaceGraphNodeKind
import zone.clanker.report.model.WorkspaceGraphNodeRole
import zone.clanker.report.model.WorkspaceGraphRequest
import zone.clanker.report.model.WorkspaceGraphSelection
import zone.clanker.report.model.WorkspaceGraphSlice
import zone.clanker.report.model.WorkspaceGraphView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkspaceSourceProblemCycleHierarchyTest {
    @Test
    fun composesClassesAndProblemsFromExplicitDeclarationEvidence() {
        val slice =
            overlaySlice(
                scopeRootId = DETAIL_SERVICE_FILE_ID,
                filter =
                    WorkspaceGraphFilter(
                        nodeKinds =
                            listOf(
                                WorkspaceGraphNodeKind.PROBLEM,
                                WorkspaceGraphNodeKind.TYPE,
                            ),
                        declarationKinds = listOf(WorkspaceGraphDeclarationKind.CLASS),
                    ),
            )

        assertEquals(
            listOf(DETAIL_CLASS_FINDING_ID, DETAIL_SERVICE_TYPE_ID).sorted(),
            slice.primaryChildrenOf(DETAIL_SERVICE_FILE_ID),
        )
        assertTrue(slice.content.nodes.none { node -> node.id == DETAIL_METHOD_FINDING_ID })
    }

    @Test
    fun composesMethodsAndCyclesAtTheirDeepestCommonAncestor() {
        val slice =
            overlaySlice(
                scopeRootId = DETAIL_SERVICE_FILE_ID,
                filter =
                    WorkspaceGraphFilter(
                        nodeKinds =
                            listOf(
                                WorkspaceGraphNodeKind.CYCLE,
                                WorkspaceGraphNodeKind.MEMBER,
                            ),
                        declarationKinds = listOf(WorkspaceGraphDeclarationKind.METHOD),
                    ),
            )

        assertEquals(
            listOf(DETAIL_CYCLE_ID, DETAIL_RUN_MEMBER_ID).sorted(),
            slice.primaryChildrenOf(DETAIL_SERVICE_FILE_ID),
        )
        assertEquals(
            DETAIL_SERVICE_FILE_ID,
            slice.content.nodes
                .single { node -> node.id == DETAIL_CYCLE_ID }
                .hierarchy.parentId,
        )
        assertTrue(
            WorkspaceGraphNodeRole.PRIMARY !in
                slice.content.nodes
                    .single { node -> node.id == DETAIL_STATE_MEMBER_ID }
                    .roles,
        )
    }

    @Test
    fun placesFileProjectAndSymbolFindingsOnAuthoritativeCapturedOwners() {
        val projectSlice =
            overlaySlice(
                scopeRootId = DETAIL_PROJECT_ID,
                filter = WorkspaceGraphFilter(nodeKinds = listOf(WorkspaceGraphNodeKind.PROBLEM)),
            )
        val fileSlice =
            overlaySlice(
                scopeRootId = DETAIL_CONFIG_FILE_ID,
                filter = WorkspaceGraphFilter(nodeKinds = listOf(WorkspaceGraphNodeKind.PROBLEM)),
            )
        val symbolSlice =
            overlaySlice(
                scopeRootId = DETAIL_RUN_MEMBER_ID,
                filter = WorkspaceGraphFilter(nodeKinds = listOf(WorkspaceGraphNodeKind.PROBLEM)),
            )

        assertEquals(listOf(DETAIL_PROJECT_FINDING_ID), projectSlice.primaryChildrenOf(DETAIL_PROJECT_ID))
        assertEquals(
            DETAIL_PROJECT_ID,
            projectSlice.content.nodes
                .single { node -> node.id == DETAIL_PROJECT_FINDING_ID }
                .hierarchy.parentId,
        )
        assertEquals(listOf(DETAIL_FILE_FINDING_ID), fileSlice.primaryChildrenOf(DETAIL_CONFIG_FILE_ID))
        assertEquals(
            DETAIL_CONFIG_FILE_ID,
            fileSlice.content.nodes
                .single { node -> node.id == DETAIL_FILE_FINDING_ID }
                .hierarchy.parentId,
        )
        assertEquals(listOf(DETAIL_SYMBOL_FINDING_ID), symbolSlice.primaryChildrenOf(DETAIL_RUN_MEMBER_ID))
        assertEquals(
            DETAIL_RUN_MEMBER_ID,
            symbolSlice.content.nodes
                .single { node -> node.id == DETAIL_SYMBOL_FINDING_ID }
                .hierarchy.parentId,
        )
    }

    @Test
    fun reportsCapturedProblemAndCycleAvailabilityWithoutClaimingCompleteness() {
        val filter = WorkspaceGraphFilter(nodeKinds = listOf(WorkspaceGraphNodeKind.PROBLEM))
        val loaded = overlaySlice(DETAIL_SERVICE_FILE_ID, filter)
        val unloaded =
            workspaceSourceHierarchySlice(
                summary = sourceDetailSummary(),
                generationId = GENERATION_ID,
                request = overlayRequest(DETAIL_WORKSPACE_ID, filter),
            )

        assertEquals(
            WorkspaceGraphAvailabilityState.PARTIAL,
            loaded.availability(WorkspaceGraphFactKind.PROBLEMS).state,
        )
        assertEquals(PROBLEM_COUNT, loaded.availability(WorkspaceGraphFactKind.PROBLEMS).observedFactCount)
        assertEquals(
            WorkspaceGraphAvailabilityReason.LEGACY_SNAPSHOT,
            loaded.availability(WorkspaceGraphFactKind.CYCLES).reason,
        )
        assertEquals(CYCLE_COUNT, loaded.availability(WorkspaceGraphFactKind.CYCLES).observedFactCount)
        assertEquals(
            WorkspaceGraphAvailabilityState.UNAVAILABLE,
            unloaded.availability(WorkspaceGraphFactKind.PROBLEMS).state,
        )
        assertEquals(
            WorkspaceGraphAvailabilityReason.NOT_INDEXED,
            unloaded.availability(WorkspaceGraphFactKind.CYCLES).reason,
        )
    }

    @Test
    fun boundsLargeProblemCollectionsWithoutUnderstatingFileTotals() {
        val request =
            overlayRequest(
                scopeRootId = DETAIL_SERVICE_FILE_ID,
                filter = WorkspaceGraphFilter(nodeKinds = listOf(WorkspaceGraphNodeKind.PROBLEM)),
            )
        val slice =
            workspaceSourceHierarchySlice(
                summary = sourceDetailSummary(),
                generationId = GENERATION_ID,
                request = request,
                loadedProjects = listOf(boundedProblemShard()),
            )
        val file = slice.content.nodes.single { node -> node.id == DETAIL_SERVICE_FILE_ID }

        assertEquals(WorkspaceGraphLimits.DEFAULT_NODE_LIMIT, slice.content.nodes.size)
        assertEquals((BOUNDED_PROBLEM_COUNT + 1).toLong(), slice.counts.matchingPrimaryNodeCount)
        assertEquals((BOUNDED_PROBLEM_COUNT + SERVICE_SYMBOL_COUNT).toLong(), file.hierarchy.directChildCount)
        assertEquals(file.hierarchy.directChildCount, file.hierarchy.descendantCount)
        assertTrue(
            slice.content.nodes.count { node -> node.kind == WorkspaceGraphNodeKind.PROBLEM } <
                BOUNDED_PROBLEM_COUNT,
        )
    }

    private fun overlaySlice(
        scopeRootId: String,
        filter: WorkspaceGraphFilter,
    ): WorkspaceGraphSlice =
        workspaceSourceHierarchySlice(
            summary = sourceDetailSummary(),
            generationId = GENERATION_ID,
            request = overlayRequest(scopeRootId, filter),
            loadedProjects = listOf(problemCycleShard()),
        )

    private fun overlayRequest(
        scopeRootId: String,
        filter: WorkspaceGraphFilter,
    ): WorkspaceGraphRequest =
        WorkspaceGraphRequest(
            workspaceId = DETAIL_WORKSPACE_ID,
            generationId = GENERATION_ID,
            facet = WorkspaceGraphFacet.SOURCE,
            view =
                WorkspaceGraphView(
                    selection = WorkspaceGraphSelection(scopeRootIds = listOf(scopeRootId)),
                    filter = filter,
                ),
        )

    private companion object {
        const val GENERATION_ID: String = "generation:problem-cycle"
        const val PROBLEM_COUNT: Long = 5
        const val CYCLE_COUNT: Long = 1
        const val SERVICE_SYMBOL_COUNT: Int = 3
    }
}

private fun WorkspaceGraphSlice.primaryChildrenOf(parentId: String): List<String> =
    content.nodes
        .filter { node -> node.hierarchy.parentId == parentId && WorkspaceGraphNodeRole.PRIMARY in node.roles }
        .map { node -> node.id }
        .sorted()

private fun WorkspaceGraphSlice.availability(fact: WorkspaceGraphFactKind) =
    content.availability.single { availability -> availability.fact == fact }
