package zone.clanker.docx.web.atlas.projection

import zone.clanker.report.model.ProjectGraphShard
import zone.clanker.report.model.RelationshipKind
import zone.clanker.report.model.RelationshipSnapshot
import zone.clanker.report.model.SourceFileSnapshot
import zone.clanker.report.model.SymbolKind
import zone.clanker.report.model.SymbolSnapshot
import kotlin.math.ceil

/** Deterministic, truthful viewport-sized subset of a potentially very large project graph. */
data class BoundedGraphFrame(
    val primaryNodeIds: List<String>,
    val visibleNodeIds: List<String>,
    val visibleRelationshipIds: List<String>,
    val totalNodeCount: Int,
    val matchingNodeCount: Int,
    val pageIndex: Int,
    val pageCount: Int,
)

data class BoundedFileGraphFrame(
    val primaryFileIds: List<String>,
    val visibleFileIds: List<String>,
    val visibleRelationshipIds: List<String>,
    val totalFileCount: Int,
    val matchingFileCount: Int,
    val pageIndex: Int,
    val pageCount: Int,
)

data class GraphFrameRequest(
    val query: String,
    val requestedPage: Int,
    val sourceSetIds: Set<String> = emptySet(),
    val symbolKinds: Set<SymbolKind> = SymbolKind.entries.toSet(),
    val allowedKinds: Set<RelationshipKind> = RelationshipKind.entries.toSet(),
    val preferredRelationshipId: String? = null,
)

fun boundedGraphFrame(
    project: ProjectGraphShard,
    request: GraphFrameRequest,
): BoundedGraphFrame {
    val normalizedQuery = request.query.trim().lowercase()
    val eligibleFileIds =
        project.files
            .filter { file -> request.sourceSetIds.isEmpty() || file.sourceSetId in request.sourceSetIds }
            .mapTo(mutableSetOf(), SourceFileSnapshot::id)
    val matchingSymbols =
        project.symbols
            .asSequence()
            .filter { symbol -> symbol.fileId in eligibleFileIds }
            .filter { symbol -> symbol.kind in request.symbolKinds }
            .filter { symbol -> normalizedQuery.isEmpty() || symbol.matches(normalizedQuery) }
            .sortedWith(compareBy(SymbolSnapshot::qualifiedName, SymbolSnapshot::id))
            .toList()
    val eligibleSymbolIds =
        project.symbols
            .filter { symbol -> symbol.fileId in eligibleFileIds && symbol.kind in request.symbolKinds }
            .mapTo(mutableSetOf(), SymbolSnapshot::id)
    val pageCount =
        if (matchingSymbols.isEmpty()) {
            0
        } else {
            ceil(matchingSymbols.size.toDouble() / PRIMARY_PAGE_SIZE).toInt()
        }
    val pageIndex = request.requestedPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
    val primaryNodeIds =
        matchingSymbols
            .drop(pageIndex * PRIMARY_PAGE_SIZE)
            .take(PRIMARY_PAGE_SIZE)
            .map(SymbolSnapshot::id)
    val eligibleRelationships =
        project.relationships
            .filter { relationship ->
                relationship.kind in request.allowedKinds &&
                    relationship.sourceSymbolId != null &&
                    relationship.sourceSymbolId in eligibleSymbolIds &&
                    relationship.targetSymbolId in eligibleSymbolIds
            }.withPreferred(request.preferredRelationshipId)
    val visibleNodeIds = expandVisibleEndpoints(primaryNodeIds, eligibleRelationships)
    val visibleNodeSet = visibleNodeIds.toSet()
    val visibleRelationshipIds =
        eligibleRelationships
            .filter { relationship -> relationship.hasVisibleEndpoints(visibleNodeSet) }
            .map(RelationshipSnapshot::id)

    return BoundedGraphFrame(
        primaryNodeIds = primaryNodeIds,
        visibleNodeIds = visibleNodeIds,
        visibleRelationshipIds = visibleRelationshipIds,
        totalNodeCount = project.symbols.size,
        matchingNodeCount = matchingSymbols.size,
        pageIndex = pageIndex,
        pageCount = pageCount,
    )
}

fun boundedFileGraphFrame(
    project: ProjectGraphShard,
    request: GraphFrameRequest,
): BoundedFileGraphFrame {
    val normalizedQuery = request.query.trim().lowercase()
    val eligibleFileIds =
        project.files
            .filter { file -> request.sourceSetIds.isEmpty() || file.sourceSetId in request.sourceSetIds }
            .mapTo(mutableSetOf(), SourceFileSnapshot::id)
    val matchingFiles =
        project.files
            .asSequence()
            .filter { file -> file.id in eligibleFileIds }
            .filter { file -> normalizedQuery.isEmpty() || file.matches(normalizedQuery) }
            .sortedWith(compareBy(SourceFileSnapshot::projectRelativePath, SourceFileSnapshot::id))
            .toList()
    val pageCount =
        if (matchingFiles.isEmpty()) {
            0
        } else {
            ceil(matchingFiles.size.toDouble() / PRIMARY_PAGE_SIZE).toInt()
        }
    val pageIndex = request.requestedPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
    val primaryFileIds =
        matchingFiles
            .drop(pageIndex * PRIMARY_PAGE_SIZE)
            .take(PRIMARY_PAGE_SIZE)
            .map(SourceFileSnapshot::id)
    val eligibleRelationships =
        project.relationships
            .filter { relationship -> relationship.kind in request.allowedKinds }
            .withPreferred(request.preferredRelationshipId)
    val endpointsByRelationshipId = project.fileEndpoints(eligibleRelationships, eligibleFileIds)
    val visibleFileIds = expandEndpointPairs(primaryFileIds, endpointsByRelationshipId.values.toList())
    val visibleFileSet = visibleFileIds.toSet()
    val visibleRelationshipIds =
        eligibleRelationships
            .filter { relationship ->
                endpointsByRelationshipId[relationship.id]?.let { (source, target) ->
                    source in visibleFileSet && target in visibleFileSet
                } == true
            }.map(RelationshipSnapshot::id)
    return BoundedFileGraphFrame(
        primaryFileIds = primaryFileIds,
        visibleFileIds = visibleFileIds,
        visibleRelationshipIds = visibleRelationshipIds,
        totalFileCount = project.files.size,
        matchingFileCount = matchingFiles.size,
        pageIndex = pageIndex,
        pageCount = pageCount,
    )
}

private fun ProjectGraphShard.fileEndpoints(
    relationships: List<RelationshipSnapshot>,
    eligibleFileIds: Set<String>,
): Map<String, Pair<String, String>> {
    val referencesById = references.associateBy { reference -> reference.id }
    val symbolsById = symbols.associateBy(SymbolSnapshot::id)
    return relationships
        .mapNotNull { relationship ->
            val sourceFileId = referencesById[relationship.referenceId]?.sourceFileId
            val targetFileId = symbolsById[relationship.targetSymbolId]?.fileId
            sourceFileId
                ?.let { source -> targetFileId?.let { target -> source to target } }
                ?.takeIf { (source, target) -> source in eligibleFileIds && target in eligibleFileIds }
                ?.let { endpoint -> relationship.id to endpoint }
        }.toMap()
}

fun pageContainingNode(
    project: ProjectGraphShard,
    symbolId: String,
): Int {
    val sortedIds =
        project.symbols
            .sortedWith(compareBy(SymbolSnapshot::qualifiedName, SymbolSnapshot::id))
            .map(SymbolSnapshot::id)
    return sortedIds.indexOf(symbolId).coerceAtLeast(0) / PRIMARY_PAGE_SIZE
}

fun pageContainingFile(
    project: ProjectGraphShard,
    fileId: String,
): Int {
    val sortedIds =
        project.files
            .sortedWith(compareBy(SourceFileSnapshot::projectRelativePath, SourceFileSnapshot::id))
            .map(SourceFileSnapshot::id)
    return sortedIds.indexOf(fileId).coerceAtLeast(0) / PRIMARY_PAGE_SIZE
}

private fun SymbolSnapshot.matches(query: String): Boolean =
    name.lowercase().contains(query) ||
        qualifiedName.lowercase().contains(query) ||
        packageName.lowercase().contains(query)

private fun SourceFileSnapshot.matches(query: String): Boolean =
    projectRelativePath.lowercase().contains(query) || language.label.lowercase().contains(query)

private fun expandVisibleEndpoints(
    primaryNodeIds: List<String>,
    relationships: List<RelationshipSnapshot>,
): List<String> =
    expandEndpointPairs(
        primaryIds = primaryNodeIds,
        endpointPairs =
            relationships.mapNotNull { relationship ->
                relationship.sourceSymbolId?.let { sourceId -> sourceId to relationship.targetSymbolId }
            },
    )

private fun expandEndpointPairs(
    primaryIds: List<String>,
    endpointPairs: List<Pair<String, String>>,
): List<String> {
    val primarySet = primaryIds.toSet()
    val visible = LinkedHashSet(primaryIds)
    endpointPairs.forEach { (sourceId, targetId) ->
        if (sourceId !in primarySet && targetId !in primarySet) return@forEach
        val missing = listOf(sourceId, targetId).count { id -> id !in visible }
        if (visible.size + missing <= MAX_VISIBLE_NODES) {
            visible += sourceId
            visible += targetId
        }
    }
    return visible.toList()
}

private fun RelationshipSnapshot.hasVisibleEndpoints(visibleNodeIds: Set<String>): Boolean =
    sourceSymbolId != null && sourceSymbolId in visibleNodeIds && targetSymbolId in visibleNodeIds

private fun List<RelationshipSnapshot>.withPreferred(preferredId: String?): List<RelationshipSnapshot> =
    sortedWith(
        compareBy<RelationshipSnapshot> { relationship -> if (relationship.id == preferredId) 0 else 1 }
            .thenBy(RelationshipSnapshot::id),
    )

const val MAX_VISIBLE_NODES: Int = 42
const val PRIMARY_PAGE_SIZE: Int = 28
