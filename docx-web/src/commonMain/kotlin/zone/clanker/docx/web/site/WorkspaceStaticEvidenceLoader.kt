package zone.clanker.docx.web.site

import zone.clanker.report.model.WorkspaceDeclarationEvidencePage
import zone.clanker.report.model.WorkspaceDeclarationEvidenceRequest
import zone.clanker.report.model.WorkspaceEvidenceCursor
import zone.clanker.report.model.WorkspaceEvidenceJson
import zone.clanker.report.model.WorkspaceEvidenceTarget
import zone.clanker.report.model.WorkspaceRelationshipOccurrence
import zone.clanker.report.model.WorkspaceRelationshipOccurrencePage
import zone.clanker.report.model.WorkspaceRelationshipOccurrenceRequest
import zone.clanker.report.model.WorkspaceReverseUsagePage
import zone.clanker.report.model.WorkspaceReverseUsageRequest
import zone.clanker.report.model.WorkspaceStaticEvidenceCatalog
import zone.clanker.report.model.WorkspaceStaticEvidenceEntry
import zone.clanker.report.model.WorkspaceStaticEvidencePage
import zone.clanker.report.model.WorkspaceStaticEvidencePageReference
import zone.clanker.report.model.WorkspaceStaticEvidencePartition
import zone.clanker.report.model.WorkspaceStaticEvidencePartitionReference
import zone.clanker.report.model.workspaceDeclarationEvidenceRouteKey
import zone.clanker.report.model.workspaceEvidenceCursorComparator
import zone.clanker.report.model.workspaceEvidenceRouteHash
import zone.clanker.report.model.workspaceRelationshipOccurrenceRouteKey
import zone.clanker.report.model.workspaceReverseUsageRouteKey

internal class WorkspaceStaticEvidenceLoader(
    private val source: WorkspaceSiteTextSource,
    maxCacheBytes: Long = DEFAULT_EVIDENCE_CACHE_BYTES,
) {
    private val artifacts = StaticEvidenceArtifactLru(maxCacheBytes)
    private var activeCatalog: ActiveStaticEvidenceCatalog? = null

    suspend fun route(
        site: LoadedWorkspaceSite,
        routeKey: String,
        after: WorkspaceEvidenceCursor?,
        before: WorkspaceEvidenceCursor?,
        limit: Int,
    ): LoadedStaticEvidenceRoute? {
        val catalog = catalog(site) ?: return null
        val prefix = workspaceEvidenceRouteHash(routeKey).take(ROUTE_PREFIX_LENGTH)
        val partitionReference =
            catalog.partitions
                .singleOrNull { reference -> reference.prefix == prefix }
                ?: return LoadedStaticEvidenceRoute(emptyList(), hasFollowingPage = false)
        return loadRoute(partitionReference, routeKey, after, before, limit)
    }

    private suspend fun loadRoute(
        partitionReference: WorkspaceStaticEvidencePartitionReference,
        routeKey: String,
        after: WorkspaceEvidenceCursor?,
        before: WorkspaceEvidenceCursor?,
        limit: Int,
    ): LoadedStaticEvidenceRoute {
        require(after == null || before == null) { "Static evidence lookup accepts only one cursor direction" }
        val partition = partition(partitionReference)
        val locatedPages =
            partition.pages
                .flatMap { pageReference ->
                    pageReference.routes.map { locator -> LocatedStaticEvidencePage(pageReference, locator) }
                }.filter { located -> located.locator.routeKey == routeKey }
                .sortedBy { located -> located.locator.pageIndex }
        require(locatedPages.map { located -> located.locator.pageIndex } == locatedPages.indices.toList()) {
            "Workspace static-evidence route pages must be contiguous"
        }
        if (locatedPages.isEmpty()) return LoadedStaticEvidenceRoute(emptyList(), hasFollowingPage = false)
        val backwards = before != null
        val startIndex =
            if (backwards) {
                locatedPages.endIndex(requireNotNull(before))
            } else {
                locatedPages.startIndex(after)
            }
        val selected = mutableListOf<WorkspaceStaticEvidenceEntry>()
        selected += entry(locatedPages.first())
        var candidateCount = 0
        var index = startIndex
        var routePageReads = 1
        while (index in locatedPages.indices && candidateCount <= limit && routePageReads < MAX_ROUTE_PAGE_READS) {
            val candidate = entry(locatedPages[index])
            if (candidate !in selected) selected += candidate
            candidateCount += candidate.occurrencesBetween(after, before)
            index += if (backwards) -1 else 1
            routePageReads += 1
        }
        require(candidateCount > limit || index !in locatedPages.indices) {
            "Workspace static-evidence lookup exceeded its bounded page-read budget"
        }
        val directionalPages = selected.filter { entry -> entry.occurrencesBetween(after, before) > 0 }
        return LoadedStaticEvidenceRoute(
            entries = selected.sortedBy(WorkspaceStaticEvidenceEntry::pageIndex),
            hasPrecedingPage =
                directionalPages
                    .minOfOrNull(WorkspaceStaticEvidenceEntry::pageIndex)
                    ?.let { pageIndex -> pageIndex > 0 } == true,
            hasFollowingPage =
                directionalPages
                    .maxOfOrNull(WorkspaceStaticEvidenceEntry::pageIndex)
                    ?.let { pageIndex -> pageIndex < locatedPages.lastIndex } == true,
        )
    }

    private suspend fun catalog(site: LoadedWorkspaceSite): WorkspaceStaticEvidenceCatalog? {
        val reference = site.manifest.evidenceCatalog ?: return null
        val target =
            WorkspaceEvidenceTarget(
                site.summary.workspace.id,
                site.manifest.generationId,
            )
        if (activeCatalog?.target != target) {
            activeCatalog = null
            artifacts.clear()
        }
        val catalog =
            activeCatalog
                ?.takeIf { cached -> cached.file == reference.file }
                ?.catalog
                ?: WorkspaceEvidenceJson
                    .decodeStaticCatalog(read(reference.file, reference.encodedByteSize))
                    .also { decoded -> activeCatalog = ActiveStaticEvidenceCatalog(target, reference.file, decoded) }
        require(catalog.target.workspaceId == site.summary.workspace.id) {
            "Workspace evidence catalog does not belong to its site"
        }
        require(catalog.target.generationId == site.manifest.generationId) {
            "Workspace evidence catalog generation does not match its site"
        }
        return catalog
    }

    private suspend fun partition(
        reference: WorkspaceStaticEvidencePartitionReference,
    ): WorkspaceStaticEvidencePartition =
        artifacts.partition(reference.file)
            ?: WorkspaceEvidenceJson
                .decodeStaticPartition(read(reference.file, reference.encodedByteSize))
                .also { partition ->
                    require(partition.prefix == reference.prefix) {
                        "Workspace evidence partition does not match its catalog reference"
                    }
                    require(partition.pages.sumOf { page -> page.routes.size } == reference.routePageCount) {
                        "Workspace evidence partition route count does not match its catalog reference"
                    }
                    artifacts.put(reference.file, reference.encodedByteSize, CachedStaticEvidencePartition(partition))
                }

    private suspend fun entry(located: LocatedStaticEvidencePage): WorkspaceStaticEvidenceEntry {
        val page = page(located.reference)
        require(page.entries.map(WorkspaceStaticEvidenceEntry::locator) == located.reference.routes) {
            "Workspace evidence page routes do not match their partition reference"
        }
        return page.entries.single { entry ->
            entry.routeKey == located.locator.routeKey && entry.pageIndex == located.locator.pageIndex
        }
    }

    private suspend fun page(reference: WorkspaceStaticEvidencePageReference): WorkspaceStaticEvidencePage =
        artifacts.page(reference.file)
            ?: WorkspaceEvidenceJson
                .decodeStaticPage(read(reference.file, reference.encodedByteSize))
                .also { page ->
                    artifacts.put(reference.file, reference.encodedByteSize, CachedStaticEvidencePage(page))
                }

    private suspend fun read(
        file: String,
        expectedByteSize: Long,
    ): String =
        source.read(file).also { content ->
            require(content.utf8EncodedByteSize() == expectedByteSize) {
                "Workspace evidence payload byte size does not match its reference"
            }
        }
}

internal class StaticWorkspaceEvidenceGateway(
    private val site: LoadedWorkspaceSite,
    private val loader: WorkspaceStaticEvidenceLoader,
) : WorkspaceEvidenceGateway {
    override suspend fun declaration(request: WorkspaceDeclarationEvidenceRequest): WorkspaceDeclarationEvidencePage {
        requireTarget(request.target)
        val route =
            loader.route(site, workspaceDeclarationEvidenceRouteKey(request.symbolId), null, null, 1)
                ?: throw WorkspaceStaticEvidenceUnavailable()
        return (
            route.entries.singleOrNull()?.declaration
                ?: WorkspaceDeclarationEvidencePage(request.target, request.symbolId)
        ).also { page ->
            require(page.target == request.target && page.symbolId == request.symbolId) {
                "Workspace static declaration evidence does not match its request"
            }
        }
    }

    override suspend fun reverseUsages(request: WorkspaceReverseUsageRequest): WorkspaceReverseUsagePage {
        requireTarget(request.target)
        val route =
            loader.route(
                site,
                workspaceReverseUsageRouteKey(request.symbolId),
                request.after,
                request.before,
                request.limit,
            )
                ?: throw WorkspaceStaticEvidenceUnavailable()
        val pages = route.entries.mapNotNull(WorkspaceStaticEvidenceEntry::reverseUsages)
        require(pages.all { page -> page.target == request.target && page.symbolId == request.symbolId }) {
            "Workspace static reverse usages do not match their request"
        }
        val totalCount = pages.firstOrNull()?.totalCount ?: 0
        val records = pages.flatMap(WorkspaceReverseUsagePage::occurrences).between(request.after, request.before)
        val page = records.requestPage(request.before, request.limit)
        val hasPrevious = request.after != null || records.size > request.limit || route.hasPrecedingPage
        val hasNext = request.before != null || records.size > request.limit || route.hasFollowingPage
        return WorkspaceReverseUsagePage(
            target = request.target,
            symbolId = request.symbolId,
            totalCount = totalCount,
            occurrences = page,
            previousCursor = page.firstOrNull()?.cursor?.takeIf { hasPrevious },
            nextCursor = page.lastOrNull()?.cursor?.takeIf { hasNext },
        )
    }

    override suspend fun relationshipOccurrences(
        request: WorkspaceRelationshipOccurrenceRequest,
    ): WorkspaceRelationshipOccurrencePage {
        requireTarget(request.target)
        val route =
            loader.route(
                site,
                workspaceRelationshipOccurrenceRouteKey(request.selector),
                request.after,
                request.before,
                request.limit,
            ) ?: throw WorkspaceStaticEvidenceUnavailable()
        val pages = route.entries.mapNotNull(WorkspaceStaticEvidenceEntry::relationshipOccurrences)
        require(pages.all { page -> page.target == request.target && page.selector == request.selector }) {
            "Workspace static relationship occurrences do not match their request"
        }
        val totalCount = pages.firstOrNull()?.totalCount ?: 0
        val records =
            pages.flatMap(WorkspaceRelationshipOccurrencePage::occurrences).between(request.after, request.before)
        val page = records.requestPage(request.before, request.limit)
        val hasPrevious = request.after != null || records.size > request.limit || route.hasPrecedingPage
        val hasNext = request.before != null || records.size > request.limit || route.hasFollowingPage
        return WorkspaceRelationshipOccurrencePage(
            target = request.target,
            selector = request.selector,
            totalCount = totalCount,
            occurrences = page,
            previousCursor = page.firstOrNull()?.cursor?.takeIf { hasPrevious },
            nextCursor = page.lastOrNull()?.cursor?.takeIf { hasNext },
        )
    }

    private fun requireTarget(target: zone.clanker.report.model.WorkspaceEvidenceTarget) {
        require(target.workspaceId == site.summary.workspace.id && target.generationId == site.manifest.generationId) {
            "Workspace static-evidence request does not target the loaded site generation"
        }
    }
}

internal data class LoadedStaticEvidenceRoute(
    val entries: List<WorkspaceStaticEvidenceEntry>,
    val hasPrecedingPage: Boolean = false,
    val hasFollowingPage: Boolean,
)

internal class WorkspaceStaticEvidenceUnavailable :
    IllegalStateException("The static report does not include an exact-evidence catalog")

private data class LocatedStaticEvidencePage(
    val reference: WorkspaceStaticEvidencePageReference,
    val locator: zone.clanker.report.model.WorkspaceStaticEvidenceRouteLocator,
)

private data class ActiveStaticEvidenceCatalog(
    val target: zone.clanker.report.model.WorkspaceEvidenceTarget,
    val file: String,
    val catalog: WorkspaceStaticEvidenceCatalog,
)

private sealed interface CachedStaticEvidenceArtifact

private data class CachedStaticEvidencePartition(
    val value: WorkspaceStaticEvidencePartition,
) : CachedStaticEvidenceArtifact

private data class CachedStaticEvidencePage(
    val value: WorkspaceStaticEvidencePage,
) : CachedStaticEvidenceArtifact

private data class WeightedStaticEvidenceArtifact(
    val encodedByteSize: Long,
    val value: CachedStaticEvidenceArtifact,
)

private class StaticEvidenceArtifactLru(
    private val maxBytes: Long,
) {
    private val entries = linkedMapOf<String, WeightedStaticEvidenceArtifact>()
    private var encodedByteSize: Long = 0

    init {
        require(maxBytes > 0) { "Workspace evidence cache byte limit must be positive" }
    }

    fun partition(file: String): WorkspaceStaticEvidencePartition? =
        access(file)?.let { artifact -> (artifact as? CachedStaticEvidencePartition)?.value }

    fun page(file: String): WorkspaceStaticEvidencePage? =
        access(file)?.let { artifact -> (artifact as? CachedStaticEvidencePage)?.value }

    fun put(
        file: String,
        weight: Long,
        value: CachedStaticEvidenceArtifact,
    ) {
        require(weight >= 0) { "Workspace evidence cache weight must not be negative" }
        entries.remove(file)?.let { previous -> encodedByteSize -= previous.encodedByteSize }
        if (weight > maxBytes) return
        while (entries.isNotEmpty() && encodedByteSize + weight > maxBytes) {
            val eldest = entries.entries.first()
            entries.remove(eldest.key)
            encodedByteSize -= eldest.value.encodedByteSize
        }
        entries[file] = WeightedStaticEvidenceArtifact(weight, value)
        encodedByteSize += weight
    }

    fun clear() {
        entries.clear()
        encodedByteSize = 0
    }

    private fun access(file: String): CachedStaticEvidenceArtifact? {
        val entry = entries.remove(file) ?: return null
        entries[file] = entry
        return entry.value
    }
}

private fun List<LocatedStaticEvidencePage>.startIndex(after: WorkspaceEvidenceCursor?): Int {
    if (after == null) return 0
    val comparator = workspaceEvidenceCursorComparator()
    return indexOfFirst { located ->
        located.locator.lastCursor?.let { last -> comparator.compare(last, after) > 0 } == true
    }.takeIf { index -> index >= 0 } ?: size
}

private fun List<LocatedStaticEvidencePage>.endIndex(before: WorkspaceEvidenceCursor): Int {
    val comparator = workspaceEvidenceCursorComparator()
    return indexOfLast { located ->
        located.locator.firstCursor?.let { first -> comparator.compare(first, before) < 0 } == true
    }
}

private fun WorkspaceStaticEvidenceEntry.occurrencesBetween(
    after: WorkspaceEvidenceCursor?,
    before: WorkspaceEvidenceCursor?,
): Int = (reverseUsages?.occurrences ?: relationshipOccurrences?.occurrences.orEmpty()).between(after, before).size

private fun List<WorkspaceRelationshipOccurrence>.between(
    after: WorkspaceEvidenceCursor?,
    before: WorkspaceEvidenceCursor?,
): List<WorkspaceRelationshipOccurrence> {
    val comparator = workspaceEvidenceCursorComparator()
    return filter { occurrence ->
        (after == null || comparator.compare(occurrence.cursor, after) > 0) &&
            (before == null || comparator.compare(occurrence.cursor, before) < 0)
    }
}

private fun List<WorkspaceRelationshipOccurrence>.requestPage(
    before: WorkspaceEvidenceCursor?,
    limit: Int,
): List<WorkspaceRelationshipOccurrence> = if (before == null) take(limit) else takeLast(limit)

private const val ROUTE_PREFIX_LENGTH: Int = 2
private const val MAX_ROUTE_PAGE_READS: Int = 5
private const val DEFAULT_EVIDENCE_CACHE_BYTES: Long = 2L * 1024 * 1024
