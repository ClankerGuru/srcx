package zone.clanker.gradle.docx.site

import zone.clanker.report.model.WorkspaceSearchCatalog
import zone.clanker.report.model.WorkspaceSearchCatalogReference
import zone.clanker.report.model.WorkspaceSearchEntry
import zone.clanker.report.model.WorkspaceSearchJson
import zone.clanker.report.model.WorkspaceSearchShard
import zone.clanker.report.model.WorkspaceSearchShardReference
import zone.clanker.report.model.workspaceSearchEntryComparator
import zone.clanker.report.model.workspaceSearchShardReferenceComparator
import java.nio.file.Files
import java.nio.file.Path

internal class DocxStaticSearchWriter {
    fun write(
        staging: Path,
        generationId: String,
        site: ProjectedDocxSite,
    ): WorkspaceSearchCatalogReference {
        val projection = DocxStaticSearchProjection.apply(site)
        val shardReferences = writeShards(staging, projection.entries)
        val catalog =
            WorkspaceSearchCatalog(
                generationId = generationId,
                workspaceId = site.workspace.id,
                entryCount = projection.entries.size,
                shards = shardReferences,
            )
        val encoded = WorkspaceSearchJson.encodeCatalog(catalog)
        val bytes = encoded.encodeToByteArray()
        val hash = sourceContentHash(bytes)
        val reference =
            WorkspaceSearchCatalogReference(
                file = WorkspaceSearchCatalogReference.path(hash),
                contentHash = hash,
                encodedByteSize = bytes.size.toLong(),
            )
        writeExact(staging.resolve(reference.file), encoded)
        return reference
    }

    private fun writeShards(
        staging: Path,
        entries: List<WorkspaceSearchEntry>,
    ): List<WorkspaceSearchShardReference> =
        entries
            .flatMap { entry -> entry.routePrefixes().map { prefix -> prefix to entry } }
            .groupBy(Pair<String, WorkspaceSearchEntry>::first, Pair<String, WorkspaceSearchEntry>::second)
            .toSortedMap()
            .flatMap { (prefix, candidates) ->
                candidates
                    .distinctBy(WorkspaceSearchEntry::key)
                    .sortedWith(workspaceSearchEntryComparator())
                    .chunked(WorkspaceSearchShard.MAX_ENTRIES)
                    .mapIndexed { pageIndex, page -> writeShard(staging, prefix, pageIndex, page) }
            }.sortedWith(workspaceSearchShardReferenceComparator())

    private fun writeShard(
        staging: Path,
        prefix: String,
        pageIndex: Int,
        entries: List<WorkspaceSearchEntry>,
    ): WorkspaceSearchShardReference {
        val shard = WorkspaceSearchShard(prefix, pageIndex, entries)
        val encoded = WorkspaceSearchJson.encodeShard(shard)
        val bytes = encoded.encodeToByteArray()
        val hash = sourceContentHash(bytes)
        val reference =
            WorkspaceSearchShardReference(
                prefix = prefix,
                pageIndex = pageIndex,
                file = WorkspaceSearchShardReference.path(hash),
                contentHash = hash,
                encodedByteSize = bytes.size.toLong(),
                entryCount = entries.size,
            )
        writeExact(staging.resolve(reference.file), encoded)
        return reference
    }
}

private fun WorkspaceSearchEntry.routePrefixes(): Set<String> =
    terms.flatMapTo(mutableSetOf()) { term ->
        (1..minOf(term.length, WorkspaceSearchCatalog.PREFIX_LENGTH)).map(term::take)
    }

private fun writeExact(
    destination: Path,
    content: String,
) {
    Files.createDirectories(destination.parent)
    if (Files.exists(destination)) {
        require(Files.readString(destination) == content) {
            "Distinct static-search payloads produced the same content hash"
        }
    } else {
        Files.writeString(destination, content)
    }
}
