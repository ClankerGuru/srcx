package zone.clanker.gradle.srcx.scan

import org.gradle.api.Project
import zone.clanker.gradle.srcx.model.SourceSetName
import java.io.File

/** One source file with its authoritative Gradle source-set and source-root ownership. */
internal data class OwnedSourceFile(
    val sourceSet: SourceSetName,
    val sourceRoot: File,
    val file: File,
)

/** Project and source-root discovery backed by the configured Gradle model. */
object ProjectScanner {
    /** Collect root project and all its subprojects. */
    internal fun collectProjects(rootProject: Project): List<Project> =
        (listOf(rootProject) + rootProject.subprojects).sortedBy { project -> project.path }

    /** Capture configured Java, Kotlin/JVM, and Kotlin Multiplatform source roots with source-set ownership. */
    internal fun discoverSourceLayout(project: Project): ProjectSourceLayout {
        val configured =
            configuredJavaSourceSets(project) +
                configuredKotlinSourceSets(project)
        val excludedDirectories = projectOutputDirectories(project)
        return if (!hasConfiguredSourceModel(project)) {
            discoverConventionalSourceLayout(project.projectDir, excludedDirectories)
        } else {
            ProjectSourceLayout(
                normalizeSourceSets(project.projectDir, configured, excludedDirectories),
                excludedDirectories,
            )
        }
    }

    /** Normalize a captured layout again after execution-time ownership exclusions are applied. */
    internal fun resolveSourceLayout(
        projectDir: File,
        configured: ProjectSourceLayout?,
    ): ProjectSourceLayout {
        if (configured == null) return discoverConventionalSourceLayout(projectDir)
        val exclusions = normalizeDirectories(configured.excludedDirectories)
        return ProjectSourceLayout(
            sourceSets = normalizeSourceSets(projectDir, configured.sourceSets, exclusions),
            excludedDirectories = exclusions,
        )
    }

    /** Filesystem fallback for projects whose configured language model is unavailable. */
    internal fun discoverConventionalSourceLayout(
        projectDir: File,
        additionalExclusions: List<File> = emptyList(),
    ): ProjectSourceLayout {
        val exclusions = normalizeDirectories(defaultOutputDirectories(projectDir) + additionalExclusions)
        val srcDir = File(projectDir, "src")
        val sourceSets =
            srcDir
                .listFiles()
                .orEmpty()
                .filter { directory -> directory.isDirectory }
                .mapNotNull { sourceSetDirectory ->
                    val roots =
                        listOf(
                            File(sourceSetDirectory, "kotlin"),
                            File(sourceSetDirectory, "java"),
                        ).filter { root -> containsSupportedSource(projectDir, root, exclusions) }
                    roots.takeIf { it.isNotEmpty() }?.let { directories ->
                        ConfiguredSourceSet(SourceSetName(sourceSetDirectory.name), directories)
                    }
                }.sortedWith(compareBy(sourceSetComparator()) { sourceSet -> sourceSet.name })
        return ProjectSourceLayout(sourceSets, exclusions)
    }

    /** Return every owned source exactly once, excluding generated/build and nested-project trees. */
    internal fun collectSourceFiles(
        projectDir: File,
        layout: ProjectSourceLayout,
    ): List<OwnedSourceFile> {
        val normalizedProject = normalizeFile(projectDir)
        val exclusions = normalizeDirectories(layout.excludedDirectories)
        val candidates =
            layout.sourceSets.flatMap { sourceSet ->
                sourceSet.directories.flatMap { sourceRoot ->
                    collectFilesBelow(normalizedProject, normalizeFile(sourceRoot), exclusions).map { sourceFile ->
                        OwnedSourceFile(sourceSet.name, normalizeFile(sourceRoot), sourceFile)
                    }
                }
            }
        return candidates
            .sortedWith(
                compareBy<OwnedSourceFile> { source -> normalizeFile(source.file).path }
                    .thenByDescending { source -> normalizeFile(source.sourceRoot).path.length }
                    .thenComparator { left, right -> sourceSetComparator().compare(left.sourceSet, right.sourceSet) }
                    .thenBy { source -> normalizeFile(source.sourceRoot).path },
            ).distinctBy { source -> normalizeFile(source.file).path }
            .sortedWith(
                Comparator { left, right ->
                    sourceSetComparator().compare(left.sourceSet, right.sourceSet).takeIf { it != 0 }
                        ?: left.file.relativeTo(normalizedProject).invariantSeparatorsPath.compareTo(
                            right.file.relativeTo(normalizedProject).invariantSeparatorsPath,
                        )
                },
            )
    }

    /** Add ownership boundaries such as nested Gradle project directories. */
    internal fun excludeDirectories(
        layout: ProjectSourceLayout,
        directories: List<File>,
    ): ProjectSourceLayout =
        layout.copy(excludedDirectories = normalizeDirectories(layout.excludedDirectories + directories))

    /** Stable task-input representation of source-set ownership without machine-specific absolute paths. */
    internal fun sourceLayoutFingerprint(
        build: String,
        projectPath: String,
        projectDir: File,
        layout: ProjectSourceLayout,
    ): List<String> {
        val normalizedProject = normalizeFile(projectDir)
        val sourceEntries =
            layout.sourceSets.flatMap { sourceSet ->
                sourceSet.directories.mapNotNull { directory ->
                    relativePath(normalizedProject, directory)?.let { path ->
                        "build=$build|project=$projectPath|sourceSet=${sourceSet.name.value}|directory=$path"
                    }
                }
            }
        val exclusionEntries =
            layout.excludedDirectories.mapNotNull { directory ->
                relativePath(normalizedProject, directory)?.let { path ->
                    "build=$build|project=$projectPath|excluded=$path"
                }
            }
        return (sourceEntries + exclusionEntries).distinct().sorted()
    }

    /** Return a stable relative path after resolving platform-specific filesystem aliases. */
    internal fun relativeSourcePath(
        baseDir: File,
        file: File,
    ): String =
        requireNotNull(relativePath(baseDir, file)) {
            "srcx: '${normalizeFile(file)}' is outside source boundary '${normalizeFile(baseDir)}'"
        }

    /** Test source ownership after resolving platform-specific filesystem aliases. */
    internal fun containsSourceFile(
        sourceRoot: File,
        file: File,
    ): Boolean = relativePath(sourceRoot, file) != null

    /** Legacy conventional source-set discovery retained for filesystem-only callers. */
    internal fun discoverSourceSets(projectDir: File): List<SourceSetName> =
        discoverConventionalSourceLayout(projectDir).sourceSets.map { sourceSet -> sourceSet.name }

    /** Conventional Kotlin and Java roots retained for filesystem-only callers. */
    internal fun sourceSetDirs(projectDir: File, sourceSetName: String): List<File> =
        listOf(
            File(projectDir, "src/$sourceSetName/kotlin"),
            File(projectDir, "src/$sourceSetName/java"),
        )

    /** Determine the build file name for a Gradle project. */
    internal fun buildFileName(project: Project): String =
        when {
            project.file("build.gradle.kts").exists() -> "build.gradle.kts"
            project.file("build.gradle").exists() -> "build.gradle"
            else -> "none"
        }
}
