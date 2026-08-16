package zone.clanker.gradle.conventions

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import javax.inject.Inject

/** Runs the opt-in static and SQLite Workspace Atlas performance profile. */
@DisableCachingByDefault(because = "Performance samples must execute instead of being restored from cache")
abstract class WorkspaceAtlasPerformanceProfileTask
    @Inject
    constructor(
        private val execOperations: ExecOperations,
        private val fileSystemOperations: FileSystemOperations,
    ) : DefaultTask() {
        @get:Classpath
        abstract val runtimeClasspath: ConfigurableFileCollection

        @get:InputDirectory
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val siteDirectory: DirectoryProperty

        @get:OutputDirectory
        abstract val reportDirectory: DirectoryProperty

        @get:Nested
        abstract val javaLauncher: Property<JavaLauncher>

        @get:Input
        abstract val sampleCount: Property<Int>

        init {
            group = "verification"
            description = "Measure bounded static and SQLite Atlas queries on the repository-scale fixture"
            sampleCount.convention(DEFAULT_SAMPLE_COUNT)
            outputs.upToDateWhen { false }
        }

        @TaskAction
        fun measure() {
            val report = reportDirectory.get().asFile
            fileSystemOperations.delete { delete(report) }
            execOperations
                .javaexec {
                    classpath(runtimeClasspath)
                    executable(javaLauncher.get().executablePath.asFile)
                    mainClass.set(PERFORMANCE_MAIN_CLASS)
                    args(
                        siteDirectory.get().asFile.absolutePath,
                        report.absolutePath,
                        sampleCount.get().toString(),
                    )
                    jvmArgs("-Djava.awt.headless=true")
                }.assertNormalExitValue()
        }

        private companion object {
            const val DEFAULT_SAMPLE_COUNT = 30
            const val PERFORMANCE_MAIN_CLASS =
                "zone.clanker.docx.index.performance.WorkspaceAtlasPerformanceProfileKt"
        }
    }
