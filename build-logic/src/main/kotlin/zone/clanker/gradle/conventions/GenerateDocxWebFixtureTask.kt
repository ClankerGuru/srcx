package zone.clanker.gradle.conventions

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.process.ExecOperations
import javax.inject.Inject

/** Generates the standalone DOCX site fixture by running its typed Kotlin serializer. */
@CacheableTask
abstract class GenerateDocxWebFixtureTask
    @Inject
    constructor(
        private val execOperations: ExecOperations,
        private val fileSystemOperations: FileSystemOperations,
    ) : DefaultTask() {
        @get:Classpath
        abstract val runtimeClasspath: ConfigurableFileCollection

        @get:OutputDirectory
        abstract val outputDirectory: DirectoryProperty

        @get:Nested
        abstract val javaLauncher: Property<JavaLauncher>

        init {
            group = "distribution"
            description = "Generate the typed standalone DOCX viewer fixture"
        }

        @TaskAction
        fun generateFixture() {
            val destination = outputDirectory.get().asFile
            fileSystemOperations.delete {
                delete(destination)
            }
            execOperations
                .javaexec {
                    classpath(runtimeClasspath)
                    executable(javaLauncher.get().executablePath.asFile)
                    mainClass.set("zone.clanker.docx.web.fixture.DocxWebFixtureKt")
                    args(destination.absolutePath)
                }.assertNormalExitValue()
            require(destination.resolve(ATLAS_OVERVIEW_PATH).isFile) {
                "DOCX fixture generator must emit $ATLAS_OVERVIEW_PATH"
            }
            require(destination.resolve(ATLAS_OVERVIEWS_PATH).isFile) {
                "DOCX fixture generator must emit $ATLAS_OVERVIEWS_PATH"
            }
        }

        private companion object {
            const val ATLAS_OVERVIEW_PATH = "data/atlas-overview.json"
            const val ATLAS_OVERVIEWS_PATH = "data/atlas-overviews.json"
        }
    }
