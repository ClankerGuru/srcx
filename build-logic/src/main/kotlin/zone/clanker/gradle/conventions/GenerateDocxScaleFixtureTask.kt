package zone.clanker.gradle.conventions

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.process.ExecOperations
import javax.inject.Inject

/** Generates a deterministic sharded DOCX scale fixture. */
@CacheableTask
abstract class GenerateDocxScaleFixtureTask
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

        @get:Input
        abstract val profileName: Property<String>

        init {
            group = "verification"
            description = "Generate a deterministic sharded DOCX scale fixture"
        }

        @TaskAction
        fun generateFixture() {
            val destination = outputDirectory.get().asFile
            fileSystemOperations.delete { delete(destination) }
            execOperations
                .javaexec {
                    classpath(runtimeClasspath)
                    executable(javaLauncher.get().executablePath.asFile)
                    mainClass.set("zone.clanker.docx.web.fixture.DocxScaleSiteFixtureKt")
                    args(destination.absolutePath, profileName.get())
                }.assertNormalExitValue()
            REQUIRED_FILES.forEach { path ->
                require(destination.resolve(path).isFile) { "DOCX scale fixture must emit $path" }
            }
            val shards = destination.resolve("data/shards").listFiles { file -> file.extension == "json" }.orEmpty()
            val expectedShards = PROFILE_PROJECT_SHARDS.getValue(profileName.get())
            require(shards.size == expectedShards) {
                "DOCX ${profileName.get()} scale fixture must emit $expectedShards project shards; found ${shards.size}"
            }
        }

        private companion object {
            val REQUIRED_FILES =
                listOf(
                    "data/manifest.json",
                    "data/workspace.json",
                    "data/dashboard.json",
                    "data/atlas-overview.json",
                    "data/scale-profile.properties",
                )
            val PROFILE_PROJECT_SHARDS = mapOf("10k" to 10, "25k" to 25, "repository" to 2_000)
        }
    }
