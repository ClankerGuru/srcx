package zone.clanker.docx.web.fixture

import zone.clanker.report.model.WorkspaceSearchBadge
import zone.clanker.report.model.WorkspaceSearchBadgeKind
import zone.clanker.report.model.WorkspaceSearchCatalog
import zone.clanker.report.model.WorkspaceSearchCatalogReference
import zone.clanker.report.model.WorkspaceSearchEntry
import zone.clanker.report.model.WorkspaceSearchJson
import zone.clanker.report.model.WorkspaceSearchKind
import zone.clanker.report.model.WorkspaceSearchLocation
import zone.clanker.report.model.WorkspaceSearchShard
import zone.clanker.report.model.WorkspaceSearchShardReference
import zone.clanker.report.model.WorkspaceSearchTarget
import zone.clanker.report.model.workspaceSearchEntryComparator
import zone.clanker.report.model.workspaceSearchShardReferenceComparator
import zone.clanker.report.model.workspaceSearchTerms
import java.security.MessageDigest

internal data class DocxWebFixtureSearch(
    val catalogReference: WorkspaceSearchCatalogReference,
    val content: Map<String, String>,
)

internal fun fixtureSearch(): DocxWebFixtureSearch {
    val entries = fixtureSearchEntries().sortedWith(workspaceSearchEntryComparator())
    val shards = fixtureSearchShards(entries)
    val catalog =
        WorkspaceSearchCatalog(
            generationId = "fixture-generation",
            workspaceId = fixtureWorkspaceIdentity.id,
            entryCount = entries.size,
            shards = shards.map(FixtureSearchShard::reference).sortedWith(workspaceSearchShardReferenceComparator()),
        )
    val encodedCatalog = WorkspaceSearchJson.encodeCatalog(catalog)
    val catalogHash = encodedCatalog.sha256()
    val catalogReference =
        WorkspaceSearchCatalogReference(
            file = WorkspaceSearchCatalogReference.path(catalogHash),
            contentHash = catalogHash,
            encodedByteSize = encodedCatalog.encodeToByteArray().size.toLong(),
        )
    return DocxWebFixtureSearch(
        catalogReference = catalogReference,
        content =
            buildMap {
                put(catalogReference.file, encodedCatalog)
                shards.forEach { shard -> put(shard.reference.file, shard.encoded) }
            },
    )
}

private data class FixtureSearchShard(
    val reference: WorkspaceSearchShardReference,
    val encoded: String,
)

private fun fixtureSearchShards(entries: List<WorkspaceSearchEntry>): List<FixtureSearchShard> =
    entries
        .flatMap { entry -> entry.routePrefixes().map { prefix -> prefix to entry } }
        .groupBy(Pair<String, WorkspaceSearchEntry>::first, Pair<String, WorkspaceSearchEntry>::second)
        .toSortedMap()
        .flatMap { (prefix, candidates) ->
            candidates
                .distinctBy(WorkspaceSearchEntry::key)
                .sortedWith(workspaceSearchEntryComparator())
                .chunked(WorkspaceSearchShard.MAX_ENTRIES)
                .mapIndexed { pageIndex, page -> fixtureSearchShard(prefix, pageIndex, page) }
        }

private fun fixtureSearchShard(
    prefix: String,
    pageIndex: Int,
    entries: List<WorkspaceSearchEntry>,
): FixtureSearchShard {
    val encoded = WorkspaceSearchJson.encodeShard(WorkspaceSearchShard(prefix, pageIndex, entries))
    val hash = encoded.sha256()
    return FixtureSearchShard(
        reference =
            WorkspaceSearchShardReference(
                prefix = prefix,
                pageIndex = pageIndex,
                file = WorkspaceSearchShardReference.path(hash),
                contentHash = hash,
                encodedByteSize = encoded.encodeToByteArray().size.toLong(),
                entryCount = entries.size,
            ),
        encoded = encoded,
    )
}

private fun fixtureSearchEntries(): List<WorkspaceSearchEntry> =
    listOf(
        fixtureClassSearchEntry(
            id = "symbol:fixture:consumer",
            label = "Consumer",
            fileId = "file:fixture:consumer",
            badges =
                listOf(
                    WorkspaceSearchBadge(WorkspaceSearchBadgeKind.RELATIONSHIPS, 1),
                    WorkspaceSearchBadge(WorkspaceSearchBadgeKind.PROBLEMS, 1),
                ),
        ),
        fixtureClassSearchEntry(
            id = "symbol:fixture:target",
            label = "Target",
            fileId = "file:fixture:target",
            badges = listOf(WorkspaceSearchBadge(WorkspaceSearchBadgeKind.RELATIONSHIPS, 1)),
        ),
    )

private fun fixtureClassSearchEntry(
    id: String,
    label: String,
    fileId: String,
    badges: List<WorkspaceSearchBadge>,
): WorkspaceSearchEntry =
    WorkspaceSearchEntry(
        id = id,
        kind = WorkspaceSearchKind.CLASS,
        label = label,
        detail = "fixture / :app / main",
        terms = workspaceSearchTerms(label, "fixture.$label", "fixture"),
        target =
            WorkspaceSearchTarget(
                WorkspaceSearchLocation(
                    buildId = "build:fixture",
                    projectId = "project:fixture:app",
                    sourceSetId = "source-set:fixture:main",
                    fileId = fileId,
                    line = 3,
                ),
            ),
        badges = badges,
    )

private fun WorkspaceSearchEntry.routePrefixes(): Set<String> =
    terms.flatMapTo(mutableSetOf()) { term ->
        (1..minOf(term.length, WorkspaceSearchCatalog.PREFIX_LENGTH)).map(term::take)
    }

private fun String.sha256(): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(encodeToByteArray())
        .joinToString("") { byte -> "%02x".format(byte) }
