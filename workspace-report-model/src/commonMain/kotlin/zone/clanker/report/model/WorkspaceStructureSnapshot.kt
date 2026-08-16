package zone.clanker.report.model

import kotlinx.serialization.Serializable

@Serializable
data class WorkspaceIdentity(
    val id: String,
    val name: String,
) {
    init {
        requireValidId(id, "Workspace")
        require(name.isNotBlank()) { "Workspace name must not be blank" }
    }
}

@Serializable
data class BuildSnapshot(
    val id: String,
    val name: String,
    val kind: BuildKind,
    val relativePath: String,
) {
    init {
        requireValidId(id, "Build")
        require(name.isNotBlank()) { "Build name must not be blank" }
        requireNormalizedBuildPath(relativePath)
        require(kind == BuildKind.ROOT || relativePath != ".") { "Only the root build may use '.' as its path" }
    }
}

@Serializable
enum class BuildKind(
    val label: String,
) {
    ROOT("Root build"),
    INCLUDED("Included build"),
}

@Serializable
data class BuildEdgeSnapshot(
    val id: String,
    val sourceBuildId: String,
    val targetBuildId: String,
) {
    init {
        requireValidId(id, "Build edge")
        requireValidId(sourceBuildId, "Build edge source")
        requireValidId(targetBuildId, "Build edge target")
        require(sourceBuildId != targetBuildId) { "Build edge endpoints must differ" }
    }
}

@Serializable
data class ProjectSnapshot(
    val id: String,
    val buildId: String,
    val path: String,
    val buildFile: String,
    val sourceDirectories: List<String> = emptyList(),
    val subprojectPaths: List<String> = emptyList(),
) {
    init {
        requireValidId(id, "Project")
        requireValidId(buildId, "Project build")
        requireGradleProjectPath(path, "Project path")
        requireNormalizedRelativePath(buildFile, "Project build file")
        sourceDirectories.forEach { requireNormalizedRelativePath(it, "Project source directory") }
        requireDistinctAndSorted(sourceDirectories, "Project source directories")
        subprojectPaths.forEach { requireGradleProjectPath(it, "Subproject path") }
        requireDistinctAndSorted(subprojectPaths, "Subproject paths")
    }
}

@Serializable
data class ProjectDependencySnapshot(
    val id: String,
    val projectId: String,
    val group: String,
    val artifact: String,
    val version: String,
    val scope: String,
) {
    init {
        requireValidId(id, "Project dependency")
        requireValidId(projectId, "Project dependency project")
        require(group.isNotBlank() && group.none(Char::isWhitespace)) {
            "Project dependency group must not be blank or contain whitespace"
        }
        require(artifact.isNotBlank()) { "Project dependency artifact must not be blank" }
        require(version.isNotBlank()) { "Project dependency version must not be blank" }
        require(scope.isNotBlank()) { "Project dependency scope must not be blank" }
    }
}

@Serializable
data class SourceSetSnapshot(
    val id: String,
    val projectId: String,
    val name: String,
    val sourceDirectories: List<String> = emptyList(),
) {
    init {
        requireValidId(id, "Source set")
        requireValidId(projectId, "Source-set project")
        require(name.isNotBlank()) { "Source-set name must not be blank" }
        sourceDirectories.forEach { requireNormalizedRelativePath(it, "Source-set directory") }
        requireDistinctAndSorted(sourceDirectories, "Source-set directories")
    }
}

@Serializable
data class SourceFileSnapshot(
    val id: String,
    val sourceSetId: String,
    val projectRelativePath: String,
    val language: SourceLanguage,
    val content: String? = null,
) {
    init {
        requireValidId(id, "Source file")
        requireValidId(sourceSetId, "Source-file source set")
        requireNormalizedRelativePath(projectRelativePath, "Source-file path")
    }
}

@Serializable
enum class SourceLanguage(
    val label: String,
) {
    KOTLIN("Kotlin"),
    KOTLIN_SCRIPT("Kotlin script"),
    JAVA("Java"),
    GROOVY("Groovy"),
    JSON("JSON"),
    YAML("YAML"),
    TOML("TOML"),
    MARKDOWN("Markdown"),
    OTHER("Other"),
}
