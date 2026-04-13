package zone.clanker.gradle.srcx.model

/**
 * Severity level of an analysis finding.
 */
enum class FindingSeverity(
    val icon: String,
) {
    FORBIDDEN("\uD83D\uDEAB"),
    WARNING("⚠\uFE0F"),
    INFO("ℹ\uFE0F"),
}

/**
 * A single finding from code analysis.
 *
 * @property severity how serious the finding is
 * @property message human-readable description
 * @property suggestion actionable advice
 */
data class Finding(
    val severity: FindingSeverity,
    val message: String,
    val suggestion: String,
)

/**
 * A class that depends on a hub class.
 *
 * @property name simple class name
 * @property filePath relative file path (e.g. "com/example/Foo.kt")
 * @property line declaration line number
 */
data class HubDependentRef(
    val name: String,
    val filePath: String,
    val line: Int,
)

/**
 * A hub class with its inbound dependency count and dependent details.
 *
 * @property name simple class name
 * @property dependentCount number of classes that depend on this
 * @property role architectural role label (e.g. "service", "repository")
 * @property filePath relative file path of the hub class
 * @property line declaration line number of the hub class
 * @property dependents details of classes that depend on this hub
 */
data class HubClass(
    val name: String,
    val dependentCount: Int,
    val role: String,
    val filePath: String = "",
    val line: Int = 0,
    val dependents: List<HubDependentRef> = emptyList(),
    val isTest: Boolean = false,
)

/**
 * Analysis results for a project, expressed purely in model terms.
 *
 * @property findings anti-patterns, warnings, and informational notes
 * @property hubs most depended-on classes
 * @property cycles circular dependency chains
 */
data class AnalysisSummary(
    val findings: List<Finding>,
    val hubs: List<HubClass>,
    val cycles: List<List<String>>,
)
