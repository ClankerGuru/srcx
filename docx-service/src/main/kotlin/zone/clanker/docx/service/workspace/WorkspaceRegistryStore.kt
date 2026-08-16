package zone.clanker.docx.service.workspace

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

@Serializable
internal data class StoredWorkspaceRegistry(
    val workspaces: List<StoredWorkspace> = emptyList(),
)

@Serializable
internal data class StoredWorkspace(
    val workspaceId: String,
    val siteDirectory: String,
)

internal class WorkspaceRegistryStore(
    private val registryFile: Path,
) {
    fun load(): List<StoredWorkspace> =
        registryFile
            .takeIf(Files::isRegularFile)
            ?.let(Files::readString)
            ?.let { content -> FORMAT.decodeFromString<StoredWorkspaceRegistry>(content) }
            ?.workspaces
            .orEmpty()

    fun save(workspaces: List<StoredWorkspace>) {
        registryFile.parent?.let(Files::createDirectories)
        val temporary = registryFile.resolveSibling("${registryFile.fileName}.tmp")
        Files.writeString(temporary, FORMAT.encodeToString(StoredWorkspaceRegistry(workspaces)) + "\n")
        runCatching {
            Files.move(
                temporary,
                registryFile,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.recoverCatching { error ->
            if (error !is AtomicMoveNotSupportedException) throw error
            Files.move(temporary, registryFile, StandardCopyOption.REPLACE_EXISTING)
        }.getOrThrow()
    }

    private companion object {
        val FORMAT: Json =
            Json {
                encodeDefaults = true
                ignoreUnknownKeys = true
                prettyPrint = true
            }
    }
}
