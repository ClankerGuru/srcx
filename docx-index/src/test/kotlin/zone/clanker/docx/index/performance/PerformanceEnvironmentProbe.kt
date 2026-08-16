package zone.clanker.docx.index.performance

import zone.clanker.report.model.WorkspaceGraphSlice
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.readLines

internal data class ArtifactSize(
    val relativePath: String,
    val bytes: Long,
)

internal data class MemorySnapshot(
    val stage: String,
    val processRssBytes: Long?,
    val heapUsedBytes: Long,
)

internal fun artifactSizes(root: Path): List<ArtifactSize> =
    Files.walk(root).use { paths ->
        paths
            .filter(Files::isRegularFile)
            .map { path -> ArtifactSize(root.relativize(path).toString().replace('\\', '/'), Files.size(path)) }
            .sorted(compareBy(ArtifactSize::relativePath))
            .toList()
    }

internal fun memorySnapshot(stage: String): MemorySnapshot {
    val runtime = Runtime.getRuntime()
    return MemorySnapshot(
        stage = stage,
        processRssBytes = currentProcessRssBytes(),
        heapUsedBytes = runtime.totalMemory() - runtime.freeMemory(),
    )
}

internal fun requireBoundedSlice(slice: WorkspaceGraphSlice) {
    require(slice.content.nodes.size <= slice.limits.nodeLimit) {
        "Slice returned ${slice.content.nodes.size} nodes for limit ${slice.limits.nodeLimit}"
    }
    require(slice.content.relations.size <= slice.limits.relationLimit) {
        "Slice returned ${slice.content.relations.size} relations for limit ${slice.limits.relationLimit}"
    }
    require(
        slice.content.relations.all { relation ->
            relation.facts.sampleFactIds.size <= slice.limits.evidencePerRelationLimit
        },
    ) { "Slice returned an evidence sample above its per-relation limit" }
}

private fun currentProcessRssBytes(): Long? = linuxRssBytes() ?: portableRssBytes()

private fun linuxRssBytes(): Long? {
    val status = Path.of("/proc/self/status")
    if (!Files.isRegularFile(status)) return null
    val rssLine = status.readLines().firstOrNull { line -> line.startsWith("VmRSS:") } ?: return null
    return rssLine
        .split(WHITESPACE)
        .getOrNull(1)
        ?.toLongOrNull()
        ?.times(BYTES_PER_KIBIBYTE)
}

private fun portableRssBytes(): Long? {
    val process =
        ProcessBuilder(
            "ps",
            "-o",
            "rss=",
            "-p",
            ProcessHandle.current().pid().toString(),
        ).redirectErrorStream(true)
            .start()
    if (!process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS) || process.exitValue() != 0) {
        process.destroyForcibly()
        return null
    }
    return process
        .inputReader()
        .use { reader -> reader.readText().trim().toLongOrNull() }
        ?.times(BYTES_PER_KIBIBYTE)
}

private val WHITESPACE = Regex("\\s+")
private const val BYTES_PER_KIBIBYTE = 1_024L
private const val PROCESS_TIMEOUT_SECONDS = 3L
