package zone.clanker.report.model

import kotlinx.serialization.Serializable

/** Content-addressed pointer to the small generation-specific search catalog. */
@Serializable
data class WorkspaceSearchCatalogReference(
    val file: String,
    val contentHash: String,
    val encodedByteSize: Long,
) {
    init {
        requireSearchHash(contentHash)
        requireNormalizedRelativePath(file, "Workspace search catalog")
        require(file == path(contentHash)) { "Workspace search catalog path must be derived from its hash" }
        require(encodedByteSize >= 0) { "Workspace search catalog byte size must not be negative" }
    }

    companion object {
        fun path(contentHash: String): String = "data/search/catalog-$contentHash.json"
    }
}

/** One bounded page for a one- or two-character normalized token prefix. */
@Serializable
data class WorkspaceSearchShardReference(
    val prefix: String,
    val pageIndex: Int,
    val file: String,
    val contentHash: String,
    val encodedByteSize: Long,
    val entryCount: Int,
) {
    init {
        requireSearchPrefix(prefix)
        require(pageIndex >= 0) { "Workspace search page index must not be negative" }
        requireSearchHash(contentHash)
        requireNormalizedRelativePath(file, "Workspace search shard")
        require(file == path(contentHash)) { "Workspace search shard path must be derived from its hash" }
        require(encodedByteSize >= 0) { "Workspace search shard byte size must not be negative" }
        require(entryCount in 1..WorkspaceSearchShard.MAX_ENTRIES) {
            "Workspace search shard entry count must be bounded"
        }
    }

    companion object {
        fun path(contentHash: String): String = "data/search/shards/$contentHash.json"
    }
}

/** Compact routing catalog; query execution follows at most [maxShardLoads] references. */
@Serializable
data class WorkspaceSearchCatalog(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val generationId: String,
    val workspaceId: String,
    val entryCount: Int,
    val prefixLength: Int = PREFIX_LENGTH,
    val maxShardLoads: Int = MAX_SHARD_LOADS,
    val shards: List<WorkspaceSearchShardReference>,
) {
    init {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) { "Unsupported workspace search schema: $schemaVersion" }
        require(generationId.isNotBlank()) { "Workspace search generation must not be blank" }
        requireValidId(workspaceId, "Workspace search workspace")
        require(entryCount >= 0) { "Workspace search entry count must not be negative" }
        require(prefixLength == PREFIX_LENGTH) { "Unsupported workspace search prefix length: $prefixLength" }
        require(maxShardLoads in 1..MAX_SHARD_LOADS) { "Workspace search shard-load limit is unsupported" }
        require(shards == shards.sortedWith(workspaceSearchShardReferenceComparator())) {
            "Workspace search shard references must be sorted by prefix and page"
        }
        require(shards.map { reference -> reference.prefix to reference.pageIndex }.distinct().size == shards.size) {
            "Workspace search shard prefix pages must be unique"
        }
        require(
            shards
                .groupBy(WorkspaceSearchShardReference::prefix)
                .values
                .all { references ->
                    references.map(WorkspaceSearchShardReference::pageIndex) == references.indices.toList()
                },
        ) { "Workspace search shard pages must be contiguous" }
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1
        const val PREFIX_LENGTH: Int = 2
        const val MAX_SHARD_LOADS: Int = 4
    }
}

/** A bounded candidate page. Source bodies and graph relationships are intentionally absent. */
@Serializable
data class WorkspaceSearchShard(
    val prefix: String,
    val pageIndex: Int,
    val entries: List<WorkspaceSearchEntry>,
) {
    init {
        requireSearchPrefix(prefix)
        require(pageIndex >= 0) { "Workspace search page index must not be negative" }
        require(entries.isNotEmpty()) { "Workspace search shards must not be empty" }
        require(entries.size <= MAX_ENTRIES) { "Workspace search shards must remain bounded" }
        require(entries.map(WorkspaceSearchEntry::key).distinct().size == entries.size) {
            "Workspace search shard entries must be unique"
        }
        require(entries == entries.sortedWith(workspaceSearchEntryComparator())) {
            "Workspace search shard entries must be deterministic"
        }
        require(entries.all { entry -> entry.terms.any { term -> term.startsWith(prefix) } }) {
            "Workspace search shard entries must match their routed prefix"
        }
    }

    companion object {
        const val MAX_ENTRIES: Int = 256
    }
}

@Serializable
data class WorkspaceSearchEntry(
    val id: String,
    val kind: WorkspaceSearchKind,
    val label: String,
    val detail: String,
    val terms: List<String>,
    val target: WorkspaceSearchTarget = WorkspaceSearchTarget(),
    val badges: List<WorkspaceSearchBadge> = emptyList(),
) {
    init {
        requireValidId(id, "Workspace search entry")
        require(label.isNotBlank()) { "Workspace search label must not be blank" }
        require(detail.isNotBlank()) { "Workspace search detail must not be blank" }
        require(terms.isNotEmpty()) { "Workspace search entries require normalized terms" }
        requireDistinctAndSorted(terms, "Workspace search terms")
        require(terms.all(::isNormalizedSearchTerm)) { "Workspace search terms must be normalized" }
        require(kind == WorkspaceSearchKind.FILE || target.extension == null) {
            "Only file search entries may carry an extension"
        }
        require(badges.map(WorkspaceSearchBadge::kind).distinct().size == badges.size) {
            "Workspace search badges must be unique by kind"
        }
        require(badges == badges.sortedBy { badge -> badge.kind.ordinal }) {
            "Workspace search badges must be deterministic"
        }
    }

    val key: String
        get() = "${kind.name}:$id"
}

@Serializable
data class WorkspaceSearchTarget(
    val location: WorkspaceSearchLocation = WorkspaceSearchLocation(),
    val extension: String? = null,
) {
    init {
        extension?.let { value ->
            require(isNormalizedSearchTerm(value)) { "Workspace search extension must be normalized" }
        }
    }
}

@Serializable
data class WorkspaceSearchLocation(
    val buildId: String? = null,
    val projectId: String? = null,
    val sourceSetId: String? = null,
    val fileId: String? = null,
    val line: Int? = null,
) {
    init {
        buildId?.let { requireValidId(it, "Workspace search build") }
        projectId?.let { requireValidId(it, "Workspace search project") }
        sourceSetId?.let { requireValidId(it, "Workspace search source set") }
        fileId?.let { requireValidId(it, "Workspace search file") }
        requireOptionalLine(line, "Workspace search line")
        require(sourceSetId == null || projectId != null) {
            "Workspace search source-set targets require a project"
        }
        require(fileId == null || projectId != null) { "Workspace search file targets require a project" }
        require(line == null || fileId != null) { "Workspace search line targets require a file" }
        require(projectId == null || buildId != null) {
            "Workspace search project targets require a build"
        }
    }
}

@Serializable
enum class WorkspaceSearchKind {
    BUILD,
    PROJECT,
    SOURCE_SET,
    PACKAGE,
    FILE,
    CLASS,
    INTERFACE,
    OBJECT,
    ENUM,
    FUNCTION,
    PROPERTY,
    FINDING,
    CYCLE,
}

@Serializable
enum class WorkspaceSearchCategory {
    ALL,
    BUILD,
    PROJECT,
    SOURCE_SET,
    PACKAGE,
    FILE,
    CLASS,
    SYMBOL,
    METHOD,
    EXTENSION,
    PROBLEM,
    CYCLE,
}

@Serializable
data class WorkspaceSearchResultGroup(
    val kind: WorkspaceSearchKind,
    val results: List<WorkspaceSearchEntry>,
) {
    init {
        require(results.isNotEmpty()) { "Workspace search result groups must not be empty" }
        require(results.size <= MAX_RESULTS) { "Workspace search result groups must remain bounded" }
        require(results.all { result -> result.kind == kind }) { "Workspace search groups must contain one kind" }
    }

    companion object {
        const val MAX_RESULTS: Int = 20
    }
}

@Serializable
data class WorkspaceSearchResults(
    val query: String,
    val category: WorkspaceSearchCategory,
    val groups: List<WorkspaceSearchResultGroup>,
    val loadedShardCount: Int,
    val loadedCandidateCount: Int,
    val loadedMatchCount: Int,
    val truncated: Boolean,
) {
    init {
        require(groups.map(WorkspaceSearchResultGroup::kind).distinct().size == groups.size) {
            "Workspace search result groups must be unique"
        }
        require(groups == groups.sortedBy { group -> group.kind.ordinal }) {
            "Workspace search result groups must use kind order"
        }
        require(loadedShardCount in 0..WorkspaceSearchCatalog.MAX_SHARD_LOADS) {
            "Workspace search loaded too many shards"
        }
        require(loadedCandidateCount >= 0) { "Workspace search candidate count must not be negative" }
        require(loadedMatchCount >= groups.sumOf { group -> group.results.size }) {
            "Workspace search match count must cover its grouped results"
        }
    }
}

fun workspaceSearchTerms(vararg values: String): List<String> =
    buildSet {
        values.forEach { value ->
            val words = searchWords(value)
            addAll(words)
            if (value.none(Char::isWhitespace)) {
                words.joinToString("").takeIf(String::isNotBlank)?.let(::add)
            }
        }
    }.sorted()

fun workspaceSearchEntryComparator(): Comparator<WorkspaceSearchEntry> =
    compareBy<WorkspaceSearchEntry> { entry -> entry.kind.ordinal }
        .thenBy { entry -> entry.label.lowercase() }
        .thenBy(WorkspaceSearchEntry::id)

fun workspaceSearchShardReferenceComparator(): Comparator<WorkspaceSearchShardReference> =
    compareBy(WorkspaceSearchShardReference::prefix, WorkspaceSearchShardReference::pageIndex)

private fun searchWords(value: String): List<String> {
    val words = mutableListOf<String>()
    val current = StringBuilder()
    var previous: Char? = null

    fun flush() {
        if (current.isNotEmpty()) {
            words += current.toString()
            current.clear()
        }
    }

    value.forEach { character ->
        if (!character.isLetterOrDigit()) {
            flush()
            previous = null
        } else {
            if (current.isNotEmpty() && character.isUpperCase() && previous?.isLowerCase() == true) flush()
            current.append(character.lowercaseChar())
            previous = character
        }
    }
    flush()
    return words
}

private fun requireSearchPrefix(prefix: String) {
    require(prefix.length in 1..WorkspaceSearchCatalog.PREFIX_LENGTH && isNormalizedSearchTerm(prefix)) {
        "Workspace search prefixes must be one or two normalized characters"
    }
}

private fun requireSearchHash(contentHash: String) {
    require(SEARCH_HASH.matches(contentHash)) { "Workspace search content hash must be lowercase SHA-256" }
}

private fun isNormalizedSearchTerm(value: String): Boolean =
    value.isNotBlank() && value == value.lowercase() && value.all(Char::isLetterOrDigit)

private val SEARCH_HASH: Regex = Regex("[0-9a-f]{64}")
