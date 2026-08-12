package zone.clanker.gradle.srcx.model

/** Typed source documentation for an entire Gradle workspace. */
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
) {
    init {
        require(name.isNotBlank()) { "name must not be blank" }
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
data class InterfaceSummary(
    val name: String,
    val packageName: String,
    val implementationCount: Int,
    val hasMock: Boolean,
    val sourceSet: String,
) {
    init {
        require(name.isNotBlank()) { "name must not be blank" }
        require(packageName.isNotBlank()) { "packageName must not be blank" }
        require(implementationCount >= 0) { "implementationCount must be >= 0" }
        require(sourceSet.isNotBlank()) { "sourceSet must not be blank" }
    }
}
