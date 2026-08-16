package zone.clanker.report.model

import kotlinx.serialization.Serializable

@Serializable
data class WorkspaceStaticEvidenceCatalogReference(
    val file: String,
    val contentHash: String,
    val encodedByteSize: Long,
) {
    init {
        requireEvidenceHash(contentHash)
        requireNormalizedRelativePath(file, "Workspace evidence catalog")
        require(file == path(contentHash)) { "Workspace evidence catalog path must be derived from its hash" }
        require(encodedByteSize >= 0) { "Workspace evidence catalog byte size must not be negative" }
    }

    companion object {
        fun path(contentHash: String): String = "data/evidence/catalog-$contentHash.json"
    }
}

/** Constant-size top-level router; each lookup loads only its two-character hash partition. */
@Serializable
data class WorkspaceStaticEvidenceCatalog(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val target: WorkspaceEvidenceTarget,
    val partitions: List<WorkspaceStaticEvidencePartitionReference>,
) {
    init {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) {
            "Unsupported workspace static-evidence schema: $schemaVersion"
        }
        require(partitions.size <= MAX_PARTITIONS) { "Workspace evidence catalog has too many partitions" }
        require(partitions == partitions.sortedBy(WorkspaceStaticEvidencePartitionReference::prefix)) {
            "Workspace evidence partitions must be deterministic"
        }
        require(partitions.map(WorkspaceStaticEvidencePartitionReference::prefix).distinct().size == partitions.size) {
            "Workspace evidence partition prefixes must be unique"
        }
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1
        const val ROUTE_PREFIX_LENGTH: Int = 2
        const val MAX_PARTITIONS: Int = 256
    }
}

@Serializable
data class WorkspaceStaticEvidencePartitionReference(
    val prefix: String,
    val file: String,
    val contentHash: String,
    val encodedByteSize: Long,
    val routePageCount: Int,
) {
    init {
        requireEvidencePrefix(prefix)
        requireEvidenceHash(contentHash)
        requireNormalizedRelativePath(file, "Workspace evidence partition")
        require(file == path(contentHash)) { "Workspace evidence partition path must be derived from its hash" }
        require(encodedByteSize >= 0) { "Workspace evidence partition byte size must not be negative" }
        require(routePageCount > 0) { "Workspace evidence partitions must reference route pages" }
    }

    companion object {
        fun path(contentHash: String): String = "data/evidence/partitions/$contentHash.json"
    }
}

@Serializable
data class WorkspaceStaticEvidencePartition(
    val prefix: String,
    val pages: List<WorkspaceStaticEvidencePageReference>,
) {
    init {
        requireEvidencePrefix(prefix)
        require(pages.isNotEmpty()) { "Workspace evidence partitions must not be empty" }
        require(pages == pages.sortedWith(workspaceStaticEvidencePageReferenceComparator())) {
            "Workspace evidence page references must be deterministic"
        }
        val locators = pages.flatMap(WorkspaceStaticEvidencePageReference::routes)
        require(locators.map { locator -> locator.routeKey to locator.pageIndex }.distinct().size == locators.size) {
            "Workspace evidence route pages must be unique"
        }
        require(locators.all { locator -> locator.routeHash.startsWith(prefix) }) {
            "Workspace evidence routes must match their partition prefix"
        }
    }
}

@Serializable
data class WorkspaceStaticEvidencePageReference(
    val file: String,
    val contentHash: String,
    val encodedByteSize: Long,
    val routes: List<WorkspaceStaticEvidenceRouteLocator>,
) {
    init {
        requireEvidenceHash(contentHash)
        requireNormalizedRelativePath(file, "Workspace evidence page")
        require(file == path(contentHash)) { "Workspace evidence page path must be derived from its hash" }
        require(encodedByteSize >= 0) { "Workspace evidence page byte size must not be negative" }
        require(routes.isNotEmpty()) { "Workspace evidence pages must route at least one entry" }
        require(routes.size <= WorkspaceStaticEvidencePage.MAX_ENTRIES) {
            "Workspace evidence page route count must remain bounded"
        }
        require(routes == routes.sortedWith(workspaceStaticEvidenceRouteLocatorComparator())) {
            "Workspace evidence page routes must be deterministic"
        }
    }

    companion object {
        fun path(contentHash: String): String = "data/evidence/pages/$contentHash.json"
    }
}

@Serializable
data class WorkspaceStaticEvidenceRouteLocator(
    val routeKey: String,
    val routeHash: String,
    val pageIndex: Int,
    val firstCursor: WorkspaceEvidenceCursor? = null,
    val lastCursor: WorkspaceEvidenceCursor? = null,
) {
    init {
        require(routeKey.isNotBlank()) { "Workspace evidence route key must not be blank" }
        requireRouteHash(routeHash)
        require(routeHash == workspaceEvidenceRouteHash(routeKey)) {
            "Workspace evidence route hash must match its key"
        }
        require(pageIndex >= 0) { "Workspace evidence route page index must not be negative" }
        require((firstCursor == null) == (lastCursor == null)) {
            "Workspace evidence route cursor bounds must both be present or absent"
        }
        require(
            firstCursor == null ||
                workspaceEvidenceCursorComparator().compare(firstCursor, requireNotNull(lastCursor)) <= 0,
        ) { "Workspace evidence route cursor bounds must be ordered" }
    }
}

@Serializable
data class WorkspaceStaticEvidencePage(
    val entries: List<WorkspaceStaticEvidenceEntry>,
) {
    init {
        require(entries.isNotEmpty()) { "Workspace evidence pages must not be empty" }
        require(entries.size <= MAX_ENTRIES) { "Workspace evidence page entry count must remain bounded" }
        require(entries.sumOf(WorkspaceStaticEvidenceEntry::weight) <= MAX_RECORDS) {
            "Workspace evidence page record count must remain bounded"
        }
        require(entries == entries.sortedWith(workspaceStaticEvidenceEntryComparator())) {
            "Workspace evidence page entries must be deterministic"
        }
    }

    companion object {
        const val MAX_ENTRIES: Int = 100
        const val MAX_RECORDS: Int = 100
    }
}

@Serializable
data class WorkspaceStaticEvidenceEntry(
    val routeKey: String,
    val routeHash: String = workspaceEvidenceRouteHash(routeKey),
    val pageIndex: Int,
    val declaration: WorkspaceDeclarationEvidencePage? = null,
    val reverseUsages: WorkspaceReverseUsagePage? = null,
    val relationshipOccurrences: WorkspaceRelationshipOccurrencePage? = null,
) {
    init {
        require(routeKey.isNotBlank()) { "Workspace evidence entry route key must not be blank" }
        requireRouteHash(routeHash)
        require(routeHash == workspaceEvidenceRouteHash(routeKey)) {
            "Workspace evidence entry route hash must match its key"
        }
        require(pageIndex >= 0) { "Workspace evidence entry page index must not be negative" }
        require(listOfNotNull(declaration, reverseUsages, relationshipOccurrences).size == 1) {
            "Workspace evidence entries must contain exactly one typed page"
        }
        require(declaration == null || pageIndex == 0) { "Workspace declarations fit exactly one page" }
        require(routeKey == typedRouteKey()) { "Workspace evidence entry route key must match its typed page" }
    }

    val weight: Int
        get() = declaration?.let { 1 } ?: reverseUsages?.occurrences?.size ?: relationshipOccurrences!!.occurrences.size

    fun locator(): WorkspaceStaticEvidenceRouteLocator =
        WorkspaceStaticEvidenceRouteLocator(
            routeKey = routeKey,
            routeHash = routeHash,
            pageIndex = pageIndex,
            firstCursor = occurrences().firstOrNull()?.cursor,
            lastCursor = occurrences().lastOrNull()?.cursor,
        )

    private fun typedRouteKey(): String =
        declaration?.let { page -> workspaceDeclarationEvidenceRouteKey(page.symbolId) }
            ?: reverseUsages?.let { page -> workspaceReverseUsageRouteKey(page.symbolId) }
            ?: workspaceRelationshipOccurrenceRouteKey(requireNotNull(relationshipOccurrences).selector)

    private fun occurrences(): List<WorkspaceRelationshipOccurrence> =
        reverseUsages?.occurrences ?: relationshipOccurrences?.occurrences.orEmpty()
}

fun workspaceDeclarationEvidenceRouteKey(symbolId: String): String = routeKey("declaration", symbolId)

fun workspaceReverseUsageRouteKey(symbolId: String): String = routeKey("reverse", symbolId)

fun workspaceRelationshipOccurrenceRouteKey(selector: WorkspaceRelationshipOccurrenceSelector): String =
    routeKey("occurrence:${selector.kind.name}", selector.sourceNodeId, selector.targetNodeId)

/** FNV-1a is used only for deterministic routing; the content files retain cryptographic hashes. */
fun workspaceEvidenceRouteHash(routeKey: String): String {
    require(routeKey.isNotBlank()) { "Workspace evidence route key must not be blank" }
    var hash = FNV_OFFSET_BASIS
    routeKey.encodeToByteArray().forEach { byte ->
        hash = (hash xor byte.toUByte().toULong()) * FNV_PRIME
    }
    return hash.toString(RADIX_HEXADECIMAL).padStart(ROUTE_HASH_LENGTH, '0')
}

fun workspaceStaticEvidencePageReferenceComparator(): Comparator<WorkspaceStaticEvidencePageReference> =
    compareBy(
        { reference -> reference.routes.first().routeHash },
        { reference -> reference.routes.first().routeKey },
        { reference -> reference.routes.first().pageIndex },
        WorkspaceStaticEvidencePageReference::file,
    )

fun workspaceStaticEvidenceRouteLocatorComparator(): Comparator<WorkspaceStaticEvidenceRouteLocator> =
    compareBy(
        WorkspaceStaticEvidenceRouteLocator::routeHash,
        WorkspaceStaticEvidenceRouteLocator::routeKey,
        WorkspaceStaticEvidenceRouteLocator::pageIndex,
    )

fun workspaceStaticEvidenceEntryComparator(): Comparator<WorkspaceStaticEvidenceEntry> =
    compareBy(
        WorkspaceStaticEvidenceEntry::routeHash,
        WorkspaceStaticEvidenceEntry::routeKey,
        WorkspaceStaticEvidenceEntry::pageIndex,
    )

private fun routeKey(
    type: String,
    vararg ids: String,
): String = "$type:${ids.joinToString(":") { id -> "${id.length}:$id" }}"

private fun requireEvidenceHash(value: String) {
    require(value.length == CONTENT_HASH_LENGTH && value.all { character -> character in HEXADECIMAL_CHARACTERS }) {
        "Workspace evidence hash must be lowercase hexadecimal"
    }
}

private fun requireEvidencePrefix(value: String) {
    require(
        value.length == WorkspaceStaticEvidenceCatalog.ROUTE_PREFIX_LENGTH &&
            value.all { character -> character in HEXADECIMAL_CHARACTERS },
    ) { "Workspace evidence partition prefix must be lowercase hexadecimal" }
}

private fun requireRouteHash(value: String) {
    require(value.length == ROUTE_HASH_LENGTH && value.all { character -> character in HEXADECIMAL_CHARACTERS }) {
        "Workspace evidence route hash must be lowercase hexadecimal"
    }
}

private const val CONTENT_HASH_LENGTH: Int = 64
private const val ROUTE_HASH_LENGTH: Int = 16
private const val RADIX_HEXADECIMAL: Int = 16
private const val HEXADECIMAL_CHARACTERS: String = "0123456789abcdef"
private const val FNV_OFFSET_BASIS: ULong = 14_695_981_039_346_656_037uL
private const val FNV_PRIME: ULong = 1_099_511_628_211uL
