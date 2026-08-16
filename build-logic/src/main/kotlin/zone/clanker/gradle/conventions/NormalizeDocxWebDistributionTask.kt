package zone.clanker.gradle.conventions

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.nio.file.Files
import java.nio.file.Path
import javax.inject.Inject

/** Produces a portable DOCX distribution from compiler-generated browser assets. */
@CacheableTask
abstract class NormalizeDocxWebDistributionTask
    @Inject
    constructor(
        private val fileSystemOperations: FileSystemOperations,
    ) : DefaultTask() {
        @get:InputDirectory
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val sourceDirectory: DirectoryProperty

        @get:OutputDirectory
        abstract val outputDirectory: DirectoryProperty

        init {
            group = "distribution"
            description = "Normalize machine-specific paths in the DOCX viewer distribution"
        }

        @TaskAction
        fun normalizeDistribution() {
            fileSystemOperations.sync {
                from(sourceDirectory)
                into(outputDirectory)
                includeEmptyDirs = false
            }
            val destination = outputDirectory.get().asFile.toPath()
            Files.walk(destination).use { paths ->
                paths
                    .filter(Files::isRegularFile)
                    .filter(::isTextAsset)
                    .forEach(::normalizeGeneratedFileUris)
            }
        }

        private fun isTextAsset(path: Path): Boolean =
            path.fileName
                .toString()
                .substringAfterLast('.', "")
                .lowercase() in TEXT_EXTENSIONS

        private fun normalizeGeneratedFileUris(path: Path) {
            val content = Files.readString(path)
            val normalized = GENERATED_SKIKO_FILE_URI.replace(content, "")
            if (normalized != content) {
                Files.writeString(path, normalized)
            }
        }

        private companion object {
            val TEXT_EXTENSIONS = setOf("css", "html", "js", "json", "map", "mjs")
            val GENERATED_SKIKO_FILE_URI =
                Regex("""file:/+[^"']*/build/wasm/packages/[^"']*/kotlin/skiko\.mjs""")
        }
    }
