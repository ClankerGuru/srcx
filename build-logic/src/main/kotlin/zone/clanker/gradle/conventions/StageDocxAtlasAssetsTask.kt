package zone.clanker.gradle.conventions

import org.gradle.api.DefaultTask
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.file.RelativePath
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import javax.inject.Inject

/** Stages the canonical SRCX Atlas styles and approved local D3 runtime for DOCX. */
@CacheableTask
abstract class StageDocxAtlasAssetsTask
    @Inject
    constructor(
        private val archiveOperations: ArchiveOperations,
        private val fileSystemOperations: FileSystemOperations,
    ) : DefaultTask() {
        @get:InputFile
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val themeStylesheet: RegularFileProperty

        @get:InputFile
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val dashboardStylesheet: RegularFileProperty

        @get:Classpath
        abstract val d3WebJarFiles: ConfigurableFileCollection

        @get:OutputDirectory
        abstract val outputDirectory: DirectoryProperty

        init {
            group = "distribution"
            description = "Stage the canonical Atlas styles and approved D3 runtime for DOCX"
        }

        @TaskAction
        fun stageAssets() {
            val d3WebJar = d3WebJarFiles.singleFile
            fileSystemOperations.sync {
                from(themeStylesheet) {
                    into(ATLAS_DIRECTORY)
                }
                from(dashboardStylesheet) {
                    into(ATLAS_DIRECTORY)
                }
                from(archiveOperations.zipTree(d3WebJar)) {
                    include(D3_MINIFIED_RESOURCE, D3_LICENSE_RESOURCE)
                    includeEmptyDirs = false
                    eachFile {
                        val outputName =
                            when (path) {
                                D3_MINIFIED_RESOURCE -> D3_MINIFIED_FILE
                                D3_LICENSE_RESOURCE -> D3_LICENSE_FILE
                                else -> error("Unexpected D3 WebJar resource: $path")
                            }
                        relativePath =
                            RelativePath(
                                true,
                                VENDOR_DIRECTORY,
                                D3_DISTRIBUTION_DIRECTORY,
                                outputName,
                            )
                    }
                }
                into(outputDirectory)
            }
        }

        private companion object {
            const val ATLAS_DIRECTORY = "atlas"
            const val VENDOR_DIRECTORY = "vendor"
            const val D3_DISTRIBUTION_DIRECTORY = "d3-7.9.0"
            const val D3_MINIFIED_FILE = "d3.min.js"
            const val D3_LICENSE_FILE = "LICENSE"
            const val D3_MINIFIED_RESOURCE =
                "META-INF/resources/webjars/d3/7.9.0/dist/d3.min.js"
            const val D3_LICENSE_RESOURCE =
                "META-INF/resources/webjars/d3/7.9.0/LICENSE"
        }
    }
