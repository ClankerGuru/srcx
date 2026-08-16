package zone.clanker.docx.service.workspace

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

internal class RegistryLease private constructor(
    private val channel: FileChannel,
    private val lock: FileLock,
) : AutoCloseable {
    override fun close() {
        lock.release()
        channel.close()
    }

    companion object {
        fun acquire(registryFile: Path): RegistryLease {
            val lockFile = registryFile.resolveSibling("${registryFile.fileName}.lock")
            lockFile.parent?.let(Files::createDirectories)
            val channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            val lock =
                runCatching { channel.tryLock() }
                    .recover { error ->
                        if (error is OverlappingFileLockException) null else throw error
                    }.getOrThrow()
            if (lock == null) {
                channel.close()
                error("Another DOCX service owns registry $registryFile; use a different --registry path.")
            }
            return RegistryLease(channel, lock)
        }
    }
}
