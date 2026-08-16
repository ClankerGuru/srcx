package zone.clanker.gradle.conventions

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File
import javax.inject.Inject

/** Materializes an isolated composite from real local repositories without writing into those checkouts. */
@DisableCachingByDefault(because = "The opt-in demo mirrors mutable local repositories")
abstract class PrepareDocxRealCompositeDemoTask
    @Inject
    constructor(
        private val fileSystemOperations: FileSystemOperations,
    ) : DefaultTask() {
        @get:Input
        abstract val repositoryRootPath: Property<String>

        @get:Input
        abstract val profileName: Property<String>

        @get:OutputDirectory
        abstract val outputDirectory: DirectoryProperty

        init {
            group = "verification"
            description = "Materialize an isolated DOCX composite from opt-in local repository copies"
            outputs.upToDateWhen { false }
        }

        @TaskAction
        fun prepare() {
            val localRoot = File(repositoryRootPath.get()).canonicalFile
            val repositories = realCompositeRepositories(localRoot, profileName.get())
            val missing = repositories.filterNot { repository -> repository.source.isDirectory }
            require(missing.isEmpty()) {
                "Missing real-composite repositories: ${missing.joinToString { repository -> repository.source.path }}"
            }

            val destination = outputDirectory.get().asFile
            repositories.forEach { repository -> mirrorRepository(repository, destination) }
            writeDemoBuild(destination, repositories)
            logger.lifecycle(
                "DOCX real composite '${profileName.get()}' prepared at ${destination.absolutePath} " +
                    "from ${repositories.size} read-only local repositories",
            )
        }

        private fun mirrorRepository(
            repository: RealCompositeRepository,
            destination: File,
        ) {
            fileSystemOperations.sync {
                from(repository.source)
                into(destination.resolve("repos/${repository.name}"))
                exclude(realCompositeCopyExcludes)
                includeEmptyDirs = false
            }
        }
    }

internal data class RealCompositeRepository(
    val name: String,
    val source: File,
)

internal fun realCompositeRepositories(
    localRoot: File,
    profileName: String,
): List<RealCompositeRepository> {
    val workspaceRoot = localRoot.resolve("foo-bar-workspace-repos/dev")
    val standard =
        listOf(
            RealCompositeRepository("srcx", workspaceRoot.resolve("srcx")),
            RealCompositeRepository("wrkx", workspaceRoot.resolve("wrkx")),
            RealCompositeRepository("opsx", workspaceRoot.resolve("opsx")),
            RealCompositeRepository("clkx-agents", workspaceRoot.resolve("clkx-agents")),
            RealCompositeRepository("clikt", workspaceRoot.resolve("clikt")),
            RealCompositeRepository("mosaic", workspaceRoot.resolve("mosaic")),
            RealCompositeRepository("turbine", workspaceRoot.resolve("turbine")),
        )
    return when (profileName) {
        "standard" -> standard
        "large" ->
            standard +
                listOf(
                    RealCompositeRepository("catalog", localRoot.resolve("catalog")),
                    RealCompositeRepository("gort", localRoot.resolve("gort")),
                )

        else -> error("Unsupported DOCX real-composite profile '$profileName'; use standard or large")
    }
}

private fun writeDemoBuild(
    destination: File,
    repositories: List<RealCompositeRepository>,
) {
    destination.mkdirs()
    destination.resolve("settings.gradle.kts").writeText(realCompositeSettings(repositories))
    destination.resolve("build.gradle.kts").writeText(
        """
        plugins {
            base
        }
        """.trimIndent() + "\n",
    )
    destination.resolve("gradle.properties").writeText(
        """
        org.gradle.caching=true
        org.gradle.configuration-cache=false
        org.gradle.jvmargs=-Xmx8g -XX:MaxMetaspaceSize=2g -Dfile.encoding=UTF-8
        """.trimIndent() + "\n",
    )
    destination.resolve("README.md").writeText(realCompositeReadme(repositories))
}

private fun realCompositeSettings(repositories: List<RealCompositeRepository>): String {
    val pluginRepository = repositories.single { repository -> repository.name == "srcx" }
    val includedBuilds =
        repositories
            .filterNot { repository -> repository == pluginRepository }
            .joinToString("\n") { repository ->
                "includeBuild(\"repos/${repository.name}\") { name = \"${repository.name}\" }"
            }
    return """
        pluginManagement {
            require(
                JavaVersion.current() == JavaVersion.VERSION_17 &&
                    System.getProperty("java.vendor").contains("JetBrains"),
            ) {
                "The DOCX real composite requires JetBrains JDK 17; set JAVA_HOME before running Gradle. " +
                    "Current runtime: ${'$'}{System.getProperty("java.vendor")} " +
                    "${'$'}{System.getProperty("java.version")}."
            }

            includeBuild("repos/srcx")
            repositories {
                mavenLocal()
                gradlePluginPortal()
                mavenCentral()
            }
        }

        dependencyResolutionManagement {
            repositories {
                mavenCentral()
                google()
            }
        }

        plugins {
            id("zone.clanker.gradle.srcx")
            id("zone.clanker.gradle.docx")
        }

        rootProject.name = "srcx-real-composite-demo"

        $includedBuilds
    """.trimIndent() + "\n"
}

private fun realCompositeReadme(repositories: List<RealCompositeRepository>): String =
    """
    # SRCX real composite demo

    This generated workspace contains isolated copies of real local Gradle repositories. Running SRCX or DOCX here
    writes only below this generated directory; the source checkouts remain unchanged.

    Included repositories: ${repositories.joinToString { repository -> repository.name }}

    Generate the typed context and verified static site with Gradle running on JetBrains JDK 17:

    ```bash
    JAVA_HOME=/path/to/jetbrains-jdk-17 gradle srcx-context docx-site --no-daemon --console=plain
    ```

    The first scan may need network access to populate the Gradle dependency cache. Later unchanged scans can add
    `--offline`. Reuse this directory rather than rematerializing it when you want warm-build measurements.

    The resulting report is in `.docx/` and remains navigable from any ordinary static HTTP server.
    """.trimIndent() + "\n"

private val realCompositeCopyExcludes =
    listOf(
        ".git/**",
        ".gradle/**",
        ".idea/**",
        ".kotlin/**",
        ".konan/**",
        ".srcx/**",
        ".docx/**",
        "build/**",
        "**/build/**",
        "node_modules/**",
        "**/node_modules/**",
        "out/**",
        "**/out/**",
        "target/**",
        "**/target/**",
        "**/.DS_Store",
    )
