package zone.clanker.gradle.docx.site

import zone.clanker.report.model.WorkspaceEvidenceJson
import zone.clanker.report.model.WorkspaceSiteManifest
import zone.clanker.report.model.WorkspaceStaticEvidenceCatalogReference
import zone.clanker.report.model.WorkspaceStaticEvidencePageReference
import zone.clanker.report.model.WorkspaceStaticEvidencePartitionReference
import java.nio.file.Files
import java.nio.file.Path

internal object DocxStaticEvidenceVerifier {
    fun verify(
        staging: Path,
        manifest: WorkspaceSiteManifest,
        workspaceId: String,
        reference: WorkspaceStaticEvidenceCatalogReference,
    ) {
        val catalog =
            WorkspaceEvidenceJson.decodeStaticCatalog(
                verifiedPayload(staging, reference.file, reference.contentHash, reference.encodedByteSize),
            )
        require(catalog.target.workspaceId == workspaceId) {
            "Workspace evidence catalog does not belong to its workspace summary"
        }
        require(catalog.target.generationId == manifest.generationId) {
            "Workspace evidence catalog generation does not match its manifest"
        }
        catalog.partitions.forEach { partitionReference -> verifyPartition(staging, partitionReference) }
    }

    private fun verifyPartition(
        staging: Path,
        reference: WorkspaceStaticEvidencePartitionReference,
    ) {
        val partition =
            WorkspaceEvidenceJson.decodeStaticPartition(
                verifiedPayload(staging, reference.file, reference.contentHash, reference.encodedByteSize),
            )
        require(partition.prefix == reference.prefix) {
            "Workspace evidence partition identity does not match its catalog entry"
        }
        require(partition.pages.sumOf { page -> page.routes.size } == reference.routePageCount) {
            "Workspace evidence partition route count does not match its catalog entry"
        }
        partition.pages.forEach { pageReference -> verifyPage(staging, pageReference) }
    }

    private fun verifyPage(
        staging: Path,
        reference: WorkspaceStaticEvidencePageReference,
    ) {
        val page =
            WorkspaceEvidenceJson.decodeStaticPage(
                verifiedPayload(staging, reference.file, reference.contentHash, reference.encodedByteSize),
            )
        require(page.entries.map { entry -> entry.locator() } == reference.routes) {
            "Workspace evidence page routes do not match their partition entry"
        }
    }

    private fun verifiedPayload(
        staging: Path,
        file: String,
        expectedHash: String,
        expectedByteSize: Long,
    ): String {
        val payload = staging.resolve(file)
        require(Files.isRegularFile(payload)) { "Missing workspace evidence payload: $file" }
        val bytes = Files.readAllBytes(payload)
        require(bytes.size.toLong() == expectedByteSize) {
            "Workspace evidence payload byte size does not match its reference: $file"
        }
        require(sourceContentHash(bytes) == expectedHash) {
            "Workspace evidence payload hash does not match its reference: $file"
        }
        return bytes.decodeToString()
    }
}
