package zone.clanker.docx.web.atlas.hierarchy

import zone.clanker.report.model.WorkspaceGraphAvailabilityReason
import zone.clanker.report.model.WorkspaceGraphAvailabilityState
import zone.clanker.report.model.WorkspaceGraphFacet
import zone.clanker.report.model.WorkspaceGraphFactKind
import zone.clanker.report.model.WorkspaceGraphFilter
import zone.clanker.report.model.WorkspaceGraphLimits
import zone.clanker.report.model.WorkspaceGraphNodeKind
import zone.clanker.report.model.WorkspaceGraphRelationKind
import zone.clanker.report.model.WorkspaceGraphRequest
import zone.clanker.report.model.WorkspaceGraphSelection
import zone.clanker.report.model.WorkspaceGraphView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkspaceSourceDetailHierarchyTest {
    @Test
    fun projectsPackagePrefixesFilesTypesAndFileOwnedMembersWithExactCounts() {
        val summary = sourceDetailSummary()
        val shard = sourceDetailShard()
        val packageIds = detailPackageIds()
        val expandedIds = (packageIds + listOf(DETAIL_SERVICE_FILE_ID, DETAIL_UTIL_FILE_ID)).sorted()
        val request = detailRequest(expandedIds, focusNodeIds = listOf(DETAIL_HELPER_MEMBER_ID))

        val slice = workspaceSourceHierarchySlice(summary, GENERATION_ID, request, listOf(shard))
        val nodesById = slice.content.nodes.associateBy { node -> node.id }
        val workspace = nodesById.getValue(DETAIL_WORKSPACE_ID)
        val sourceSet = nodesById.getValue(DETAIL_SOURCE_SET_ID)
        val rootPackage = nodesById.getValue(workspaceSourcePackageId(DETAIL_SOURCE_SET_ID, "com"))
        val parentPackage = nodesById.getValue(workspaceSourcePackageId(DETAIL_SOURCE_SET_ID, "com.acme"))
        val featurePackage =
            nodesById.getValue(workspaceSourcePackageId(DETAIL_SOURCE_SET_ID, DETAIL_FEATURE_PACKAGE))
        val unknownPackage = nodesById.getValue(workspaceUnknownSourcePackageId(DETAIL_SOURCE_SET_ID))
        val serviceFile = nodesById.getValue(DETAIL_SERVICE_FILE_ID)

        assertEquals(DETAIL_NODE_COUNT, slice.content.nodes.size)
        assertEquals(DETAIL_PRIMARY_NODE_COUNT, slice.counts.matchingPrimaryNodeCount)
        assertEquals(DETAIL_DESCENDANT_COUNT, workspace.hierarchy.descendantCount)
        assertEquals(SOURCE_SET_DIRECT_CHILD_COUNT, sourceSet.hierarchy.directChildCount)
        assertEquals(SOURCE_SET_DESCENDANT_COUNT, sourceSet.hierarchy.descendantCount)
        assertEquals(1L, rootPackage.hierarchy.directChildCount)
        assertEquals(ROOT_PACKAGE_DESCENDANT_COUNT, rootPackage.hierarchy.descendantCount)
        assertEquals(2L, parentPackage.hierarchy.directChildCount)
        assertEquals(PARENT_PACKAGE_DESCENDANT_COUNT, parentPackage.hierarchy.descendantCount)
        assertEquals(1L, featurePackage.hierarchy.directChildCount)
        assertEquals(FEATURE_PACKAGE_DESCENDANT_COUNT, featurePackage.hierarchy.descendantCount)
        assertEquals("Unknown package", unknownPackage.presentation.label)
        assertEquals(DETAIL_SOURCE_SET_ID, unknownPackage.hierarchy.parentId)
        assertEquals(1L, unknownPackage.hierarchy.directChildCount)
        assertEquals(1L, unknownPackage.hierarchy.descendantCount)
        assertEquals(SERVICE_SYMBOL_COUNT, serviceFile.hierarchy.directChildCount)
        assertEquals(SERVICE_SYMBOL_COUNT, serviceFile.hierarchy.descendantCount)

        assertEquals(WorkspaceGraphNodeKind.TYPE, nodesById.getValue(DETAIL_SERVICE_TYPE_ID).kind)
        assertEquals(DETAIL_SERVICE_FILE_ID, nodesById.getValue(DETAIL_SERVICE_TYPE_ID).hierarchy.parentId)
        assertEquals(WorkspaceGraphNodeKind.MEMBER, nodesById.getValue(DETAIL_RUN_MEMBER_ID).kind)
        assertEquals(DETAIL_SERVICE_FILE_ID, nodesById.getValue(DETAIL_RUN_MEMBER_ID).hierarchy.parentId)
        assertEquals(DETAIL_SERVICE_FILE_ID, nodesById.getValue(DETAIL_STATE_MEMBER_ID).hierarchy.parentId)
        assertEquals(
            workspaceUnknownSourcePackageId(DETAIL_SOURCE_SET_ID),
            nodesById.getValue(DETAIL_CONFIG_FILE_ID).hierarchy.parentId,
        )

        val structureAvailability = slice.availability(WorkspaceGraphFactKind.STRUCTURE)
        val packageAvailability = slice.availability(WorkspaceGraphFactKind.PACKAGES)
        val memberOwnershipAvailability = slice.availability(WorkspaceGraphFactKind.MEMBER_OWNERSHIP)
        assertEquals(DETAIL_NODE_COUNT.toLong(), structureAvailability.observedFactCount)
        assertEquals(WorkspaceGraphAvailabilityState.PARTIAL, packageAvailability.state)
        assertEquals(DETAIL_PACKAGE_COUNT, packageAvailability.observedFactCount)
        assertEquals(WorkspaceGraphAvailabilityReason.LEGACY_SNAPSHOT, packageAvailability.reason)
        assertEquals(WorkspaceGraphAvailabilityState.UNAVAILABLE, memberOwnershipAvailability.state)
        assertEquals(WorkspaceGraphAvailabilityReason.NOT_CAPTURED, memberOwnershipAvailability.reason)
    }

    @Test
    fun rollsOneRelationshipFromPackagesToFilesAndThenExactMembers() {
        val summary = sourceDetailSummary()
        val shard = sourceDetailShard()
        val rootPackageId = workspaceSourcePackageId(DETAIL_SOURCE_SET_ID, "com")
        val parentPackageId = workspaceSourcePackageId(DETAIL_SOURCE_SET_ID, "com.acme")
        val featurePackageId = workspaceSourcePackageId(DETAIL_SOURCE_SET_ID, DETAIL_FEATURE_PACKAGE)
        val sharedPackageId = workspaceSourcePackageId(DETAIL_SOURCE_SET_ID, DETAIL_SHARED_PACKAGE)

        val packageSlice =
            workspaceSourceHierarchySlice(
                summary,
                GENERATION_ID,
                detailRequest(listOf(rootPackageId, parentPackageId).sorted()),
                listOf(shard),
            )
        val fileSlice =
            workspaceSourceHierarchySlice(
                summary,
                GENERATION_ID,
                detailRequest(listOf(rootPackageId, parentPackageId, featurePackageId, sharedPackageId).sorted()),
                listOf(shard),
            )
        val memberExpandedIds =
            listOf(
                rootPackageId,
                parentPackageId,
                featurePackageId,
                sharedPackageId,
                DETAIL_SERVICE_FILE_ID,
                DETAIL_UTIL_FILE_ID,
            ).sorted()
        val memberRequest = detailRequest(memberExpandedIds)
        val memberSlice = workspaceSourceHierarchySlice(summary, GENERATION_ID, memberRequest, listOf(shard))
        val memberRelation = memberSlice.content.relations.single()

        assertEquals(featurePackageId to sharedPackageId, packageSlice.singleRelationEndpoints())
        assertEquals(DETAIL_SERVICE_FILE_ID to DETAIL_UTIL_FILE_ID, fileSlice.singleRelationEndpoints())
        assertEquals(DETAIL_RUN_MEMBER_ID to DETAIL_HELPER_MEMBER_ID, memberSlice.singleRelationEndpoints())
        assertEquals(WorkspaceGraphRelationKind.CALL, memberRelation.kind)
        assertEquals(1L, memberRelation.facts.factCount)
        assertEquals(listOf(DETAIL_RELATIONSHIP_ID), memberRelation.facts.sampleFactIds)
        assertEquals(
            memberSlice,
            workspaceSourceHierarchySlice(summary, GENERATION_ID, memberRequest, listOf(shard)),
        )
    }

    @Test
    fun keepsFullPackageEndpointsForPrefixAnchorsAndFileToSymbolAsymmetryForImports() {
        val summary = sourceDetailSummary()
        val shard = sourceDetailImportShard()
        val rootPackageId = workspaceSourcePackageId(DETAIL_SOURCE_SET_ID, "com")
        val parentPackageId = workspaceSourcePackageId(DETAIL_SOURCE_SET_ID, "com.acme")
        val featurePackageId = workspaceSourcePackageId(DETAIL_SOURCE_SET_ID, DETAIL_FEATURE_PACKAGE)
        val sharedPackageId = workspaceSourcePackageId(DETAIL_SOURCE_SET_ID, DETAIL_SHARED_PACKAGE)
        val packageSlice =
            workspaceSourceHierarchySlice(
                summary,
                GENERATION_ID,
                detailRequest(listOf(rootPackageId, parentPackageId).sorted()),
                listOf(shard),
            )
        val symbolSlice =
            workspaceSourceHierarchySlice(
                summary,
                GENERATION_ID,
                detailRequest(
                    expandedNodeIds =
                        listOf(
                            rootPackageId,
                            parentPackageId,
                            featurePackageId,
                            sharedPackageId,
                            DETAIL_SERVICE_FILE_ID,
                            DETAIL_UTIL_FILE_ID,
                        ).sorted(),
                    focusNodeIds = listOf(DETAIL_HELPER_MEMBER_ID),
                ),
                listOf(shard),
            )

        assertEquals(featurePackageId to sharedPackageId, packageSlice.singleRelationEndpoints())
        assertEquals(DETAIL_SERVICE_FILE_ID to DETAIL_HELPER_MEMBER_ID, symbolSlice.singleRelationEndpoints())
    }

    @Test
    fun boundsLoadedMembersWithoutUnderstatingTheirFileTotals() {
        val summary = boundedMemberSummary()
        val shard = boundedMemberShard()
        val packageIds =
            listOf(
                workspaceSourcePackageId(BOUNDED_SOURCE_SET_ID, "fixture"),
                workspaceSourcePackageId(BOUNDED_SOURCE_SET_ID, BOUNDED_PACKAGE),
            )
        val request =
            WorkspaceGraphRequest(
                workspaceId = BOUNDED_WORKSPACE_ID,
                generationId = GENERATION_ID,
                facet = WorkspaceGraphFacet.SOURCE,
                view =
                    WorkspaceGraphView(
                        selection =
                            WorkspaceGraphSelection(
                                scopeRootIds = listOf(BOUNDED_SOURCE_SET_ID),
                                expandedNodeIds = (packageIds + BOUNDED_FILE_ID).sorted(),
                            ),
                    ),
            )

        val slice = workspaceSourceHierarchySlice(summary, GENERATION_ID, request, listOf(shard))
        val file = slice.content.nodes.single { node -> node.id == BOUNDED_FILE_ID }

        assertEquals(WorkspaceGraphLimits.DEFAULT_NODE_LIMIT, slice.content.nodes.size)
        assertEquals(BOUNDED_PRIMARY_NODE_COUNT, slice.counts.matchingPrimaryNodeCount)
        assertEquals(BOUNDED_MEMBER_COUNT.toLong(), file.hierarchy.directChildCount)
        assertEquals(BOUNDED_MEMBER_COUNT.toLong(), file.hierarchy.descendantCount)
        assertTrue(
            slice.content.nodes.count { node -> node.kind == WorkspaceGraphNodeKind.MEMBER } <
                BOUNDED_MEMBER_COUNT,
        )
    }

    @Test
    fun filtersLoadedFileChildrenByMemberKindAndQuery() {
        val summary = sourceDetailSummary()
        val shard = sourceDetailShard()
        val request =
            WorkspaceGraphRequest(
                workspaceId = DETAIL_WORKSPACE_ID,
                generationId = GENERATION_ID,
                facet = WorkspaceGraphFacet.SOURCE,
                view =
                    WorkspaceGraphView(
                        selection = WorkspaceGraphSelection(scopeRootIds = listOf(DETAIL_UTIL_FILE_ID)),
                        filter =
                            WorkspaceGraphFilter(
                                nodeKinds = listOf(WorkspaceGraphNodeKind.MEMBER),
                                relationKinds = listOf(WorkspaceGraphRelationKind.EXTENDS),
                                query = "HELPER",
                            ),
                    ),
            )

        val slice = workspaceSourceHierarchySlice(summary, GENERATION_ID, request, listOf(shard))

        assertEquals(FILTERED_MEMBER_NODE_COUNT, slice.content.nodes.size)
        assertEquals(FILTERED_MEMBER_PRIMARY_COUNT, slice.counts.matchingPrimaryNodeCount)
        assertTrue(slice.content.nodes.any { node -> node.id == DETAIL_HELPER_MEMBER_ID })
        assertTrue(slice.content.nodes.none { node -> node.id == DETAIL_UTIL_TYPE_ID })
        assertTrue(slice.content.relations.isEmpty())
    }

    private fun detailRequest(
        expandedNodeIds: List<String>,
        focusNodeIds: List<String> = emptyList(),
    ): WorkspaceGraphRequest =
        WorkspaceGraphRequest(
            workspaceId = DETAIL_WORKSPACE_ID,
            generationId = GENERATION_ID,
            facet = WorkspaceGraphFacet.SOURCE,
            view =
                WorkspaceGraphView(
                    selection =
                        WorkspaceGraphSelection(
                            scopeRootIds = listOf(DETAIL_SOURCE_SET_ID),
                            expandedNodeIds = expandedNodeIds,
                            focusNodeIds = focusNodeIds,
                        ),
                ),
        )

    private fun detailPackageIds(): List<String> =
        listOf(
            workspaceSourcePackageId(DETAIL_SOURCE_SET_ID, "com"),
            workspaceSourcePackageId(DETAIL_SOURCE_SET_ID, "com.acme"),
            workspaceSourcePackageId(DETAIL_SOURCE_SET_ID, DETAIL_FEATURE_PACKAGE),
            workspaceSourcePackageId(DETAIL_SOURCE_SET_ID, DETAIL_SHARED_PACKAGE),
            workspaceUnknownSourcePackageId(DETAIL_SOURCE_SET_ID),
        )

    private companion object {
        const val GENERATION_ID: String = "generation:source-detail"
        const val DETAIL_NODE_COUNT: Int = 17
        const val DETAIL_PRIMARY_NODE_COUNT: Long = 14
        const val DETAIL_DESCENDANT_COUNT: Long = 16
        const val SOURCE_SET_DIRECT_CHILD_COUNT: Long = 2
        const val SOURCE_SET_DESCENDANT_COUNT: Long = 13
        const val ROOT_PACKAGE_DESCENDANT_COUNT: Long = 10
        const val PARENT_PACKAGE_DESCENDANT_COUNT: Long = 9
        const val FEATURE_PACKAGE_DESCENDANT_COUNT: Long = 4
        const val SERVICE_SYMBOL_COUNT: Long = 3
        const val DETAIL_PACKAGE_COUNT: Long = 4
        const val BOUNDED_PRIMARY_NODE_COUNT: Long = 504
        const val FILTERED_MEMBER_NODE_COUNT: Int = 9
        const val FILTERED_MEMBER_PRIMARY_COUNT: Long = 2
    }
}

private fun zone.clanker.report.model.WorkspaceGraphSlice.availability(fact: WorkspaceGraphFactKind) =
    content.availability.single { availability -> availability.fact == fact }

private fun zone.clanker.report.model.WorkspaceGraphSlice.singleRelationEndpoints(): Pair<String, String> {
    val endpoints = content.relations.single().endpoints
    return endpoints.sourceNodeId to endpoints.targetNodeId
}
