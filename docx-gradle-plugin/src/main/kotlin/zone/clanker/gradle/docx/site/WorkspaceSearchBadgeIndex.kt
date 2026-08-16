package zone.clanker.gradle.docx.site

import zone.clanker.report.model.ProjectGraphShard
import zone.clanker.report.model.ReferenceSnapshot
import zone.clanker.report.model.SymbolSnapshot
import zone.clanker.report.model.WorkspaceSearchBadge
import zone.clanker.report.model.WorkspaceSearchBadgeKind

internal class WorkspaceSearchBadgeIndex(
    graph: ProjectGraphShard,
) {
    private val relationshipsByEntity = mutableMapOf<String, MutableSet<String>>()
    private val problemsByEntity = mutableMapOf<String, MutableSet<String>>()
    private val cyclesByEntity = mutableMapOf<String, MutableSet<String>>()

    init {
        val referencesById = graph.references.associateBy(ReferenceSnapshot::id)
        val symbolsById = graph.symbols.associateBy(SymbolSnapshot::id)
        graph.relationships.forEach { relationship ->
            val reference = referencesById.getValue(relationship.referenceId)
            val symbolIds = setOfNotNull(relationship.sourceSymbolId, relationship.targetSymbolId)
            val fileIds =
                symbolIds
                    .mapNotNullTo(mutableSetOf(), symbolsById::get)
                    .mapTo(mutableSetOf(), SymbolSnapshot::fileId)
            fileIds += reference.sourceFileId
            relationshipsByEntity.add(relationship.id, symbolIds + fileIds)
        }
        graph.findings.forEach { finding ->
            val fileIds =
                finding.symbolIds
                    .mapNotNullTo(mutableSetOf(), symbolsById::get)
                    .mapTo(mutableSetOf(), SymbolSnapshot::fileId)
                    .apply { finding.fileId?.let(::add) }
            problemsByEntity.add(finding.id, finding.symbolIds + fileIds)
        }
        graph.cycles.forEach { cycle ->
            val fileIds =
                cycle.symbolIds
                    .mapNotNullTo(mutableSetOf(), symbolsById::get)
                    .mapTo(mutableSetOf(), SymbolSnapshot::fileId)
            cyclesByEntity.add(cycle.id, cycle.symbolIds + fileIds)
        }
    }

    fun badges(entityId: String): List<WorkspaceSearchBadge> =
        listOfNotNull(
            relationshipsByEntity.badge(entityId, WorkspaceSearchBadgeKind.RELATIONSHIPS),
            problemsByEntity.badge(entityId, WorkspaceSearchBadgeKind.PROBLEMS),
            cyclesByEntity.badge(entityId, WorkspaceSearchBadgeKind.CYCLES),
        )
}

private fun MutableMap<String, MutableSet<String>>.add(
    recordId: String,
    entityIds: Iterable<String>,
) {
    entityIds.forEach { entityId -> getOrPut(entityId, ::mutableSetOf).add(recordId) }
}

private fun Map<String, MutableSet<String>>.badge(
    entityId: String,
    kind: WorkspaceSearchBadgeKind,
): WorkspaceSearchBadge? =
    get(entityId)
        ?.size
        ?.takeIf { count -> count > 0 }
        ?.let { count -> WorkspaceSearchBadge(kind, count.toLong()) }
