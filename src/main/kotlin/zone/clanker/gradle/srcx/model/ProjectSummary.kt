package zone.clanker.gradle.srcx.model

/**
 * A Gradle project path (e.g. ":app" or ":").
 *
 * @property value the project path, must start with ":"
 */
@JvmInline
value class ProjectPath(
    val value: String,
) {
    init {
        require(value.startsWith(":")) { "ProjectPath must start with ':', was '$value'" }
    }

    override fun toString(): String = value
}

/**
 * Aggregated summary of a single Gradle project's source analysis.
 *
 * Collects all extracted symbols and dependencies for a project, along
 * with metadata about the build file and source directories. Used by
 * [DashboardRenderer][zone.clanker.gradle.srcx.report.DashboardRenderer]
 * to produce the root index.
 *
 * @property projectPath the Gradle project path (e.g. ":app" or ":")
 * @property symbols the list of extracted source symbols (all source sets combined)
 * @property dependencies the list of extracted dependencies
 * @property buildFile the name of the build file (e.g. "build.gradle.kts")
 * @property sourceDirs the list of source directory paths that were scanned
 * @property subprojects the list of direct subproject paths
 * @property sourceSets the per-source-set symbol breakdown (main, test, etc.)
 */
data class ProjectSummary(
    val projectPath: ProjectPath,
    val symbols: List<SymbolEntry>,
    val dependencies: List<DependencyEntry>,
    val buildFile: String,
    val sourceDirs: List<String>,
    val subprojects: List<String>,
    val sourceSets: List<SourceSetSummary> = emptyList(),
    val analysis: AnalysisSummary? = null,
)
