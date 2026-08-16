package zone.clanker.gradle.docx.site

import zone.clanker.report.model.WorkspaceEvidenceJson
import zone.clanker.report.model.WorkspaceStaticEvidenceCatalog
import zone.clanker.report.model.WorkspaceStaticEvidenceCatalogReference
import zone.clanker.report.model.WorkspaceStaticEvidenceEntry
import zone.clanker.report.model.WorkspaceStaticEvidencePage
import zone.clanker.report.model.WorkspaceStaticEvidencePageReference
import zone.clanker.report.model.WorkspaceStaticEvidencePartition
import zone.clanker.report.model.WorkspaceStaticEvidencePartitionReference
import zone.clanker.report.model.workspaceStaticEvidenceEntryComparator
import zone.clanker.report.model.workspaceStaticEvidencePageReferenceComparator
import java.nio.file.Files
import java.nio.file.Path

internal class DocxStaticEvidenceWriter {
    fun write(
        staging: Path,
        generationId: String,
        site: ProjectedDocxSite,
    ): WorkspaceStaticEvidenceCatalogReference {
        val projection = DocxStaticEvidenceProjection.apply(site, generationId)
        val partitions = writePartitions(staging, projection.entries)
        val catalog =
            WorkspaceStaticEvidenceCatalog(
                target =
                    zone.clanker.report.model
                        .WorkspaceEvidenceTarget(site.workspace.id, generationId),
                partitions = partitions,
            )
        return writeCatalog(staging, catalog)
    }

    private fun writePartitions(
        staging: Path,
        entries: List<WorkspaceStaticEvidenceEntry>,
    ): List<WorkspaceStaticEvidencePartitionReference> =
        entries
            .groupBy { entry -> entry.routeHash.take(WorkspaceStaticEvidenceCatalog.ROUTE_PREFIX_LENGTH) }
            .toSortedMap()
            .map { (prefix, partitionEntries) ->
                val pageReferences =
                    packPages(partitionEntries.sortedWith(workspaceStaticEvidenceEntryComparator()))
                        .map { page -> writePage(staging, page) }
                        .sortedWith(workspaceStaticEvidencePageReferenceComparator())
                val partition = WorkspaceStaticEvidencePartition(prefix, pageReferences)
                val encoded = WorkspaceEvidenceJson.encodeStaticPartition(partition)
                val bytes = encoded.encodeToByteArray()
                val hash = sourceContentHash(bytes)
                WorkspaceStaticEvidencePartitionReference(
                    prefix = prefix,
                    file = WorkspaceStaticEvidencePartitionReference.path(hash),
                    contentHash = hash,
                    encodedByteSize = bytes.size.toLong(),
                    routePageCount = pageReferences.sumOf { reference -> reference.routes.size },
                ).also { reference -> writeEvidenceExact(staging.resolve(reference.file), encoded) }
            }

    private fun packPages(entries: List<WorkspaceStaticEvidenceEntry>): List<WorkspaceStaticEvidencePage> {
        val pages = mutableListOf<WorkspaceStaticEvidencePage>()
        var current = mutableListOf<WorkspaceStaticEvidenceEntry>()
        var currentWeight = 0
        entries.forEach { entry ->
            val full =
                current.size == WorkspaceStaticEvidencePage.MAX_ENTRIES ||
                    currentWeight + entry.weight > WorkspaceStaticEvidencePage.MAX_RECORDS
            if (full) {
                pages += WorkspaceStaticEvidencePage(current)
                current = mutableListOf()
                currentWeight = 0
            }
            current += entry
            currentWeight += entry.weight
        }
        if (current.isNotEmpty()) pages += WorkspaceStaticEvidencePage(current)
        return pages
    }

    private fun writePage(
        staging: Path,
        page: WorkspaceStaticEvidencePage,
    ): WorkspaceStaticEvidencePageReference {
        val encoded = WorkspaceEvidenceJson.encodeStaticPage(page)
        val bytes = encoded.encodeToByteArray()
        val hash = sourceContentHash(bytes)
        return WorkspaceStaticEvidencePageReference(
            file = WorkspaceStaticEvidencePageReference.path(hash),
            contentHash = hash,
            encodedByteSize = bytes.size.toLong(),
            routes = page.entries.map(WorkspaceStaticEvidenceEntry::locator),
        ).also { reference -> writeEvidenceExact(staging.resolve(reference.file), encoded) }
    }

    private fun writeCatalog(
        staging: Path,
        catalog: WorkspaceStaticEvidenceCatalog,
    ): WorkspaceStaticEvidenceCatalogReference {
        val encoded = WorkspaceEvidenceJson.encodeStaticCatalog(catalog)
        val bytes = encoded.encodeToByteArray()
        val hash = sourceContentHash(bytes)
        return WorkspaceStaticEvidenceCatalogReference(
            file = WorkspaceStaticEvidenceCatalogReference.path(hash),
            contentHash = hash,
            encodedByteSize = bytes.size.toLong(),
        ).also { reference -> writeEvidenceExact(staging.resolve(reference.file), encoded) }
    }
}

private fun writeEvidenceExact(
    destination: Path,
    content: String,
) {
    Files.createDirectories(destination.parent)
    if (Files.exists(destination)) {
        require(Files.readString(destination) == content) {
            "Distinct static-evidence payloads produced the same content hash"
        }
    } else {
        Files.writeString(destination, content)
    }
}
