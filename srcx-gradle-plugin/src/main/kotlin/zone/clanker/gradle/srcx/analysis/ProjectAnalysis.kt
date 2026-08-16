package zone.clanker.gradle.srcx.analysis

import zone.clanker.gradle.srcx.model.AnalysisSummary
import zone.clanker.gradle.srcx.model.ArchitectureComponent
import zone.clanker.gradle.srcx.model.ArchitectureComponentCycle
import zone.clanker.gradle.srcx.model.ArchitectureDependency
import zone.clanker.gradle.srcx.model.ArchitectureEntryPoint
import zone.clanker.gradle.srcx.model.ArchitectureEntryPointKind
import zone.clanker.gradle.srcx.model.ArchitectureLayer
import zone.clanker.gradle.srcx.model.ArchitectureSummary
import zone.clanker.gradle.srcx.model.Finding
import zone.clanker.gradle.srcx.model.FindingSeverity
import zone.clanker.gradle.srcx.model.HubClass
import zone.clanker.gradle.srcx.model.HubDependentRef
import java.io.File

/**
 * Aggregated analysis results for a set of source directories.
 *
 * @property antiPatterns detected code issues
 * @property hubs most-depended-on classes with inbound counts
 * @property roles component role classifications
 * @property cycles detected circular dependencies
 * @property components classified source components used to build the graph
 * @property dependencies directional dependencies between source components
 */
data class ProjectAnalysis(
    val antiPatterns: List<AntiPattern>,
    val hubs: List<HubResult>,
    val roles: Map<String, ComponentRole>,
    val cycles: List<List<String>>,
    val components: List<ClassifiedComponent> = emptyList(),
    val dependencies: List<ClassDependency> = emptyList(),
) {
    /** Convert analysis internals to a report-safe model. */
    fun toSummary(): AnalysisSummary =
        AnalysisSummary(
            findings = antiPatterns.map(AntiPattern::toFinding),
            hubs = hubs.map { it.toHubClass(roles) },
            cycles = cycles,
            architecture = toArchitectureSummary(),
        )
}

private fun AntiPattern.toFinding(): Finding =
    Finding(
        severity =
            when (severity) {
                AntiPattern.Severity.FORBIDDEN -> FindingSeverity.FORBIDDEN
                AntiPattern.Severity.WARNING -> FindingSeverity.WARNING
                AntiPattern.Severity.INFO -> FindingSeverity.INFO
            },
        message = message,
        suggestion = suggestion,
        filePath = file.path.takeUnless { it.isBlank() || it == "." },
        line = line,
        componentIds = componentIds,
        componentCycle = componentCycle,
    )

private fun HubResult.toHubClass(roles: Map<String, ComponentRole>): HubClass {
    val name = component.source.simpleName
    val role = roles[name]
    return HubClass(
        name = name,
        dependentCount = count,
        role = if (role != null && role != ComponentRole.OTHER) role.name.lowercase() else "",
        filePath = component.source.relativePath,
        line = component.source.declarationLine,
        dependents = dependents.map { HubDependentRef(it.name, it.filePath, it.line) },
        isTest = component.source.isTestSource(),
    )
}

private fun ProjectAnalysis.toArchitectureSummary(): ArchitectureSummary =
    components
        .groupBy { component -> component.source.qualifiedName }
        .filterValues { candidates -> candidates.size == 1 }
        .values
        .map { candidates -> candidates.single() }
        .sortedWith(
            compareBy<ClassifiedComponent> { component -> component.source.qualifiedName }
                .thenBy { component -> component.source.file.invariantSeparatorsPath },
        ).let { unambiguousComponents ->
            val componentIds = unambiguousComponents.mapTo(mutableSetOf()) { it.source.qualifiedName }
            val unambiguousDependencies =
                dependencies.filter { edge ->
                    edge.from.source.qualifiedName in componentIds && edge.to.source.qualifiedName in componentIds
                }
            ArchitectureSummary(
                components = unambiguousComponents.map { it.toArchitectureComponent() },
                dependencies =
                    unambiguousDependencies
                        .filter { it.from.source.qualifiedName != it.to.source.qualifiedName }
                        .map { ArchitectureDependency(it.from.source.qualifiedName, it.to.source.qualifiedName) }
                        .distinct()
                        .sortedWith(compareBy({ it.from }, { it.to })),
                entryPoints =
                    classifyEntryPoints(unambiguousComponents, unambiguousDependencies)
                        .filter { it.kind == EntryPointKind.APP }
                        .map(ClassifiedEntryPoint::toArchitectureEntryPoint)
                        .distinct()
                        .sortedWith(compareBy({ it.componentId }, { it.kind }, { it.reason })),
                cycles = findQualifiedCycles(unambiguousDependencies).map(::ArchitectureComponentCycle),
            )
        }

private fun ClassifiedEntryPoint.toArchitectureEntryPoint(): ArchitectureEntryPoint =
    ArchitectureEntryPoint(
        componentId = component.source.qualifiedName,
        reason = reason,
        kind =
            if (reason == "Dependency graph root") {
                ArchitectureEntryPointKind.GRAPH_ROOT
            } else {
                ArchitectureEntryPointKind.EXPLICIT
            },
    )

private fun ClassifiedComponent.toArchitectureComponent(): ArchitectureComponent {
    val isTest = source.isTestSource()
    return ArchitectureComponent(
        id = source.qualifiedName,
        name = source.simpleName,
        packageName = source.packageName,
        packageGroup = packageGroup,
        role = role.label,
        layer = ArchitectureLayer.valueOf(detectLayer(source.packageName, isTest).name),
        filePath = source.relativePath,
        line = source.declarationLine,
        isTest = isTest,
    )
}

private fun SourceFileMetadata.isTestSource(): Boolean {
    val normalizedPath = file.invariantSeparatorsPath
    val sourceSet = normalizedPath.substringAfter("/src/", "").substringBefore('/')
    return sourceSet.contains("test", ignoreCase = true) ||
        simpleName.endsWith("Test") ||
        simpleName.endsWith("Spec")
}

/**
 * Run the full analysis pipeline on a set of source directories.
 *
 * Parses source files, classifies components, builds the dependency graph,
 * detects anti-patterns, finds hub classes, and identifies cycles.
 */
fun analyzeProject(
    sourceDirs: List<File>,
    rootDir: File,
    forbiddenPackages: Set<String> = zone.clanker.gradle.srcx.Srcx.DEFAULT_FORBIDDEN_PACKAGES,
    forbiddenClassPatterns: Set<String> = zone.clanker.gradle.srcx.Srcx.DEFAULT_FORBIDDEN_CLASS_PATTERNS,
): ProjectAnalysis = analyzeSources(scanSources(sourceDirs), rootDir, forbiddenPackages, forbiddenClassPatterns)

/** Run project analysis from files that already passed Gradle ownership and output-directory filtering. */
fun analyzeProjectFiles(
    sourceFiles: List<File>,
    rootDir: File,
    forbiddenPackages: Set<String> = zone.clanker.gradle.srcx.Srcx.DEFAULT_FORBIDDEN_PACKAGES,
    forbiddenClassPatterns: Set<String> = zone.clanker.gradle.srcx.Srcx.DEFAULT_FORBIDDEN_CLASS_PATTERNS,
): ProjectAnalysis = analyzeSources(scanSourceFiles(sourceFiles), rootDir, forbiddenPackages, forbiddenClassPatterns)

private fun analyzeSources(
    sources: List<SourceFileMetadata>,
    rootDir: File,
    forbiddenPackages: Set<String>,
    forbiddenClassPatterns: Set<String>,
): ProjectAnalysis {
    if (sources.isEmpty()) return ProjectAnalysis(emptyList(), emptyList(), emptyMap(), emptyList())

    val components = classifyAll(sources)
    val edges = buildDependencyGraph(components)
    val antiPatterns = detectAntiPatterns(components, edges, rootDir, forbiddenPackages, forbiddenClassPatterns)
    val hubs = findHubClasses(components, edges)
    val roles = components.associate { component -> component.source.simpleName to component.role }
    val cycles = findCycles(edges)
    return ProjectAnalysis(antiPatterns, hubs, roles, cycles, components, edges)
}
