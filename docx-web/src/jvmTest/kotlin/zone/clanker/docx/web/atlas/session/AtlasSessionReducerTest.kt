package zone.clanker.docx.web.atlas.session

import zone.clanker.report.model.WorkspaceGraphAvailabilityState
import zone.clanker.report.model.WorkspaceGraphDeclarationKind
import zone.clanker.report.model.WorkspaceGraphFacet
import zone.clanker.report.model.WorkspaceGraphFactAvailability
import zone.clanker.report.model.WorkspaceGraphFactKind
import zone.clanker.report.model.WorkspaceGraphNode
import zone.clanker.report.model.WorkspaceGraphNodeHierarchy
import zone.clanker.report.model.WorkspaceGraphNodeKind
import zone.clanker.report.model.WorkspaceGraphNodePresentation
import zone.clanker.report.model.WorkspaceGraphNodeRole
import zone.clanker.report.model.WorkspaceGraphRelationKind
import zone.clanker.report.model.WorkspaceGraphRelationshipDirection
import zone.clanker.report.model.WorkspaceGraphSlice
import zone.clanker.report.model.WorkspaceGraphSliceContent
import zone.clanker.report.model.WorkspaceGraphSliceCounts
import zone.clanker.report.model.WorkspaceGraphSliceTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AtlasSessionReducerTest {
    @Test
    fun representsAllWithEmptySelectionsAndRejectsASyntheticAllId() {
        val session = atlasSession()

        assertEquals(emptyList(), session.scope.levels)
        assertEquals(emptyList(), session.scope.selectedIds(AtlasHierarchyLevel.BUILD))
        assertFailsWith<IllegalArgumentException> { AtlasSemanticId("all") }
    }

    @Test
    fun changingAnAncestorPrunesImpossibleDescendantsAtomicallyAndIsUndoable() {
        val initial =
            atlasSession().copy(
                camera = AtlasCamera(position = AtlasPoint(11.0, 7.0), scale = 2.0),
                focus = AtlasFocus(listOf(GROUP_ID, BUILD_ONE_ID, PROJECT_ONE_ID)),
                inspection = AtlasInspection(primary = nodeTarget(PROJECT_ONE_ID)),
            )
        val beforeAncestorChange =
            listOf(
                AtlasScopeSelectionReplaced(AtlasHierarchyLevel.BUILD, listOf(BUILD_ONE_ID)),
                AtlasScopeSelectionReplaced(AtlasHierarchyLevel.PROJECT, listOf(PROJECT_ONE_ID)),
                AtlasScopeSelectionReplaced(AtlasHierarchyLevel.SOURCE_SET, listOf(SOURCE_ONE_ID)),
            ).reduceAtlasSession(initial)

        val changed =
            reduceAtlasSession(
                beforeAncestorChange,
                AtlasScopeSelectionReplaced(AtlasHierarchyLevel.BUILD, listOf(BUILD_TWO_ID)),
            )

        assertEquals(listOf(BUILD_TWO_ID), changed.scope.selectedIds(AtlasHierarchyLevel.BUILD))
        assertEquals(emptyList(), changed.scope.selectedIds(AtlasHierarchyLevel.PROJECT))
        assertEquals(emptyList(), changed.scope.selectedIds(AtlasHierarchyLevel.SOURCE_SET))
        assertEquals(beforeAncestorChange.camera, changed.camera)
        assertEquals(beforeAncestorChange.focus, changed.focus)
        assertEquals(beforeAncestorChange.inspection, changed.inspection)

        val restored = reduceAtlasSession(changed, AtlasNavigateBack)
        assertEquals(beforeAncestorChange.navigationSnapshot(), restored.navigationSnapshot())
        assertEquals(
            changed.navigationSnapshot(),
            reduceAtlasSession(restored, AtlasNavigateForward).navigationSnapshot(),
        )
    }

    @Test
    fun clearingAnAncestorMeansAllAndPreservesStillPossibleDescendants() {
        val selected =
            listOf(
                AtlasScopeSelectionReplaced(AtlasHierarchyLevel.BUILD, listOf(BUILD_ONE_ID)),
                AtlasScopeSelectionReplaced(AtlasHierarchyLevel.PROJECT, listOf(PROJECT_ONE_ID)),
                AtlasScopeSelectionReplaced(AtlasHierarchyLevel.SOURCE_SET, listOf(SOURCE_ONE_ID)),
            ).reduceAtlasSession(atlasSession())

        val cleared =
            reduceAtlasSession(
                selected,
                AtlasScopeSelectionReplaced(AtlasHierarchyLevel.BUILD, emptyList()),
            )

        assertEquals(emptyList(), cleared.scope.selectedIds(AtlasHierarchyLevel.BUILD))
        assertEquals(listOf(PROJECT_ONE_ID), cleared.scope.selectedIds(AtlasHierarchyLevel.PROJECT))
        assertEquals(listOf(SOURCE_ONE_ID), cleared.scope.selectedIds(AtlasHierarchyLevel.SOURCE_SET))
    }

    @Test
    fun ignoresUnknownOrWrongLevelScopeSelectionsWithoutBroadeningScope() {
        val selected =
            reduceAtlasSession(
                atlasSession(),
                AtlasScopeSelectionReplaced(AtlasHierarchyLevel.BUILD, listOf(BUILD_ONE_ID)),
            )

        val wrongLevel =
            reduceAtlasSession(
                selected,
                AtlasScopeSelectionReplaced(AtlasHierarchyLevel.PROJECT, listOf(BUILD_TWO_ID)),
            )

        assertSame(selected, wrongLevel)
    }

    @Test
    fun inspectionEvidenceAndFocusNeverMoveTheCameraOrChangeScope() {
        val selected =
            reduceAtlasSession(
                atlasSession().copy(camera = AtlasCamera(scale = 3.0)),
                AtlasScopeSelectionReplaced(AtlasHierarchyLevel.BUILD, listOf(BUILD_ONE_ID)),
            )
        val focused =
            reduceAtlasSession(
                selected,
                AtlasFocusChanged(AtlasFocus(listOf(GROUP_ID, BUILD_ONE_ID, PROJECT_ONE_ID))),
            )
        val target = AtlasInspectionTarget(AtlasInspectionKind.RELATION, RELATION_ID)
        val inspected = reduceAtlasSession(focused, AtlasPrimaryInspectionChanged(target))
        val withEvidence = reduceAtlasSession(inspected, AtlasEvidenceChanged(AtlasEvidenceSelection(target, FACT_ID)))

        assertEquals(selected.camera, withEvidence.camera)
        assertEquals(selected.scope, withEvidence.scope)
        assertEquals(focused.focus, withEvidence.focus)
        assertEquals(target, withEvidence.inspection.primary)
        assertEquals(FACT_ID, withEvidence.inspection.evidence?.occurrenceId)
    }

    @Test
    fun composesLayersAndTypedFiltersWithoutChangingNavigationDomains() {
        val initial = atlasSession().copy(camera = AtlasCamera(scale = 1.5))
        val layers = listOf(AtlasLayer.FILES, AtlasLayer.PROBLEMS, AtlasLayer.RELATIONSHIPS)
        val filters =
            AtlasFilters(
                search = AtlasSearchFilter("Repository", listOf(AtlasSearchTarget.CLASS)),
                declarations = listOf(AtlasDeclarationKind.CLASS),
                nodeKinds = listOf(WorkspaceGraphNodeKind.TYPE),
                relationships =
                    AtlasRelationshipFilter(
                        kinds = listOf(WorkspaceGraphRelationKind.CALL),
                        direction = AtlasRelationshipDirection.INCOMING,
                        minimumCountExclusive = 5,
                    ),
            )

        val updated =
            listOf(
                AtlasLayersChanged(layers),
                AtlasFiltersChanged(filters),
            ).reduceAtlasSession(initial)

        assertEquals(layers, updated.layers.enabled)
        assertEquals(filters, updated.filters)
        assertEquals(initial.camera, updated.camera)
        assertEquals(initial.scope, updated.scope)
        assertEquals(initial.focus, updated.focus)
        assertTrue(updated.history.canNavigateBack)
    }

    @Test
    fun ignoresObsoleteRequestResponsesAndSettlesOnlyTheCurrentToken() {
        val initial = atlasSession()
        val firstPending =
            reduceAtlasSession(
                initial,
                AtlasRequestStarted(initial.workspaceGraphRequest(WorkspaceGraphFacet.SOURCE)),
            )
        val firstToken = assertIs<AtlasRequestState.Pending>(firstPending.request).token
        val secondPending =
            reduceAtlasSession(
                firstPending,
                AtlasRequestStarted(firstPending.workspaceGraphRequest(WorkspaceGraphFacet.TASKS)),
            )
        val secondToken = assertIs<AtlasRequestState.Pending>(secondPending.request).token

        val afterStaleFailure = reduceAtlasSession(secondPending, AtlasRequestFailed(firstToken, "obsolete"))
        val afterStaleSuccess =
            reduceAtlasSession(
                afterStaleFailure,
                AtlasRequestSucceeded(firstToken, graphSlice(firstToken, WorkspaceGraphFacet.SOURCE)),
            )

        assertSame(secondPending, afterStaleFailure)
        assertSame(secondPending, afterStaleSuccess)
        assertNotEquals(firstToken, secondToken)
        val settled =
            reduceAtlasSession(
                afterStaleSuccess,
                AtlasRequestSucceeded(secondToken, graphSlice(secondToken, WorkspaceGraphFacet.TASKS)),
            )
        assertIs<AtlasRequestState.Settled>(settled.request)
    }

    @Test
    fun changingGenerationInvalidatesRequestsAndDiscardsLaterStaleResponses() {
        val initial = atlasSession()
        val pending =
            reduceAtlasSession(
                initial,
                AtlasRequestStarted(initial.workspaceGraphRequest(WorkspaceGraphFacet.SOURCE)),
            )
        val token = assertIs<AtlasRequestState.Pending>(pending.request).token
        val nextGeneration = AtlasGeneration(WORKSPACE_ID, "generation:two")

        val replaced = reduceAtlasSession(pending, AtlasGenerationChanged(nextGeneration, hierarchy()))
        val afterStaleResponse =
            reduceAtlasSession(
                replaced,
                AtlasRequestSucceeded(token, graphSlice(token, WorkspaceGraphFacet.SOURCE)),
            )

        assertIs<AtlasRequestState.Idle>(replaced.request)
        assertEquals(pending.request.revision + 1, replaced.request.revision)
        assertSame(replaced, afterStaleResponse)
    }

    @Test
    fun checkpointsOneMultiEventFlyToAsOneRestorableHistoryEntry() {
        val initial =
            reduceAtlasSession(
                atlasSession(),
                AtlasScopeSelectionReplaced(AtlasHierarchyLevel.BUILD, listOf(BUILD_ONE_ID)),
            ).copy(history = AtlasHistory())
        val checkpoint = reduceAtlasSession(initial, AtlasNavigationCheckpoint)
        val target = nodeTarget(PROJECT_ONE_ID)
        val flownTo =
            listOf(
                AtlasCameraChanged(
                    AtlasCamera(
                        position = AtlasPoint(40.0, 20.0),
                        scale = 4.0,
                        transition = AtlasCameraTransition.Moving(AtlasCameraMotion.FLY_TO, PROJECT_ONE_ID),
                    ),
                ),
                AtlasFocusChanged(AtlasFocus(listOf(GROUP_ID, BUILD_ONE_ID, PROJECT_ONE_ID)), recordHistory = false),
                AtlasPrimaryInspectionChanged(target, recordHistory = false),
            ).reduceAtlasSession(checkpoint)

        assertEquals(1, flownTo.history.backward.size)
        assertEquals(initial.navigationSnapshot(), reduceAtlasSession(flownTo, AtlasNavigateBack).navigationSnapshot())
    }

    @Test
    fun restoresTheMeasuredCanonicalCameraFromHistoryForARendererRemount() {
        val initial =
            atlasSession().copy(
                camera =
                    AtlasCamera(
                        position = AtlasPoint(125.0, -44.0),
                        scale = 2.25,
                        viewport = AtlasSize(1280.0, 720.0),
                    ),
            )
        val moved =
            reduceAtlasSession(
                initial,
                AtlasCameraChanged(
                    AtlasCamera(
                        position = AtlasPoint(-18.0, 63.0),
                        scale = 0.75,
                        viewport = AtlasSize(1280.0, 720.0),
                    ),
                    recordHistory = true,
                ),
            )

        val restored = reduceAtlasSession(moved, AtlasNavigateBack)

        assertEquals(initial.camera, restored.camera)
        assertTrue(restored.camera.viewport.width > 0.0)
        assertTrue(restored.camera.viewport.height > 0.0)
    }

    @Test
    fun preservesScopeAndTemporarilyRevealsAnUnloadedSearchTargetAsOneUndoableFlyTo() {
        val initial =
            reduceAtlasSession(
                atlasSession(),
                AtlasScopeSelectionReplaced(AtlasHierarchyLevel.BUILD, listOf(BUILD_TWO_ID)),
            ).copy(history = AtlasHistory())
        val unloadedFileId = AtlasSemanticId("file:search-result")
        val target = nodeTarget(unloadedFileId)

        val flownTo = reduceAtlasSession(initial, AtlasFlyToRequested(target, PROJECT_ONE_ID))

        assertSame(initial.scope, flownTo.scope)
        assertEquals(listOf(GROUP_ID, BUILD_ONE_ID, PROJECT_ONE_ID), flownTo.focus.path)
        assertEquals(
            listOf(GROUP_ID, BUILD_ONE_ID, PROJECT_ONE_ID).sortedBy(AtlasSemanticId::value),
            flownTo.focus.automaticExpandedIds,
        )
        assertEquals(target, flownTo.inspection.primary)
        assertEquals(unloadedFileId, flownTo.focus.requestTargetId)
        assertEquals(1, flownTo.history.backward.size)
        assertEquals(
            listOf(unloadedFileId.value, PROJECT_ONE_ID.value).sorted(),
            flownTo
                .workspaceGraphRequest(WorkspaceGraphFacet.SOURCE)
                .view.selection.focusNodeIds,
        )
        assertEquals(initial.navigationSnapshot(), reduceAtlasSession(flownTo, AtlasNavigateBack).navigationSnapshot())
    }

    @Test
    fun ignoresAFlyToWithoutAKnownTargetOrRevealAncestor() {
        val initial = atlasSession()
        val unknownTarget = nodeTarget(AtlasSemanticId("file:unknown"))

        assertSame(initial, reduceAtlasSession(initial, AtlasFlyToRequested(unknownTarget)))
    }

    @Test
    fun modelsCurrentRequestFailureAndCancellationWithoutAcceptingOldTokens() {
        val initial = atlasSession()
        val firstPending =
            reduceAtlasSession(
                initial,
                AtlasRequestStarted(initial.workspaceGraphRequest(WorkspaceGraphFacet.SOURCE)),
            )
        val firstToken = assertIs<AtlasRequestState.Pending>(firstPending.request).token
        val failed = reduceAtlasSession(firstPending, AtlasRequestFailed(firstToken, "index unavailable"))
        assertIs<AtlasRequestState.Failed>(failed.request)

        val secondPending =
            reduceAtlasSession(
                failed,
                AtlasRequestStarted(failed.workspaceGraphRequest(WorkspaceGraphFacet.SOURCE)),
            )
        val secondToken = assertIs<AtlasRequestState.Pending>(secondPending.request).token
        assertSame(secondPending, reduceAtlasSession(secondPending, AtlasRequestCancelled(firstToken)))
        assertIs<AtlasRequestState.Cancelled>(
            reduceAtlasSession(secondPending, AtlasRequestCancelled(secondToken)).request,
        )
    }

    @Test
    fun keepsOverlayGestureHoverAndCalculatedGeometryOutOfNavigationHistory() {
        val initial = atlasSession()
        val target = nodeTarget(BUILD_ONE_ID)
        val calculated = mapOf(BUILD_ONE_ID to AtlasRect(AtlasPoint.ORIGIN, AtlasSize(120.0, 80.0)))
        val updated =
            listOf(
                AtlasOverlayChanged(AtlasOverlay.Scope(AtlasHierarchyLevel.BUILD)),
                AtlasGestureChanged(AtlasGesture.Pan(AtlasPoint.ORIGIN, AtlasPoint(5.0, 7.0))),
                AtlasHoverChanged(target),
                AtlasCalculatedLayoutChanged(calculated),
            ).reduceAtlasSession(initial)

        assertEquals(AtlasOverlay.Scope(AtlasHierarchyLevel.BUILD), updated.overlay)
        assertIs<AtlasGesture.Pan>(updated.gesture)
        assertEquals(target, updated.inspection.hovered)
        assertEquals(calculated, updated.layout.calculated)
        assertEquals(initial.history, updated.history)
        assertEquals(initial.camera, updated.camera)
        assertEquals(initial.scope, updated.scope)
        assertEquals(initial.focus, updated.focus)
    }

    @Test
    fun derivesBoundedNodeKindsFromVisibleSemanticLayers() {
        val session =
            atlasSession().copy(
                layers =
                    AtlasLayers(
                        enabled =
                            listOf(
                                AtlasLayer.CONTAINMENT,
                                AtlasLayer.FILES,
                                AtlasLayer.SYMBOLS,
                                AtlasLayer.PROBLEMS,
                                AtlasLayer.CYCLES,
                            ).sortedBy(AtlasLayer::ordinal),
                    ),
            )

        val nodeKinds =
            session
                .workspaceGraphRequest(WorkspaceGraphFacet.SOURCE)
                .view.filter.nodeKinds

        assertEquals(
            listOf(
                WorkspaceGraphNodeKind.BUILD,
                WorkspaceGraphNodeKind.CYCLE,
                WorkspaceGraphNodeKind.FILE,
                WorkspaceGraphNodeKind.MEMBER,
                WorkspaceGraphNodeKind.PACKAGE,
                WorkspaceGraphNodeKind.PROBLEM,
                WorkspaceGraphNodeKind.PROJECT,
                WorkspaceGraphNodeKind.SOURCE_SET,
                WorkspaceGraphNodeKind.TYPE,
                WorkspaceGraphNodeKind.WORKSPACE,
            ),
            nodeKinds,
        )
    }

    @Test
    fun derivesAPinnedBoundedRequestFromTheDeepestScopeFocusAndFilters() {
        val filters =
            AtlasFilters(
                search = AtlasSearchFilter("Parser"),
                declarations = listOf(AtlasDeclarationKind.CLASS),
                nodeKinds = listOf(WorkspaceGraphNodeKind.TYPE),
                relationships =
                    AtlasRelationshipFilter(
                        kinds = listOf(WorkspaceGraphRelationKind.CALL),
                        direction = AtlasRelationshipDirection.INCOMING,
                        minimumCountExclusive = 5,
                    ),
            )
        val selected =
            listOf(
                AtlasScopeSelectionReplaced(AtlasHierarchyLevel.BUILD, listOf(BUILD_ONE_ID)),
                AtlasScopeSelectionReplaced(AtlasHierarchyLevel.PROJECT, listOf(PROJECT_ONE_ID)),
                AtlasFocusChanged(
                    AtlasFocus(
                        path = listOf(GROUP_ID, BUILD_ONE_ID, PROJECT_ONE_ID),
                        expandedIds = listOf(BUILD_ONE_ID, SOURCE_ONE_ID, SOURCE_TWO_ID),
                    ),
                ),
                AtlasFiltersChanged(filters),
            ).reduceAtlasSession(atlasSession())

        val request = selected.workspaceGraphRequest(WorkspaceGraphFacet.SOURCE)

        assertEquals(WORKSPACE_ID.value, request.workspaceId)
        assertEquals("generation:one", request.generationId)
        assertEquals(listOf(PROJECT_ONE_ID.value), request.view.selection.scopeRootIds)
        assertEquals(listOf(SOURCE_ONE_ID.value), request.view.selection.expandedNodeIds)
        assertEquals(listOf(PROJECT_ONE_ID.value), request.view.selection.focusNodeIds)
        assertEquals("Parser", request.view.filter.query)
        assertEquals(listOf(WorkspaceGraphNodeKind.TYPE), request.view.filter.nodeKinds)
        assertEquals(listOf(WorkspaceGraphRelationKind.CALL), request.view.filter.relationKinds)
        assertEquals(listOf(WorkspaceGraphDeclarationKind.CLASS), request.view.filter.declarationKinds)
        assertEquals(
            5,
            request.view.filter.relationshipCountFilter
                ?.moreThan,
        )
        assertEquals(
            WorkspaceGraphRelationshipDirection.INCOMING,
            request.view.filter.relationshipCountFilter
                ?.direction,
        )
    }

    @Test
    fun keepsPrimaryInspectionOutOfTheBoundedGraphRequest() {
        val initial = atlasSession()
        val request = initial.workspaceGraphRequest(WorkspaceGraphFacet.SOURCE)
        val pending = reduceAtlasSession(initial, AtlasRequestStarted(request))
        val token = assertIs<AtlasRequestState.Pending>(pending.request).token
        val settled = reduceAtlasSession(pending, AtlasRequestSucceeded(token, graphSlice(token, request.facet)))
        val inspected =
            reduceAtlasSession(
                settled,
                AtlasPrimaryInspectionChanged(nodeTarget(WORKSPACE_ID)),
            )

        assertEquals(request, inspected.workspaceGraphRequest(WorkspaceGraphFacet.SOURCE))
        assertEquals(nodeTarget(WORKSPACE_ID), inspected.inspection.primary)
    }
}

private fun atlasSession(): AtlasSession =
    AtlasSession(
        generation = AtlasGeneration(WORKSPACE_ID, "generation:one"),
        hierarchy = hierarchy(),
    )

private fun hierarchy(): AtlasHierarchyIndex =
    AtlasHierarchyIndex.of(
        listOf(
            AtlasHierarchyNode(GROUP_ID, AtlasHierarchyLevel.APPLICATION_GROUP),
            AtlasHierarchyNode(BUILD_ONE_ID, AtlasHierarchyLevel.BUILD, GROUP_ID),
            AtlasHierarchyNode(BUILD_TWO_ID, AtlasHierarchyLevel.BUILD, GROUP_ID),
            AtlasHierarchyNode(PROJECT_ONE_ID, AtlasHierarchyLevel.PROJECT, BUILD_ONE_ID),
            AtlasHierarchyNode(PROJECT_TWO_ID, AtlasHierarchyLevel.PROJECT, BUILD_TWO_ID),
            AtlasHierarchyNode(SOURCE_ONE_ID, AtlasHierarchyLevel.SOURCE_SET, PROJECT_ONE_ID),
            AtlasHierarchyNode(SOURCE_TWO_ID, AtlasHierarchyLevel.SOURCE_SET, PROJECT_TWO_ID),
        ),
    )

private fun graphSlice(
    token: AtlasRequestToken,
    facet: WorkspaceGraphFacet,
): WorkspaceGraphSlice =
    WorkspaceGraphSlice(
        target =
            WorkspaceGraphSliceTarget(
                workspaceId = token.generation.workspaceId.value,
                generationId = token.generation.generationId,
                facet = facet,
            ),
        content =
            WorkspaceGraphSliceContent(
                availability =
                    WorkspaceGraphFactKind.entries.sortedBy { fact -> fact.name }.map { fact ->
                        WorkspaceGraphFactAvailability(fact, WorkspaceGraphAvailabilityState.COMPLETE, 0)
                    },
                nodes =
                    listOf(
                        WorkspaceGraphNode(
                            id = token.generation.workspaceId.value,
                            kind = WorkspaceGraphNodeKind.WORKSPACE,
                            hierarchy = WorkspaceGraphNodeHierarchy(null, 0, 0, 0),
                            presentation = WorkspaceGraphNodePresentation("Fixture workspace"),
                            roles = listOf(WorkspaceGraphNodeRole.PRIMARY),
                        ),
                    ),
                relations = emptyList(),
            ),
        counts = WorkspaceGraphSliceCounts(1, 0),
    )

private fun nodeTarget(id: AtlasSemanticId): AtlasInspectionTarget =
    AtlasInspectionTarget(AtlasInspectionKind.NODE, id)

private val WORKSPACE_ID = AtlasSemanticId("workspace:fixture")
private val GROUP_ID = AtlasSemanticId("group:fixture")
private val BUILD_ONE_ID = AtlasSemanticId("build:one")
private val BUILD_TWO_ID = AtlasSemanticId("build:two")
private val PROJECT_ONE_ID = AtlasSemanticId("project:one")
private val PROJECT_TWO_ID = AtlasSemanticId("project:two")
private val SOURCE_ONE_ID = AtlasSemanticId("source:one:main")
private val SOURCE_TWO_ID = AtlasSemanticId("source:two:main")
private val RELATION_ID = AtlasSemanticId("relation:fixture")
private val FACT_ID = AtlasSemanticId("fact:fixture")
