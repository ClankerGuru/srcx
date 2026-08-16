package zone.clanker.gradle.srcx.scan

import zone.clanker.gradle.srcx.model.SourceSetName
import java.io.File

private val supportedExtensions = setOf("kt", "java", "kts")
private val excludedProjectDirectoryNames =
    setOf(".docx", ".gradle", ".kotlin", ".srcx", "node_modules", "out", "target")

internal fun normalizeSourceSets(
    projectDir: File,
    sourceSets: List<ConfiguredSourceSet>,
    exclusions: List<File>,
): List<ConfiguredSourceSet> {
    val normalizedProject = normalizeFile(projectDir)
    return sourceSets
        .groupBy { sourceSet -> sourceSet.name }
        .mapNotNull { (name, sameNameSets) ->
            val directories =
                sameNameSets
                    .flatMap { sourceSet -> sourceSet.directories }
                    .map(::normalizeFile)
                    .filter { directory ->
                        directory.startsWith(normalizedProject) &&
                            exclusions.none { excluded -> directory.startsWith(normalizeFile(excluded)) } &&
                            containsSupportedSource(normalizedProject, directory, exclusions)
                    }.distinctBy { directory -> directory.path }
                    .sortedBy { directory -> directory.relativeTo(normalizedProject).invariantSeparatorsPath }
            directories.takeIf { it.isNotEmpty() }?.let { ConfiguredSourceSet(name, it) }
        }.sortedWith(compareBy(sourceSetComparator()) { sourceSet -> sourceSet.name })
}

internal fun defaultOutputDirectories(projectDir: File): List<File> =
    excludedProjectDirectoryNames.map { name -> File(projectDir, name) } + File(projectDir, "build")

internal fun containsSupportedSource(
    projectDir: File,
    directory: File,
    exclusions: List<File>,
): Boolean = collectFilesBelow(normalizeFile(projectDir), normalizeFile(directory), exclusions).isNotEmpty()

internal fun collectFilesBelow(
    projectDir: File,
    sourceRoot: File,
    exclusions: List<File>,
): List<File> {
    if (!sourceRoot.isDirectory || !sourceRoot.startsWith(projectDir)) return emptyList()
    return sourceRoot
        .walkTopDown()
        .onEnter { directory ->
            directory == sourceRoot || exclusions.none { excluded -> directory.startsWith(excluded) }
        }.filter { file -> file.isFile && file.extension in supportedExtensions }
        .map(::normalizeFile)
        .toList()
}

internal fun normalizeDirectories(directories: List<File>): List<File> =
    directories
        .map(::normalizeFile)
        .distinctBy { directory -> directory.path }
        .sortedBy { directory -> directory.path }

internal fun normalizeFile(file: File): File =
    runCatching { file.canonicalFile }.getOrElse { file.absoluteFile.normalize() }

internal fun relativePath(
    projectDir: File,
    file: File,
): String? =
    normalizeFile(file)
        .relativeToOrNull(normalizeFile(projectDir))
        ?.invariantSeparatorsPath
        ?.ifEmpty { "." }

internal fun sourceSetComparator(): Comparator<SourceSetName> {
    val priority = mapOf("main" to 0, "test" to 1, "androidTest" to 2, "commonMain" to 3, "commonTest" to 4)
    return Comparator { left, right ->
        val leftPriority = priority[left.value] ?: Int.MAX_VALUE
        val rightPriority = priority[right.value] ?: Int.MAX_VALUE
        if (leftPriority != rightPriority) {
            leftPriority.compareTo(rightPriority)
        } else {
            left.value.compareTo(right.value)
        }
    }
}
