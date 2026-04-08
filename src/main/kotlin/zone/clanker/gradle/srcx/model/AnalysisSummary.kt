package zone.clanker.gradle.srcx.model

/**
 * Severity level of an analysis finding.
 */
enum class FindingSeverity { WARNING, INFO }

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
 * A hub class with its inbound dependency count.
 *
 * @property name simple class name
 * @property dependents number of classes that depend on this
 * @property role architectural role label (e.g. "service", "repository")
 */
data class HubClass(
    val name: String,
    val dependents: Int,
    val role: String,
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
