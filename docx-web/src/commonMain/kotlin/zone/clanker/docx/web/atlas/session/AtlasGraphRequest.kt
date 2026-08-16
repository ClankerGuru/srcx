package zone.clanker.docx.web.atlas.session

import zone.clanker.report.model.WorkspaceGraphDeclarationKind
import zone.clanker.report.model.WorkspaceGraphFacet
import zone.clanker.report.model.WorkspaceGraphFilter
import zone.clanker.report.model.WorkspaceGraphLimits
import zone.clanker.report.model.WorkspaceGraphNodeKind
import zone.clanker.report.model.WorkspaceGraphRelationshipCountFilter
import zone.clanker.report.model.WorkspaceGraphRelationshipDirection
import zone.clanker.report.model.WorkspaceGraphRequest
import zone.clanker.report.model.WorkspaceGraphSearchTarget
import zone.clanker.report.model.WorkspaceGraphSelection
import zone.clanker.report.model.WorkspaceGraphView

internal fun AtlasSession.workspaceGraphRequest(
    facet: WorkspaceGraphFacet,
    limits: WorkspaceGraphLimits = WorkspaceGraphLimits(),
    continuationToken: String? = null,
): WorkspaceGraphRequest =
    WorkspaceGraphRequest(
        workspaceId = generation.workspaceId.value,
        generationId = generation.generationId,
        facet = facet,
        view =
            WorkspaceGraphView(
                selection =
                    WorkspaceGraphSelection(
                        scopeRootIds = scope.deepestSelectedIds().map(AtlasSemanticId::value),
                        expandedNodeIds = scopedExpandedIds().map(AtlasSemanticId::value),
                        focusNodeIds = requestedFocusIds().map(AtlasSemanticId::value),
                    ),
                filter =
                    WorkspaceGraphFilter(
                        nodeKinds = filters.nodeKinds.ifEmpty { layers.workspaceNodeKinds() },
                        relationKinds = filters.relationships.kinds,
                        query = filters.search.query,
                        searchTargets = filters.search.targets.workspaceTargets(),
                        declarationKinds = filters.declarations.workspaceDeclarationKinds(),
                        relationshipCountFilter = filters.relationships.workspaceCountFilter(),
                    ),
                continuationToken = continuationToken,
            ),
        limits = limits,
    )

private fun AtlasLayers.workspaceNodeKinds(): List<WorkspaceGraphNodeKind> =
    (NAVIGATION_NODE_KINDS + enabled.flatMap(AtlasLayer::workspaceNodeKinds))
        .distinct()
        .sortedBy(WorkspaceGraphNodeKind::name)

private val AtlasLayer.workspaceNodeKinds: List<WorkspaceGraphNodeKind>
    get() =
        when (this) {
            AtlasLayer.FILES -> listOf(WorkspaceGraphNodeKind.FILE)
            AtlasLayer.SYMBOLS -> listOf(WorkspaceGraphNodeKind.TYPE, WorkspaceGraphNodeKind.MEMBER)
            AtlasLayer.PROBLEMS -> listOf(WorkspaceGraphNodeKind.PROBLEM)
            AtlasLayer.CYCLES -> listOf(WorkspaceGraphNodeKind.CYCLE)
            AtlasLayer.TASKS -> listOf(WorkspaceGraphNodeKind.TASK)
            AtlasLayer.DEPENDENCIES -> listOf(WorkspaceGraphNodeKind.DEPENDENCY)
            AtlasLayer.VARIANTS -> listOf(WorkspaceGraphNodeKind.VARIANT)
            AtlasLayer.UPGRADES -> listOf(WorkspaceGraphNodeKind.UPGRADE)
            AtlasLayer.CONTAINMENT,
            AtlasLayer.RELATIONSHIPS,
            AtlasLayer.LABELS,
            -> emptyList()
        }

private val NAVIGATION_NODE_KINDS =
    listOf(
        WorkspaceGraphNodeKind.WORKSPACE,
        WorkspaceGraphNodeKind.BUILD,
        WorkspaceGraphNodeKind.PROJECT,
        WorkspaceGraphNodeKind.SOURCE_SET,
        WorkspaceGraphNodeKind.PACKAGE,
    )

private fun AtlasSession.requestedFocusIds(): List<AtlasSemanticId> =
    canonicalIds(
        listOfNotNull(
            focus.targetId,
            focus.requestTargetId,
        ),
    )

private fun AtlasSession.scopedExpandedIds(): List<AtlasSemanticId> {
    val roots = scope.deepestSelectedIds()
    if (roots.isEmpty()) return focus.effectiveExpandedIds
    return focus.effectiveExpandedIds.filter { expandedId ->
        roots.any { rootId -> expandedId == rootId || hierarchy.isDescendantOf(expandedId, rootId) }
    }
}

private fun List<AtlasDeclarationKind>.workspaceDeclarationKinds(): List<WorkspaceGraphDeclarationKind> =
    map { declaration -> WorkspaceGraphDeclarationKind.valueOf(declaration.name) }
        .distinct()
        .sortedBy(WorkspaceGraphDeclarationKind::name)

private fun List<AtlasSearchTarget>.workspaceTargets(): List<WorkspaceGraphSearchTarget> =
    mapNotNull { target ->
        when (target) {
            AtlasSearchTarget.BUILD -> WorkspaceGraphSearchTarget.BUILD
            AtlasSearchTarget.PROJECT -> WorkspaceGraphSearchTarget.PROJECT
            AtlasSearchTarget.PACKAGE -> WorkspaceGraphSearchTarget.PACKAGE
            AtlasSearchTarget.FILE -> WorkspaceGraphSearchTarget.FILE
            AtlasSearchTarget.FILE_EXTENSION -> WorkspaceGraphSearchTarget.FILE_EXTENSION
            AtlasSearchTarget.CLASS -> WorkspaceGraphSearchTarget.CLASS
            AtlasSearchTarget.METHOD,
            AtlasSearchTarget.FUNCTION,
            -> WorkspaceGraphSearchTarget.METHOD
            AtlasSearchTarget.INTERFACE,
            AtlasSearchTarget.OBJECT,
            AtlasSearchTarget.ENUM,
            AtlasSearchTarget.TYPE,
            AtlasSearchTarget.PROPERTY,
            AtlasSearchTarget.SYMBOL,
            -> WorkspaceGraphSearchTarget.SYMBOL
            AtlasSearchTarget.APPLICATION_GROUP,
            AtlasSearchTarget.SOURCE_SET,
            AtlasSearchTarget.TASK,
            AtlasSearchTarget.DEPENDENCY,
            AtlasSearchTarget.PROBLEM,
            AtlasSearchTarget.CYCLE,
            -> null
        }
    }.distinct().sortedBy(WorkspaceGraphSearchTarget::name)

private fun AtlasRelationshipFilter.workspaceCountFilter(): WorkspaceGraphRelationshipCountFilter? =
    minimumCountExclusive
        .takeIf { threshold -> threshold > 0 }
        ?.let { threshold ->
            WorkspaceGraphRelationshipCountFilter(
                moreThan = threshold,
                direction = direction.workspaceDirection,
                keepInverseContext = keepInverseContext,
            )
        }

private val AtlasRelationshipDirection.workspaceDirection: WorkspaceGraphRelationshipDirection
    get() =
        when (this) {
            AtlasRelationshipDirection.ANY -> WorkspaceGraphRelationshipDirection.ANY
            AtlasRelationshipDirection.INCOMING -> WorkspaceGraphRelationshipDirection.INCOMING
            AtlasRelationshipDirection.OUTGOING -> WorkspaceGraphRelationshipDirection.OUTGOING
        }
