package zone.clanker.docx.web.site

import zone.clanker.report.model.WorkspaceSearchCatalog
import zone.clanker.report.model.WorkspaceSearchCategory
import zone.clanker.report.model.WorkspaceSearchEntry
import zone.clanker.report.model.WorkspaceSearchJson
import zone.clanker.report.model.WorkspaceSearchKind
import zone.clanker.report.model.WorkspaceSearchResultGroup
import zone.clanker.report.model.WorkspaceSearchResults
import zone.clanker.report.model.WorkspaceSearchShardReference
import zone.clanker.report.model.workspaceSearchEntryComparator
import zone.clanker.report.model.workspaceSearchTerms

data class LoadedWorkspaceSearchCatalog(
    val catalog: WorkspaceSearchCatalog,
    val encodedByteSize: Long,
) {
    init {
        require(encodedByteSize >= 0) { "Loaded workspace search catalog byte size must not be negative" }
    }
}

internal class WorkspaceStaticSearchLoader(
    private val source: WorkspaceSiteTextSource,
) {
    suspend fun loadCatalog(site: LoadedWorkspaceSite): LoadedWorkspaceSearchCatalog? {
        val reference = site.manifest.searchCatalog ?: return null
        val encoded = source.read(reference.file)
        val encodedByteSize = encoded.utf8EncodedByteSize()
        require(encodedByteSize == reference.encodedByteSize) {
            "Workspace search catalog byte size does not match its manifest reference"
        }
        val catalog = WorkspaceSearchJson.decodeCatalog(encoded)
        require(catalog.generationId == site.manifest.generationId) {
            "Workspace search catalog generation does not match its manifest"
        }
        require(catalog.workspaceId == site.summary.workspace.id) {
            "Workspace search catalog does not belong to its workspace summary"
        }
        return LoadedWorkspaceSearchCatalog(catalog, encodedByteSize)
    }

    suspend fun query(
        site: LoadedWorkspaceSite,
        catalog: WorkspaceSearchCatalog,
        rawQuery: String,
        resultLimitPerGroup: Int,
    ): WorkspaceSearchResults {
        require(resultLimitPerGroup in 1..WorkspaceSearchResultGroup.MAX_RESULTS) {
            "Workspace search per-group result limit is unsupported"
        }
        require(catalog.generationId == site.manifest.generationId) {
            "Workspace search query catalog generation does not match its site"
        }
        require(catalog.workspaceId == site.summary.workspace.id) {
            "Workspace search query catalog does not belong to its site"
        }
        val query = ParsedWorkspaceSearchQuery.parse(rawQuery)
        if (query.terms.isEmpty()) return query.emptyResults()
        val references = catalog.shards.filter { reference -> reference.prefix == query.routingPrefix }
        val selectedReferences = references.take(catalog.maxShardLoads)
        val entries = selectedReferences.flatMap { reference -> loadShard(reference) }
        val matches = entries.filter(query::matches)
        val groups = matches.grouped(query, resultLimitPerGroup)
        val resultLimitTruncated =
            matches.groupingBy(WorkspaceSearchEntry::kind).eachCount().any { (_, count) ->
                count > resultLimitPerGroup
            }
        return WorkspaceSearchResults(
            query = query.raw,
            category = query.category,
            groups = groups,
            loadedShardCount = selectedReferences.size,
            loadedCandidateCount = entries.size,
            loadedMatchCount = matches.size,
            truncated = references.size > selectedReferences.size || resultLimitTruncated,
        )
    }

    private suspend fun loadShard(reference: WorkspaceSearchShardReference): List<WorkspaceSearchEntry> {
        val encoded = source.read(reference.file)
        require(encoded.utf8EncodedByteSize() == reference.encodedByteSize) {
            "Workspace search shard byte size does not match its catalog reference"
        }
        return WorkspaceSearchJson
            .decodeShard(encoded)
            .also { shard ->
                require(shard.prefix == reference.prefix && shard.pageIndex == reference.pageIndex) {
                    "Workspace search shard identity does not match its catalog reference"
                }
                require(shard.entries.size == reference.entryCount) {
                    "Workspace search shard count does not match its catalog reference"
                }
            }.entries
    }
}

private data class ParsedWorkspaceSearchQuery(
    val raw: String,
    val category: WorkspaceSearchCategory,
    val terms: List<String>,
) {
    val routingPrefix: String =
        terms
            .sortedWith(compareByDescending<String>(String::length).thenBy { term -> term })
            .firstOrNull()
            ?.take(WorkspaceSearchCatalog.PREFIX_LENGTH)
            .orEmpty()

    fun matches(entry: WorkspaceSearchEntry): Boolean =
        entry.matchesCategory(category) &&
            if (category == WorkspaceSearchCategory.EXTENSION) {
                terms.all { term -> entry.target.extension?.startsWith(term) == true }
            } else {
                terms.all { term -> entry.terms.any { candidate -> candidate.startsWith(term) } }
            }

    fun emptyResults(): WorkspaceSearchResults =
        WorkspaceSearchResults(
            query = raw,
            category = category,
            groups = emptyList(),
            loadedShardCount = 0,
            loadedCandidateCount = 0,
            loadedMatchCount = 0,
            truncated = false,
        )

    companion object {
        fun parse(rawQuery: String): ParsedWorkspaceSearchQuery {
            val raw = rawQuery.trim()
            val requestedPrefix = raw.substringBefore(':', "").lowercase()
            val category = SEARCH_CATEGORIES[requestedPrefix] ?: WorkspaceSearchCategory.ALL
            val body = if (category == WorkspaceSearchCategory.ALL) raw else raw.substringAfter(':').trim()
            return ParsedWorkspaceSearchQuery(raw, category, workspaceSearchTerms(body))
        }
    }
}

private fun WorkspaceSearchEntry.matchesCategory(category: WorkspaceSearchCategory): Boolean =
    when (category) {
        WorkspaceSearchCategory.ALL -> true
        WorkspaceSearchCategory.BUILD -> kind == WorkspaceSearchKind.BUILD
        WorkspaceSearchCategory.PROJECT -> kind == WorkspaceSearchKind.PROJECT
        WorkspaceSearchCategory.SOURCE_SET -> kind == WorkspaceSearchKind.SOURCE_SET
        WorkspaceSearchCategory.PACKAGE -> kind == WorkspaceSearchKind.PACKAGE
        WorkspaceSearchCategory.FILE,
        WorkspaceSearchCategory.EXTENSION,
        -> kind == WorkspaceSearchKind.FILE
        WorkspaceSearchCategory.CLASS ->
            kind in
                setOf(
                    WorkspaceSearchKind.CLASS,
                    WorkspaceSearchKind.INTERFACE,
                    WorkspaceSearchKind.OBJECT,
                    WorkspaceSearchKind.ENUM,
                )
        WorkspaceSearchCategory.SYMBOL ->
            kind in
                setOf(
                    WorkspaceSearchKind.CLASS,
                    WorkspaceSearchKind.INTERFACE,
                    WorkspaceSearchKind.OBJECT,
                    WorkspaceSearchKind.ENUM,
                    WorkspaceSearchKind.FUNCTION,
                    WorkspaceSearchKind.PROPERTY,
                )
        WorkspaceSearchCategory.METHOD -> kind == WorkspaceSearchKind.FUNCTION
        WorkspaceSearchCategory.PROBLEM -> kind == WorkspaceSearchKind.FINDING
        WorkspaceSearchCategory.CYCLE -> kind == WorkspaceSearchKind.CYCLE
    }

private fun List<WorkspaceSearchEntry>.grouped(
    query: ParsedWorkspaceSearchQuery,
    resultLimitPerGroup: Int,
): List<WorkspaceSearchResultGroup> =
    groupBy(WorkspaceSearchEntry::kind).let { candidatesByKind ->
        WorkspaceSearchKind.entries.mapNotNull { kind ->
            val candidates = candidatesByKind[kind] ?: return@mapNotNull null
            WorkspaceSearchResultGroup(
                kind = kind,
                results =
                    candidates
                        .sortedWith(query.hitComparator())
                        .take(resultLimitPerGroup),
            )
        }
    }

private fun ParsedWorkspaceSearchQuery.hitComparator(): Comparator<WorkspaceSearchEntry> =
    compareBy<WorkspaceSearchEntry> { entry ->
        val labelTerms = workspaceSearchTerms(entry.label)
        when {
            terms.all(entry.terms::contains) -> 0
            terms.all { term -> labelTerms.any { label -> label.startsWith(term) } } -> 1
            else -> 2
        }
    }.then(workspaceSearchEntryComparator())

private val SEARCH_CATEGORIES: Map<String, WorkspaceSearchCategory> =
    mapOf(
        "build" to WorkspaceSearchCategory.BUILD,
        "project" to WorkspaceSearchCategory.PROJECT,
        "source" to WorkspaceSearchCategory.SOURCE_SET,
        "package" to WorkspaceSearchCategory.PACKAGE,
        "file" to WorkspaceSearchCategory.FILE,
        "class" to WorkspaceSearchCategory.CLASS,
        "symbol" to WorkspaceSearchCategory.SYMBOL,
        "method" to WorkspaceSearchCategory.METHOD,
        "ext" to WorkspaceSearchCategory.EXTENSION,
        "problem" to WorkspaceSearchCategory.PROBLEM,
        "cycle" to WorkspaceSearchCategory.CYCLE,
    )
