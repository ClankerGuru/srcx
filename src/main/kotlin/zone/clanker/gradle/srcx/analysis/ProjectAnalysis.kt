package zone.clanker.gradle.srcx.analysis

import java.io.File

/**
 * Aggregated analysis results for a set of source directories.
 *
 * @property antiPatterns detected code issues
 * @property hubs most-depended-on classes with inbound counts
 * @property roles component role classifications
 * @property cycles detected circular dependencies
 */
data class ProjectAnalysis(
    val antiPatterns: List<AntiPattern>,
    val hubs: List<HubResult>,
    val roles: Map<String, ComponentRole>,
    val cycles: List<List<String>>,
) {
    /** Convert to a model-layer [zone.clanker.gradle.srcx.model.AnalysisSummary]. */
    fun toSummary(): zone.clanker.gradle.srcx.model.AnalysisSummary {
        val findings =
            antiPatterns.map { ap ->
                zone.clanker.gradle.srcx.model.Finding(
                    severity =
                        when (ap.severity) {
                            AntiPattern.Severity.WARNING -> zone.clanker.gradle.srcx.model.FindingSeverity.WARNING
                            AntiPattern.Severity.INFO -> zone.clanker.gradle.srcx.model.FindingSeverity.INFO
                        },
                    message = ap.message,
                    suggestion = ap.suggestion,
                )
            }
        val hubClasses =
            hubs.map { hub ->
                val name = hub.component.source.simpleName
                val role = roles[name]
                val roleLabel = if (role != null && role != ComponentRole.OTHER) role.name.lowercase() else ""
                val depRefs =
                    hub.dependents.map { dep ->
                        zone.clanker.gradle.srcx.model
                            .HubDependentRef(dep.name, dep.filePath, dep.line)
                    }
                zone.clanker.gradle.srcx.model.HubClass(
                    name = name,
                    dependentCount = hub.count,
                    role = roleLabel,
                    filePath = hub.component.source.relativePath,
                    line = hub.component.source.declarationLine,
                    dependents = depRefs,
                )
            }
        return zone.clanker.gradle.srcx.model
            .AnalysisSummary(findings, hubClasses, cycles)
    }
}

/**
 * Run the full analysis pipeline on a set of source directories.
 *
 * Parses source files, classifies components, builds the dependency graph,
 * detects anti-patterns, finds hub classes, and identifies cycles.
 */
fun analyzeProject(sourceDirs: List<File>, rootDir: File): ProjectAnalysis {
    val sources = scanSources(sourceDirs)
    if (sources.isEmpty()) return ProjectAnalysis(emptyList(), emptyList(), emptyMap(), emptyList())

    val components = classifyAll(sources)
    val edges = buildDependencyGraph(components)

    val antiPatterns = detectAntiPatterns(components, edges, rootDir)
    val hubs = findHubClasses(components, edges)
    val roles = components.associate { it.source.simpleName to it.role }
    val cycles = findCycles(edges)

    return ProjectAnalysis(antiPatterns, hubs, roles, cycles)
}
