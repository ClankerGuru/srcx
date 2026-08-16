package zone.clanker.docx.service.workspace

import zone.clanker.report.model.WorkspaceServiceEndpoint
import zone.clanker.report.model.WorkspaceServiceJson
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission

internal class EndpointFile private constructor(
    private val path: Path,
    private val endpoint: WorkspaceServiceEndpoint,
) : AutoCloseable {
    override fun close() {
        val stillOwned =
            path
                .takeIf(Files::isRegularFile)
                ?.let(Files::readString)
                ?.let { content -> runCatching { WorkspaceServiceJson.decodeEndpoint(content) }.getOrNull() }
                ?.let(endpoint::equals) == true
        if (stillOwned) Files.deleteIfExists(path)
    }

    companion object {
        fun write(
            path: Path,
            endpoint: WorkspaceServiceEndpoint,
        ): EndpointFile {
            path.parent?.let(Files::createDirectories)
            val temporary = path.resolveSibling("${path.fileName}.tmp")
            Files.writeString(temporary, WorkspaceServiceJson.encodeEndpoint(endpoint))
            setOwnerOnlyPermissions(temporary)
            moveAtomically(temporary, path)
            setOwnerOnlyPermissions(path)
            return EndpointFile(path, endpoint)
        }

        private fun setOwnerOnlyPermissions(path: Path) {
            runCatching {
                Files.setPosixFilePermissions(
                    path,
                    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                )
            }
        }

        private fun moveAtomically(
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
            }.recoverCatching { error ->
                if (error !is AtomicMoveNotSupportedException) throw error
                Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING)
            }.getOrThrow()
        }
    }
}
