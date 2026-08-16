package zone.clanker.docx.web.atlas.hierarchy

import zone.clanker.report.model.WorkspaceGraphFilter
import zone.clanker.report.model.WorkspaceGraphRelationshipDirection

internal class WorkspaceSourceHierarchyRelationshipFilter(
    private val filter: WorkspaceGraphFilter,
    facts: List<WorkspaceSourceHierarchyFact>,
) {
    private val countFilter = filter.relationshipCountFilter
    private val countsByDirection =
        countFilter?.let { directionalCounts(facts.filter(::kindMatches)) }.orEmpty()

    val enabled: Boolean
        get() = countFilter != null

    fun matchesCount(recordId: String): Boolean {
        val requested = countFilter ?: return true
        val count = countsByDirection.getValue(requested.direction)[recordId] ?: 0
        return count > requested.moreThan
    }

    fun allowsContext(
        fact: WorkspaceSourceHierarchyFact,
        anchorIds: Set<String>,
    ): Boolean {
        val requested = countFilter
        return when {
            requested == null || requested.keepInverseContext -> true
            requested.direction == WorkspaceGraphRelationshipDirection.OUTGOING ->
                fact.sourcePath.any { record -> record.id in anchorIds }

            requested.direction == WorkspaceGraphRelationshipDirection.INCOMING ->
                fact.targetPath.any { record -> record.id in anchorIds }

            else -> true
        }
    }

    private fun kindMatches(fact: WorkspaceSourceHierarchyFact): Boolean =
        filter.relationKinds.isEmpty() || fact.kind in filter.relationKinds
}

private fun directionalCounts(
    facts: List<WorkspaceSourceHierarchyFact>,
): Map<WorkspaceGraphRelationshipDirection, Map<String, Long>> {
    val outgoing = mutableMapOf<String, Long>()
    val incoming = mutableMapOf<String, Long>()
    val any = mutableMapOf<String, Long>()
    facts.forEach { fact ->
        val sourceIds = fact.sourcePath.mapTo(mutableSetOf(), WorkspaceSourceHierarchyRecord::id)
        val targetIds = fact.targetPath.mapTo(mutableSetOf(), WorkspaceSourceHierarchyRecord::id)
        sourceIds.forEach(outgoing::increment)
        targetIds.forEach(incoming::increment)
        (sourceIds + targetIds).forEach(any::increment)
    }
    return mapOf(
        WorkspaceGraphRelationshipDirection.ANY to any,
        WorkspaceGraphRelationshipDirection.OUTGOING to outgoing,
        WorkspaceGraphRelationshipDirection.INCOMING to incoming,
    )
}

private fun MutableMap<String, Long>.increment(id: String) {
    this[id] = (this[id] ?: 0) + 1
}
