package zone.clanker.docx.web.atlas.hierarchy

import zone.clanker.report.model.WorkspaceGraphAvailabilityReason
import zone.clanker.report.model.WorkspaceGraphAvailabilityState
import zone.clanker.report.model.WorkspaceGraphFacet
import zone.clanker.report.model.WorkspaceGraphFactKind
import zone.clanker.report.model.WorkspaceGraphFilter
import zone.clanker.report.model.WorkspaceGraphLimits
import zone.clanker.report.model.WorkspaceGraphNodeKind
import zone.clanker.report.model.WorkspaceGraphNodeRole
import zone.clanker.report.model.WorkspaceGraphRequest
import zone.clanker.report.model.WorkspaceGraphSelection
import zone.clanker.report.model.WorkspaceGraphView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkspaceSourceHierarchyProjectorTest {
    @Test
    fun boundsAnEightyBuildAndTwoThousandFiveHundredSixtyProjectHierarchyDeterministically() {
        val summary = largeSourceHierarchySummary()
        val request =
            WorkspaceGraphRequest(
                workspaceId = LARGE_WORKSPACE_ID,
                generationId = GENERATION_ID,
                facet = WorkspaceGraphFacet.SOURCE,
                view =
                    WorkspaceGraphView(
                        selection =
                            WorkspaceGraphSelection(
                                expandedNodeIds = summary.builds.map { build -> build.id },
                            ),
                    ),
            )

        val first = workspaceSourceHierarchySlice(summary, GENERATION_ID, request)
        val second = workspaceSourceHierarchySlice(summary, GENERATION_ID, request)
        val workspace = first.content.nodes.single { node -> node.id == LARGE_WORKSPACE_ID }

        assertEquals(first, second)
        assertEquals(WorkspaceGraphLimits.DEFAULT_NODE_LIMIT, first.limits.nodeLimit)
        assertEquals(WorkspaceGraphLimits.DEFAULT_RELATION_LIMIT, first.limits.relationLimit)
        assertEquals(WorkspaceGraphLimits.DEFAULT_NODE_LIMIT, first.content.nodes.size)
        assertEquals(WorkspaceGraphLimits.DEFAULT_RELATION_LIMIT, first.content.relations.size)
        assertEquals(1L + LARGE_BUILD_COUNT + LARGE_PROJECT_COUNT, first.counts.matchingPrimaryNodeCount)
        assertEquals(LARGE_BUILD_EDGE_COUNT.toLong(), first.counts.matchingRelationCount)
        assertEquals(LARGE_BUILD_COUNT.toLong(), workspace.hierarchy.directChildCount)
        assertEquals(
            (LARGE_BUILD_COUNT + LARGE_PROJECT_COUNT * 2).toLong(),
            workspace.hierarchy.descendantCount,
        )
        assertEquals(
            first.content.nodes
                .map { node -> node.id }
                .sorted(),
            first.content.nodes.map { node -> node.id },
        )
        assertEquals(
            first.content.relations
                .map { relation -> relation.id }
                .sorted(),
            first.content.relations.map { relation -> relation.id },
        )
        assertTrue(
            first.content.relations.all { relation ->
                relation.facts.sampleFactIds.size <= WorkspaceGraphLimits.DEFAULT_EVIDENCE_PER_RELATION_LIMIT
            },
        )
        assertTrue(first.content.nodes.all { node -> WorkspaceGraphNodeRole.PRIMARY in node.roles })

        val buildAvailability =
            first.content.availability.single { availability ->
                availability.fact == WorkspaceGraphFactKind.BUILD_DEPENDENCIES
            }
        assertEquals(WorkspaceGraphAvailabilityState.COMPLETE, buildAvailability.state)
        assertEquals(LARGE_BUILD_EDGE_COUNT.toLong(), buildAvailability.observedFactCount)
    }

    @Test
    fun supportsMultiRootScopeTypedQueryFocusAndSourceSetExpansion() {
        val summary = largeSourceHierarchySummary()
        val firstProjectId = largeProjectId(0, 0)
        val secondProjectId = largeProjectId(1, 0)
        val firstSourceSetId = largeSourceSetId(0, 0)
        val secondSourceSetId = largeSourceSetId(1, 0)
        val scopeRootIds = listOf(firstProjectId, secondProjectId)
        val request =
            WorkspaceGraphRequest(
                workspaceId = LARGE_WORKSPACE_ID,
                generationId = GENERATION_ID,
                facet = WorkspaceGraphFacet.SOURCE,
                view =
                    WorkspaceGraphView(
                        selection =
                            WorkspaceGraphSelection(
                                scopeRootIds = scopeRootIds,
                                focusNodeIds = listOf(firstSourceSetId),
                            ),
                        filter =
                            WorkspaceGraphFilter(
                                nodeKinds = listOf(WorkspaceGraphNodeKind.SOURCE_SET),
                                query = "MAIN",
                            ),
                    ),
            )

        val slice = workspaceSourceHierarchySlice(summary, GENERATION_ID, request)
        val nodesById = slice.content.nodes.associateBy { node -> node.id }

        assertEquals(scopeRootIds, slice.viewport.scopeRootIds)
        assertEquals(MULTI_ROOT_NODE_COUNT, slice.content.nodes.size)
        assertEquals(MULTI_ROOT_PRIMARY_COUNT, slice.counts.matchingPrimaryNodeCount)
        assertTrue(firstSourceSetId in nodesById)
        assertTrue(secondSourceSetId in nodesById)
        assertEquals(
            listOf(WorkspaceGraphNodeRole.FOCUS, WorkspaceGraphNodeRole.PRIMARY),
            nodesById.getValue(firstSourceSetId).roles,
        )
        assertEquals(WorkspaceGraphNodeKind.SOURCE_SET, nodesById.getValue(secondSourceSetId).kind)
        assertEquals(0L, slice.counts.matchingRelationCount)
    }

    @Test
    fun retainsExternalEndpointClosureAndOnlyBoundedRelationshipEvidence() {
        val summary = externalEndpointSummary()
        val request =
            WorkspaceGraphRequest(
                workspaceId = EXTERNAL_WORKSPACE_ID,
                generationId = GENERATION_ID,
                facet = WorkspaceGraphFacet.SOURCE,
                view =
                    WorkspaceGraphView(
                        selection = WorkspaceGraphSelection(scopeRootIds = listOf(EXTERNAL_ALPHA_PROJECT_ID)),
                    ),
            )

        val slice =
            workspaceSourceHierarchySlice(
                summary = summary,
                generationId = GENERATION_ID,
                request = request,
                loadedProjects = listOf(externalEndpointShard()),
            )
        val nodesById = slice.content.nodes.associateBy { node -> node.id }
        val relation = slice.content.relations.single()

        assertEquals(EXTERNAL_ALPHA_SOURCE_SET_ID, relation.endpoints.sourceNodeId)
        assertEquals(EXTERNAL_BETA_SOURCE_SET_ID, relation.endpoints.targetNodeId)
        assertEquals(EXTERNAL_RELATIONSHIP_COUNT.toLong(), relation.facts.factCount)
        assertEquals(
            (0 until WorkspaceGraphLimits.DEFAULT_EVIDENCE_PER_RELATION_LIMIT).map(::externalRelationshipId),
            relation.facts.sampleFactIds,
        )
        assertEquals(2L, relation.facts.omittedFactCount)
        assertEquals(listOf(WorkspaceGraphNodeRole.ENDPOINT), nodesById.getValue(EXTERNAL_BETA_SOURCE_SET_ID).roles)
        assertEquals(listOf(WorkspaceGraphNodeRole.ANCESTOR), nodesById.getValue(EXTERNAL_BETA_PROJECT_ID).roles)
        assertTrue(
            nodesById.getValue(EXTERNAL_ALPHA_SOURCE_SET_ID).roles.containsAll(
                listOf(WorkspaceGraphNodeRole.ENDPOINT, WorkspaceGraphNodeRole.PRIMARY),
            ),
        )
        assertEquals(2L, slice.counts.matchingPrimaryNodeCount)
        assertEquals(1L, slice.counts.matchingRelationCount)

        val codeAvailability =
            slice.content.availability.single { availability ->
                availability.fact == WorkspaceGraphFactKind.CODE_RELATIONSHIPS
            }
        val packageAvailability =
            slice.content.availability.single { availability ->
                availability.fact == WorkspaceGraphFactKind.PACKAGES
            }
        assertEquals(WorkspaceGraphAvailabilityState.PARTIAL, codeAvailability.state)
        assertEquals(WorkspaceGraphAvailabilityReason.LEGACY_SNAPSHOT, codeAvailability.reason)
        assertEquals(EXTERNAL_RELATIONSHIP_COUNT.toLong(), codeAvailability.observedFactCount)
        assertEquals(WorkspaceGraphAvailabilityState.PARTIAL, packageAvailability.state)
        assertEquals(WorkspaceGraphAvailabilityReason.LEGACY_SNAPSHOT, packageAvailability.reason)
        assertEquals(2L, packageAvailability.observedFactCount)
    }

    private companion object {
        const val GENERATION_ID: String = "generation:test"
        const val MULTI_ROOT_NODE_COUNT: Int = 7
        const val MULTI_ROOT_PRIMARY_COUNT: Long = 4
    }
}
