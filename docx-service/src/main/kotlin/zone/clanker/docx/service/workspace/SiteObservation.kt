package zone.clanker.docx.service.workspace

import zone.clanker.report.model.WorkspaceAvailability
import zone.clanker.report.model.WorkspaceIndexStatus
import zone.clanker.report.model.WorkspaceMount
import zone.clanker.report.model.WorkspaceSiteJson
import java.nio.file.Files
import java.nio.file.Path

internal fun observeSite(
    workspaceId: String,
    siteDirectory: Path,
    observedAtEpochMilliseconds: Long,
    indexStatus: WorkspaceIndexStatus,
): WorkspaceMount {
    val viewerPath = "/w/$workspaceId/"
    val manifestFile = siteDirectory.resolve(MANIFEST_FILE)
    val indexFile = siteDirectory.resolve(INDEX_FILE)
    if (!Files.isRegularFile(indexFile) || !Files.isRegularFile(manifestFile)) {
        return WorkspaceMount(
            workspaceId = workspaceId,
            viewerPath = viewerPath,
            availability = WorkspaceAvailability.UNAVAILABLE,
            message = "Generated site is not currently available.",
            observedAtEpochMilliseconds = observedAtEpochMilliseconds,
            index = indexStatus,
        )
    }
    return runCatching {
        val manifest = WorkspaceSiteJson.decodeManifest(Files.readString(manifestFile))
        val status =
            siteDirectory
                .resolve(STATUS_FILE)
                .takeIf(Files::isRegularFile)
                ?.let(Files::readString)
                ?.let(WorkspaceSiteJson::decodeStatus)
        WorkspaceMount(
            workspaceId = workspaceId,
            viewerPath = viewerPath,
            availability = WorkspaceAvailability.AVAILABLE,
            generationId = manifest.generationId,
            workspaceName = status?.workspaceName,
            siteState = status?.state,
            message = status?.message ?: "Generated site is available.",
            observedAtEpochMilliseconds = observedAtEpochMilliseconds,
            index = indexStatus,
        )
    }.getOrElse { error ->
        WorkspaceMount(
            workspaceId = workspaceId,
            viewerPath = viewerPath,
            availability = WorkspaceAvailability.UNAVAILABLE,
            message = "Generated site metadata is invalid: ${error.message ?: error.javaClass.simpleName}",
            observedAtEpochMilliseconds = observedAtEpochMilliseconds,
            index = indexStatus,
        )
    }
}

internal fun WorkspaceMount.sameObservation(other: WorkspaceMount): Boolean =
    copy(observedAtEpochMilliseconds = other.observedAtEpochMilliseconds) == other

internal const val INDEX_FILE: String = "index.html"
internal const val MANIFEST_FILE: String = "data/manifest.json"
internal const val STATUS_FILE: String = "status.json"
