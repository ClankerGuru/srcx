package zone.clanker.gradle.docx.site

import zone.clanker.report.model.SourceContentReference
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

internal class DocxSourceContentWriter {
    fun write(
        staging: Path,
        projectedContents: List<ProjectedSourceContent>,
    ): Map<String, List<SourceContentReference>> {
        require(
            projectedContents
                .map { source -> source.projectId to source.fileId }
                .distinct()
                .size == projectedContents.size,
        ) { "Projected source contents must identify each project file once" }
        val contentByHash = mutableMapOf<String, String>()
        return projectedContents
            .sortedWith(compareBy(ProjectedSourceContent::projectId, ProjectedSourceContent::fileId))
            .groupBy(ProjectedSourceContent::projectId)
            .mapValues { (_, projectContents) ->
                projectContents.map { source ->
                    val bytes = source.content.encodeToByteArray()
                    val hash = sourceContentHash(bytes)
                    val reference =
                        SourceContentReference(
                            fileId = source.fileId,
                            contentHash = hash,
                            encodedByteSize = bytes.size.toLong(),
                        )
                    val existingContent = contentByHash[hash]
                    require(existingContent == null || existingContent == source.content) {
                        "Distinct source bodies produced the same content hash"
                    }
                    if (existingContent == null) {
                        contentByHash[hash] = source.content
                        val destination = staging.resolve(reference.file)
                        Files.createDirectories(destination.parent)
                        Files.writeString(destination, source.content)
                    }
                    reference
                }
            }
    }
}

internal fun sourceContentHash(content: ByteArray): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(content)
        .joinToString("") { byte ->
            (byte.toInt() and BYTE_MASK).toString(HEX_RADIX).padStart(2, '0')
        }

private const val BYTE_MASK: Int = 0xff
private const val HEX_RADIX: Int = 16
