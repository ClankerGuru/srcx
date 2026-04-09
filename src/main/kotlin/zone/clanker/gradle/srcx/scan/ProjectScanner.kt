package zone.clanker.gradle.srcx.scan

import org.gradle.api.Project
import zone.clanker.gradle.srcx.model.SourceSetName
import java.io.File

/**
 * Project discovery utilities: collecting projects, discovering source sets,
 * and resolving source directories.
 *
 * All methods are pure functions on the file system or the Gradle [Project] API.
 */
object ProjectScanner {
    /** File extensions that PSI can parse. */
    private val SUPPORTED_EXTENSIONS = setOf("kt", "java", "kts")

    /** Collect root project and all its subprojects. */
    internal fun collectProjects(rootProject: Project): List<Project> =
        listOf(rootProject) + rootProject.subprojects

    /** Discover source set directories under src/ that contain source files. */
    internal fun discoverSourceSets(projectDir: File): List<SourceSetName> {
        val srcDir = File(projectDir, "src")
        if (!srcDir.exists() || !srcDir.isDirectory) return emptyList()
        val children = srcDir.listFiles() ?: return emptyList()
        return children
            .filter { it.isDirectory }
            .filter { dir ->
                dir.walkTopDown().any { f ->
                    f.isFile && f.extension in SUPPORTED_EXTENSIONS
                }
            }.map { SourceSetName(it.name) }
            .sortedWith(sourceSetComparator())
    }

    /** Return kotlin and java source directories for a given source set name. */
    internal fun sourceSetDirs(projectDir: File, sourceSetName: String): List<File> =
        listOf(
            File(projectDir, "src/$sourceSetName/kotlin"),
            File(projectDir, "src/$sourceSetName/java"),
        )

    /** Discover projects within an included build using Gradle internal APIs. */
    @Suppress("SwallowedException")
    internal fun discoverIncludedBuildProjects(
        build: org.gradle.api.initialization.IncludedBuild,
    ): List<Pair<String, File>> =
        runCatching {
            val target = build.javaClass.getMethod("getTarget").invoke(build)
            val registry =
                target!!.javaClass.getMethod("getProjects").let {
                    it.isAccessible = true
                    it.invoke(target)
                }
            val allProjects =
                registry!!.javaClass.getMethod("getAllProjects").let {
                    it.isAccessible = true
                    it.invoke(registry) as Set<*>
                }
            allProjects.mapNotNull { ps ->
                val path =
                    ps!!.javaClass.getMethod("getIdentityPath").let {
                        it.isAccessible = true
                        it.invoke(ps).toString()
                    }
                val dir =
                    ps.javaClass.getMethod("getProjectDir").let {
                        it.isAccessible = true
                        it.invoke(ps) as File
                    }
                // Strip the build prefix from the path to get project-relative path
                val relativePath =
                    path
                        .removePrefix(":${build.name}")
                        .ifEmpty { ":" }
                relativePath to dir
            }
        }.getOrDefault(listOf(":" to build.projectDir))

    /** Determine the build file name for a Gradle project. */
    internal fun buildFileName(project: Project): String =
        when {
            project.file("build.gradle.kts").exists() -> "build.gradle.kts"
            project.file("build.gradle").exists() -> "build.gradle"
            else -> "none"
        }

    private fun sourceSetComparator(): Comparator<SourceSetName> {
        val priority = mapOf("main" to 0, "test" to 1, "androidTest" to 2, "commonMain" to 3, "commonTest" to 4)
        return Comparator { a, b ->
            val pa = priority[a.value] ?: Int.MAX_VALUE
            val pb = priority[b.value] ?: Int.MAX_VALUE
            if (pa != pb) pa.compareTo(pb) else a.value.compareTo(b.value)
        }
    }
}
