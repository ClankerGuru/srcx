package zone.clanker.docx.web.atlas.hierarchy

import zone.clanker.report.model.BuildEdgeSnapshot
import zone.clanker.report.model.RelationshipKind
import zone.clanker.report.model.RelationshipSnapshot
import zone.clanker.report.model.WorkspaceGraphAvailabilityReason
import zone.clanker.report.model.WorkspaceGraphAvailabilityState
import zone.clanker.report.model.WorkspaceGraphFactAvailability
import zone.clanker.report.model.WorkspaceGraphFactKind
import zone.clanker.report.model.WorkspaceGraphRelationKind
import zone.clanker.report.model.WorkspaceSummaryShard

internal class WorkspaceSourceHierarchyEvidence(
    private val summary: WorkspaceSummaryShard,
    private val loadedFacts: WorkspaceSourceHierarchyLoadedFacts,
    private val catalog: WorkspaceSourceHierarchyCatalog,
) {
    fun facts(): List<WorkspaceSourceHierarchyFact> =
        (summary.buildEdges.map(::buildFact) + loadedFacts.relationshipsById.values.mapNotNull(::codeFact))
            .sortedWith(
                compareBy<WorkspaceSourceHierarchyFact>(
                    { fact -> fact.factId },
                    { fact -> fact.kind.name },
                ),
            )

    fun availability(): List<WorkspaceGraphFactAvailability> =
        WorkspaceGraphFactKind.entries
            .sortedBy(Enum<*>::name)
            .map(::availabilityFor)

    private fun buildFact(edge: BuildEdgeSnapshot): WorkspaceSourceHierarchyFact =
        WorkspaceSourceHierarchyFact(
            factId = edge.id,
            kind = WorkspaceGraphRelationKind.BUILD_DEPENDS_ON,
            sourcePath = catalog.path(edge.sourceBuildId),
            targetPath = catalog.path(edge.targetBuildId),
        )

    private fun codeFact(relationship: RelationshipSnapshot): WorkspaceSourceHierarchyFact? {
        val reference = loadedFacts.referencesById.getValue(relationship.referenceId)
        val sourcePath =
            relationship.sourceSymbolId
                ?.let(catalog::recordOrNull)
                ?.let { symbol -> catalog.path(symbol.id) }
                ?: pathForFile(reference.sourceFileId)
                ?: return null
        val targetPath =
            catalog
                .recordOrNull(relationship.targetSymbolId)
                ?.let { symbol -> catalog.path(symbol.id) }
                ?: return null
        return WorkspaceSourceHierarchyFact(
            factId = relationship.id,
            kind = relationship.kind.graphKind(),
            sourcePath = sourcePath,
            targetPath = targetPath,
        )
    }

    private fun pathForFile(fileId: String): List<WorkspaceSourceHierarchyRecord>? {
        catalog.recordOrNull(fileId)?.let { file -> return catalog.path(file.id) }
        val sourceSetId = loadedFacts.filesById.getValue(fileId).sourceSetId
        val sourceSet = loadedFacts.sourceSetsById.getValue(sourceSetId)
        return catalog.sourceSetPath(sourceSetId, sourceSet.projectId)
    }

    private fun availabilityFor(fact: WorkspaceGraphFactKind): WorkspaceGraphFactAvailability =
        when (fact) {
            WorkspaceGraphFactKind.STRUCTURE -> complete(fact, catalog.size.toLong())
            WorkspaceGraphFactKind.BUILD_DEPENDENCIES -> complete(fact, summary.buildEdges.size.toLong())
            WorkspaceGraphFactKind.PACKAGES -> loadedLegacyFacts(fact, catalog.observedPackageCount.toLong())
            WorkspaceGraphFactKind.SYMBOL_DECLARATIONS ->
                loadedLegacyFacts(fact, loadedFacts.symbolsById.size.toLong())
            WorkspaceGraphFactKind.CODE_RELATIONSHIPS ->
                loadedLegacyFacts(fact, loadedFacts.relationshipsById.size.toLong())
            WorkspaceGraphFactKind.PROBLEMS ->
                loadedLegacyFacts(fact, loadedFacts.findingsById.size.toLong())
            WorkspaceGraphFactKind.CYCLES ->
                loadedLegacyFacts(fact, loadedFacts.cyclesById.size.toLong())
            WorkspaceGraphFactKind.MEMBER_OWNERSHIP,
            WorkspaceGraphFactKind.PROJECT_DEPENDENCIES,
            WorkspaceGraphFactKind.SOURCE_SET_DEPENDENCIES,
            WorkspaceGraphFactKind.VARIANTS,
            WorkspaceGraphFactKind.VARIANT_SOURCE_SETS,
            WorkspaceGraphFactKind.GRADLE_TASKS,
            WorkspaceGraphFactKind.TASK_RELATIONSHIPS,
            WorkspaceGraphFactKind.DECLARED_DEPENDENCIES,
            WorkspaceGraphFactKind.RESOLVED_DEPENDENCIES,
            WorkspaceGraphFactKind.DEPENDENCY_UPGRADES,
            -> unavailable(fact, WorkspaceGraphAvailabilityReason.NOT_CAPTURED)
        }

    private fun loadedLegacyFacts(
        fact: WorkspaceGraphFactKind,
        count: Long,
    ): WorkspaceGraphFactAvailability =
        if (loadedFacts.projects.isEmpty()) {
            unavailable(fact, WorkspaceGraphAvailabilityReason.NOT_INDEXED)
        } else {
            WorkspaceGraphFactAvailability(
                fact = fact,
                state = WorkspaceGraphAvailabilityState.PARTIAL,
                observedFactCount = count,
                reason = WorkspaceGraphAvailabilityReason.LEGACY_SNAPSHOT,
            )
        }
}

internal data class WorkspaceSourceHierarchyFact(
    val factId: String,
    val kind: WorkspaceGraphRelationKind,
    val sourcePath: List<WorkspaceSourceHierarchyRecord>,
    val targetPath: List<WorkspaceSourceHierarchyRecord>,
)

private fun complete(
    fact: WorkspaceGraphFactKind,
    count: Long,
): WorkspaceGraphFactAvailability =
    WorkspaceGraphFactAvailability(
        fact = fact,
        state = WorkspaceGraphAvailabilityState.COMPLETE,
        observedFactCount = count,
    )

private fun unavailable(
    fact: WorkspaceGraphFactKind,
    reason: WorkspaceGraphAvailabilityReason,
): WorkspaceGraphFactAvailability =
    WorkspaceGraphFactAvailability(
        fact = fact,
        state = WorkspaceGraphAvailabilityState.UNAVAILABLE,
        observedFactCount = 0,
        reason = reason,
    )

private fun RelationshipKind.graphKind(): WorkspaceGraphRelationKind =
    when (this) {
        RelationshipKind.IMPORT -> WorkspaceGraphRelationKind.IMPORT
        RelationshipKind.EXTENDS -> WorkspaceGraphRelationKind.EXTENDS
        RelationshipKind.IMPLEMENTS -> WorkspaceGraphRelationKind.IMPLEMENTS
        RelationshipKind.CALL -> WorkspaceGraphRelationKind.CALL
        RelationshipKind.CONSTRUCTOR -> WorkspaceGraphRelationKind.CONSTRUCTOR
        RelationshipKind.NAME_REFERENCE -> WorkspaceGraphRelationKind.NAME_REFERENCE
        RelationshipKind.TYPE_REFERENCE -> WorkspaceGraphRelationKind.TYPE_REFERENCE
        RelationshipKind.PROPERTY_TYPE -> WorkspaceGraphRelationKind.PROPERTY_TYPE
        RelationshipKind.PARAMETER_TYPE -> WorkspaceGraphRelationKind.PARAMETER_TYPE
        RelationshipKind.RETURN_TYPE -> WorkspaceGraphRelationKind.RETURN_TYPE
    }
