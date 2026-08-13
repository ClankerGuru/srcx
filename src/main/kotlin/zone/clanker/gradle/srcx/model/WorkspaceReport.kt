package zone.clanker.gradle.srcx.model

/** Architecture documentation for an entire Gradle workspace. */
data class WorkspaceReport(
    val name: String,
    val rootProjects: List<ProjectSummary>,
    val includedBuilds: List<IncludedBuildSummary>,
    val buildEdges: List<BuildEdge>,
    val aggregateAnalysis: AnalysisSummary?,
    val entryPoints: List<EntryPointSummary>,
    val interfaces: List<InterfaceSummary>,
    val workspaceIndex: WorkspaceIndex = WorkspaceIndex(),
    val importantSymbols: List<ImportantSymbol> = emptyList(),
    val sourceFiles: List<WorkspaceSourceFile> = emptyList(),
) {
    init {
        require(name.isNotBlank()) { "name must not be blank" }
        require(sourceFiles.distinctBy { it.identity }.size == sourceFiles.size) {
            "sourceFiles must have distinct workspace identities"
        }
        require(sourceFiles == sourceFiles.sortedWith(WORKSPACE_SOURCE_FILE_ORDER)) {
            "sourceFiles must use deterministic build, project, source-set, and path order"
        }
    }

    val allProjects: List<ProjectSummary>
        get() = rootProjects + includedBuilds.flatMap { it.projects }

    val projectCount: Int
        get() = allProjects.size

    val symbolCount: Int
        get() = allProjects.sumOf { it.symbols.size }

    val dependencyCount: Int
        get() = allProjects.sumOf { it.dependencies.size }

    val sourceSetCount: Int
        get() = allProjects.sumOf { it.sourceSets.size }

    val indexedSymbolCount: Int
        get() = workspaceIndex.symbols.size

    val relationshipCount: Int
        get() = workspaceIndex.relationships.size

    val importantSymbolCount: Int
        get() = importantSymbols.size

    val findings: List<Finding>
        get() = aggregateAnalysis?.findings ?: allProjects.flatMap { it.analysis?.findings.orEmpty() }

    val productionHubs: List<HubClass>
        get() =
            (aggregateAnalysis?.hubs ?: allProjects.flatMap { it.analysis?.hubs.orEmpty() })
                .filterNot { it.isTest }
}

/** Stable identity for a source file within its Gradle workspace scope. */
data class WorkspaceSourceFileIdentity(
    val build: String,
    val project: String,
    val sourceSet: String,
    val projectRelativeFile: String,
) {
    init {
        require(build.isNotBlank()) { "build must not be blank" }
        require(project.isNotBlank()) { "project must not be blank" }
        require(sourceSet.isNotBlank()) { "sourceSet must not be blank" }
        require(projectRelativeFile.isNotBlank()) { "projectRelativeFile must not be blank" }
        require(!projectRelativeFile.startsWith('/') && !WINDOWS_ABSOLUTE_PATH.matches(projectRelativeFile)) {
            "projectRelativeFile must be relative"
        }
        require('\\' !in projectRelativeFile) { "projectRelativeFile must use forward slashes" }
        require(projectRelativeFile.split('/').none { it.isEmpty() || it == "." || it == ".." }) {
            "projectRelativeFile must be a normalized project-relative path"
        }
    }

    val value: String
        get() = "$build::$project::$sourceSet::$projectRelativeFile"
}

/** Complete source text with exact build, project, source-set, and file ownership. */
data class WorkspaceSourceFile(
    val build: String,
    val project: String,
    val sourceSet: String,
    val projectRelativeFile: String,
    val content: String,
) {
    val identity: WorkspaceSourceFileIdentity =
        WorkspaceSourceFileIdentity(
            build = build,
            project = project,
            sourceSet = sourceSet,
            projectRelativeFile = projectRelativeFile,
        )
}

private val WINDOWS_ABSOLUTE_PATH = Regex("^[A-Za-z]:[/\\\\].*")

private val WORKSPACE_SOURCE_FILE_ORDER =
    compareBy<WorkspaceSourceFile> { it.build }
        .thenBy { it.project }
        .thenBy { it.sourceSet }
        .thenBy { it.projectRelativeFile }

/** A typed summary of one included Gradle build. */
data class IncludedBuildSummary(
    val name: String,
    val relativePath: String,
    val projects: List<ProjectSummary>,
) {
    init {
        require(name.isNotBlank()) { "name must not be blank" }
        require(relativePath.isNotBlank()) { "relativePath must not be blank" }
    }

    val symbolCount: Int
        get() = projects.sumOf { it.symbols.size }

    val dependencyCount: Int
        get() = projects.sumOf { it.dependencies.size }

    val sourceSetCount: Int
        get() = projects.sumOf { it.sourceSets.size }

    val findings: List<Finding>
        get() = projects.flatMap { it.analysis?.findings.orEmpty() }
}

/** A dependency from one Gradle build to another. */
data class BuildEdge(
    val from: String,
    val to: String,
) {
    init {
        require(from.isNotBlank()) { "from must not be blank" }
        require(to.isNotBlank()) { "to must not be blank" }
    }
}

/** A classified application or test entry point. */
data class EntryPointSummary(
    val name: String,
    val packageName: String,
    val kind: EntryPointKind,
) {
    init {
        require(name.isNotBlank()) { "name must not be blank" }
        require(packageName.isNotBlank()) { "packageName must not be blank" }
    }
}

/** Entry-point groups presented by the workspace report. */
enum class EntryPointKind(
    val label: String,
) {
    APP("Application"),
    TEST("Test"),
    MOCK("Test double"),
}

/** A typed interface candidate and its observed implementation coverage. */
@Suppress("LongParameterList")
data class InterfaceSummary(
    val name: String,
    val packageName: String?,
    val implementationCount: Int,
    val hasMock: Boolean,
    val sourceSet: String,
    val build: String? = null,
    val project: String? = null,
    val qualifiedName: String =
        packageName
            ?.takeUnless { it == "_root_" }
            ?.let { "$it.$name" }
            ?: name,
    val identity: WorkspaceSymbolIdentity? = null,
) {
    init {
        require(name.isNotBlank()) { "name must not be blank" }
        require(packageName == null || packageName.isNotBlank()) { "packageName must not be blank" }
        require(implementationCount >= 0) { "implementationCount must be >= 0" }
        require(sourceSet.isNotBlank()) { "sourceSet must not be blank" }
        require(build == null || build.isNotBlank()) { "build must not be blank" }
        require(project == null || project.isNotBlank()) { "project must not be blank" }
        require(qualifiedName.isNotBlank()) { "qualifiedName must not be blank" }
        identity?.let { exactIdentity ->
            require(build == exactIdentity.build) { "build must match identity" }
            require(project == exactIdentity.project) { "project must match identity" }
            require(sourceSet == exactIdentity.sourceSet) { "sourceSet must match identity" }
            require(qualifiedName == exactIdentity.qualifiedName) { "qualifiedName must match identity" }
        }
    }
}
