package zone.clanker.docx.web.atlas.hierarchy

import zone.clanker.report.model.WorkspaceGraphFacet
import zone.clanker.report.model.WorkspaceGraphFilter
import zone.clanker.report.model.WorkspaceGraphNodeKind
import zone.clanker.report.model.WorkspaceGraphNodeRole
import zone.clanker.report.model.WorkspaceGraphRelationshipCountFilter
import zone.clanker.report.model.WorkspaceGraphRelationshipDirection
import zone.clanker.report.model.WorkspaceGraphRequest
import zone.clanker.report.model.WorkspaceGraphSearchTarget
import zone.clanker.report.model.WorkspaceGraphSelection
import zone.clanker.report.model.WorkspaceGraphView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkspaceSourceHierarchyFilterTest {
    @Test
    fun matchesTypedSearchFieldsWithoutLeakingAcrossCategories() {
        assertEquals(
            listOf(DETAIL_SERVICE_TYPE_ID),
            searchPrimaryChildren(
                scopeRootId = DETAIL_SERVICE_FILE_ID,
                query = "SERVICE",
                target = WorkspaceGraphSearchTarget.CLASS,
            ),
        )
        assertEquals(
            emptyList(),
            searchPrimaryChildren(
                scopeRootId = DETAIL_SERVICE_FILE_ID,
                query = "run",
                target = WorkspaceGraphSearchTarget.CLASS,
            ),
        )
        assertEquals(
            listOf(DETAIL_STATE_MEMBER_ID),
            searchPrimaryChildren(
                scopeRootId = DETAIL_SERVICE_FILE_ID,
                query = "state",
                target = WorkspaceGraphSearchTarget.SYMBOL,
            ),
        )
        assertEquals(
            listOf(DETAIL_HELPER_MEMBER_ID),
            searchPrimaryChildren(
                scopeRootId = DETAIL_UTIL_FILE_ID,
                query = "helper",
                target = WorkspaceGraphSearchTarget.METHOD,
            ),
        )
    }

    @Test
    fun normalizesFileExtensionsAndSearchesBuildAndProjectOwnershipFields() {
        val featurePackageId = workspaceSourcePackageId(DETAIL_SOURCE_SET_ID, DETAIL_FEATURE_PACKAGE)
        assertEquals(
            listOf(DETAIL_SERVICE_FILE_ID),
            searchPrimaryChildren(
                scopeRootId = featurePackageId,
                query = "*.KT",
                target = WorkspaceGraphSearchTarget.FILE_EXTENSION,
            ),
        )
        assertEquals(
            listOf(workspaceSourcePackageId(DETAIL_SOURCE_SET_ID, "com")),
            searchPrimaryChildren(
                scopeRootId = DETAIL_SOURCE_SET_ID,
                query = "com",
                target = WorkspaceGraphSearchTarget.PACKAGE,
            ),
        )
        assertEquals(
            listOf(DETAIL_SERVICE_FILE_ID),
            searchPrimaryChildren(
                scopeRootId = featurePackageId,
                query = "Service.kt",
                target = WorkspaceGraphSearchTarget.FILE,
            ),
        )
        assertEquals(
            listOf(DETAIL_BUILD_ID),
            searchPrimaryChildren(
                scopeRootId = DETAIL_WORKSPACE_ID,
                query = ".",
                target = WorkspaceGraphSearchTarget.BUILD,
                loadProject = false,
            ),
        )
        assertEquals(
            listOf(DETAIL_PROJECT_ID),
            searchPrimaryChildren(
                scopeRootId = DETAIL_BUILD_ID,
                query = "build.gradle.kts",
                target = WorkspaceGraphSearchTarget.PROJECT,
                loadProject = false,
            ),
        )
    }

    @Test
    fun appliesStrictExactFactCountsAndKeepsInverseEndpointClosureByDefault() {
        val slice = relationshipSlice(moreThan = 2, WorkspaceGraphRelationshipDirection.OUTGOING)
        val nodesById = slice.content.nodes.associateBy { node -> node.id }

        assertEquals(listOf(DETAIL_RUN_MEMBER_ID), slice.primaryMemberIds())
        assertTrue(WorkspaceGraphNodeRole.ENDPOINT in nodesById.getValue(DETAIL_HELPER_MEMBER_ID).roles)
        assertTrue(WorkspaceGraphNodeRole.ENDPOINT in nodesById.getValue(DETAIL_STATE_MEMBER_ID).roles)
        assertEquals(THREE_RELATION_ROUTES, slice.content.relations.size)
        assertTrue(
            slice.content.relations.any { relation ->
                relation.endpoints.sourceNodeId == DETAIL_STATE_MEMBER_ID &&
                    relation.endpoints.targetNodeId == DETAIL_RUN_MEMBER_ID
            },
        )
        assertEquals(
            DOUBLE_CALL_FACT_COUNT,
            slice.content.relations
                .single { relation -> relation.endpoints.targetNodeId == DETAIL_HELPER_MEMBER_ID }
                .facts.factCount,
        )

        val anyDirection = relationshipSlice(moreThan = 3, WorkspaceGraphRelationshipDirection.ANY)
        assertEquals(listOf(DETAIL_RUN_MEMBER_ID), anyDirection.primaryMemberIds())

        val strictBoundary = relationshipSlice(moreThan = 4, WorkspaceGraphRelationshipDirection.ANY)
        assertTrue(strictBoundary.primaryMemberIds().isEmpty())
        assertTrue(strictBoundary.content.relations.isEmpty())
    }

    @Test
    fun separatesIncomingCountsAndCanRemoveInverseContextWithoutBreakingClosure() {
        val incoming = relationshipSlice(moreThan = 1, WorkspaceGraphRelationshipDirection.INCOMING)
        assertEquals(listOf(DETAIL_HELPER_MEMBER_ID), incoming.primaryMemberIds())
        assertEquals(1, incoming.content.relations.size)
        assertEquals(
            DOUBLE_CALL_FACT_COUNT,
            incoming.content.relations
                .single()
                .facts.factCount,
        )
        assertTrue(
            WorkspaceGraphNodeRole.ENDPOINT in
                incoming.content.nodes
                    .single { node -> node.id == DETAIL_RUN_MEMBER_ID }
                    .roles,
        )

        val outgoingOnly =
            relationshipSlice(
                moreThan = 2,
                direction = WorkspaceGraphRelationshipDirection.OUTGOING,
                keepInverseContext = false,
            )
        assertEquals(TWO_OUTGOING_ROUTES, outgoingOnly.content.relations.size)
        assertFalse(
            outgoingOnly.content.relations.any { relation ->
                relation.endpoints.sourceNodeId == DETAIL_STATE_MEMBER_ID &&
                    relation.endpoints.targetNodeId == DETAIL_RUN_MEMBER_ID
            },
        )
        val endpointIds =
            outgoingOnly.content.relations
                .flatMap { relation ->
                    listOf(relation.endpoints.sourceNodeId, relation.endpoints.targetNodeId)
                }.toSet()
        assertTrue(endpointIds.all { endpointId -> outgoingOnly.content.nodes.any { node -> node.id == endpointId } })
    }

    private fun searchPrimaryChildren(
        scopeRootId: String,
        query: String,
        target: WorkspaceGraphSearchTarget,
        loadProject: Boolean = true,
    ): List<String> {
        val request =
            sourceRequest(
                scopeRootIds = listOf(scopeRootId),
                filter = WorkspaceGraphFilter(query = query, searchTargets = listOf(target)),
            )
        val loadedProjects = if (loadProject) listOf(sourceDetailShard()) else emptyList()
        return workspaceSourceHierarchySlice(sourceDetailSummary(), GENERATION_ID, request, loadedProjects)
            .content.nodes
            .filter { node -> WorkspaceGraphNodeRole.PRIMARY in node.roles && node.id != scopeRootId }
            .map { node -> node.id }
            .sorted()
    }

    private fun relationshipSlice(
        moreThan: Long,
        direction: WorkspaceGraphRelationshipDirection,
        keepInverseContext: Boolean = true,
    ) =
        workspaceSourceHierarchySlice(
            summary = sourceDetailSummary(),
            generationId = GENERATION_ID,
            request =
                sourceRequest(
                    scopeRootIds = listOf(DETAIL_SOURCE_SET_ID),
                    expandedNodeIds = detailExpansionIds(),
                    filter =
                        WorkspaceGraphFilter(
                            nodeKinds = listOf(WorkspaceGraphNodeKind.MEMBER),
                            relationshipCountFilter =
                                WorkspaceGraphRelationshipCountFilter(
                                    moreThan = moreThan,
                                    direction = direction,
                                    keepInverseContext = keepInverseContext,
                                ),
                        ),
                ),
            loadedProjects = listOf(relationshipFilterShard()),
        )

    private fun sourceRequest(
        scopeRootIds: List<String>,
        expandedNodeIds: List<String> = emptyList(),
        filter: WorkspaceGraphFilter,
    ): WorkspaceGraphRequest =
        WorkspaceGraphRequest(
            workspaceId = DETAIL_WORKSPACE_ID,
            generationId = GENERATION_ID,
            facet = WorkspaceGraphFacet.SOURCE,
            view =
                WorkspaceGraphView(
                    selection =
                        WorkspaceGraphSelection(
                            scopeRootIds = scopeRootIds,
                            expandedNodeIds = expandedNodeIds,
                        ),
                    filter = filter,
                ),
        )

    private fun detailExpansionIds(): List<String> =
        listOf(
            workspaceSourcePackageId(DETAIL_SOURCE_SET_ID, "com"),
            workspaceSourcePackageId(DETAIL_SOURCE_SET_ID, "com.acme"),
            workspaceSourcePackageId(DETAIL_SOURCE_SET_ID, DETAIL_FEATURE_PACKAGE),
            workspaceSourcePackageId(DETAIL_SOURCE_SET_ID, DETAIL_SHARED_PACKAGE),
            DETAIL_SERVICE_FILE_ID,
            DETAIL_UTIL_FILE_ID,
        ).sorted()

    private companion object {
        const val GENERATION_ID: String = "generation:source-filter"
        const val THREE_RELATION_ROUTES: Int = 3
        const val TWO_OUTGOING_ROUTES: Int = 2
        const val DOUBLE_CALL_FACT_COUNT: Long = 2
    }
}

private fun zone.clanker.report.model.WorkspaceGraphSlice.primaryMemberIds(): List<String> =
    content.nodes
        .filter { node -> node.kind == WorkspaceGraphNodeKind.MEMBER && WorkspaceGraphNodeRole.PRIMARY in node.roles }
        .map { node -> node.id }
        .sorted()
