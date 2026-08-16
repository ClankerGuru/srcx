package zone.clanker.docx.service

import zone.clanker.docx.service.workspace.INDEX_FILE
import zone.clanker.docx.service.workspace.MANIFEST_FILE
import zone.clanker.docx.service.workspace.STATUS_FILE
import zone.clanker.report.model.WorkspaceSiteJson
import zone.clanker.report.model.WorkspaceSiteManifest
import zone.clanker.report.model.WorkspaceSiteState
import zone.clanker.report.model.WorkspaceSiteStatus
import zone.clanker.report.model.WorkspaceSnapshot
import java.nio.file.Files
import java.nio.file.Path

internal fun temporaryDirectory(prefix: String): Path =
    Files.createTempDirectory(prefix).also { directory -> directory.toFile().deleteOnExit() }

internal fun writeSite(
    directory: Path,
    generationId: String = "generation-a",
    workspaceName: String = "sample-workspace",
    message: String = "Current",
) {
    Files.createDirectories(directory.resolve("data"))
    Files.writeString(directory.resolve(INDEX_FILE), "<html><body>$workspaceName</body></html>")
    Files.writeString(
        directory.resolve(MANIFEST_FILE),
        WorkspaceSiteJson.encodeManifest(
            WorkspaceSiteManifest(
                generationId = generationId,
                snapshotSchemaVersion = WorkspaceSnapshot.CURRENT_SCHEMA_VERSION,
                workspaceFile = "data/workspace.json",
                dashboardFile = "data/dashboard.json",
                projectShards = emptyList(),
                assetFiles = emptyList(),
            ),
        ),
    )
    Files.writeString(
        directory.resolve(STATUS_FILE),
        WorkspaceSiteJson.encodeStatus(
            WorkspaceSiteStatus(
                state = WorkspaceSiteState.CURRENT,
                generationId = generationId,
                workspaceName = workspaceName,
                message = message,
            ),
        ),
    )
}
