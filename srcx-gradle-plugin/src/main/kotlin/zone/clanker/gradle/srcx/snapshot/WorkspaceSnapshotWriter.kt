package zone.clanker.gradle.srcx.snapshot

import zone.clanker.gradle.srcx.Srcx
import zone.clanker.gradle.srcx.model.WorkspaceReport
import zone.clanker.report.model.WorkspaceSnapshotJson
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Writes the canonical typed workspace snapshot consumed by downstream renderers. */
data object WorkspaceSnapshotWriter {
    fun write(
        outputDirectory: File,
        canonicalRootPath: String,
        report: WorkspaceReport,
    ): File {
        require(outputDirectory.isDirectory || outputDirectory.mkdirs()) {
            "Unable to create SRCX output directory: ${outputDirectory.absolutePath}"
        }
        val snapshot =
            WorkspaceSnapshotMapper.map(
                report = report,
                workspaceId = WorkspaceSnapshotMapper.workspaceId(canonicalRootPath, report.name),
            )
        val destination = File(outputDirectory, Srcx.WORKSPACE_SNAPSHOT_FILE)
        AtomicSnapshotPublisher.publish(destination, WorkspaceSnapshotJson.encode(snapshot))
        return destination
    }
}

internal fun interface SnapshotFileMove {
    fun move(
        source: Path,
        destination: Path,
    )
}

/** Keeps the last complete generation visible until its replacement is durable. */
internal data object AtomicSnapshotPublisher {
    fun publish(
        destination: File,
        content: String,
        move: SnapshotFileMove = SnapshotFileMove(::replaceAtomically),
    ) {
        val parent = requireNotNull(destination.parentFile) { "Snapshot destination must have a parent directory" }
        require(parent.isDirectory || parent.mkdirs()) {
            "Unable to create snapshot directory: ${parent.absolutePath}"
        }
        val temporary = Files.createTempFile(parent.toPath(), ".${destination.name}.", ".tmp")
        runCatching {
            FileOutputStream(temporary.toFile()).use { output ->
                output.write(content.toByteArray(StandardCharsets.UTF_8))
                output.fd.sync()
            }
            move.move(temporary, destination.toPath())
        }.onFailure {
            Files.deleteIfExists(temporary)
        }.getOrThrow()
    }
}

private fun replaceAtomically(
    source: Path,
    destination: Path,
) {
    runCatching {
        Files.move(
            source,
            destination,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }.recoverCatching { failure ->
        if (failure !is AtomicMoveNotSupportedException) throw failure
        Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING)
    }.getOrThrow()
}
