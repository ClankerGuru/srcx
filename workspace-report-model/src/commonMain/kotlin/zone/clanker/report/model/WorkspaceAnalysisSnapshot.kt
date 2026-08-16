@file:Suppress("LongParameterList")

package zone.clanker.report.model

import kotlinx.serialization.Serializable

@Serializable
data class ProjectAnalysisSnapshot(
    val id: String,
    val projectId: String,
    val findings: List<FindingSnapshot> = emptyList(),
    val hubs: List<HubSnapshot> = emptyList(),
    val components: List<ArchitectureComponentSnapshot> = emptyList(),
    val dependencies: List<ArchitectureDependencySnapshot> = emptyList(),
    val entryPoints: List<ArchitectureEntryPointSnapshot> = emptyList(),
    val cycles: List<ArchitectureCycleSnapshot> = emptyList(),
    val legacyNameCycles: List<NamedCycleSnapshot> = emptyList(),
) {
    init {
        requireValidId(id, "Project analysis")
        requireValidId(projectId, "Project analysis project")
        requireUniqueAndSorted(findings.map(FindingSnapshot::id), "analysis findings")
        requireUniqueAndSorted(hubs.map(HubSnapshot::id), "analysis hubs")
        requireUniqueAndSorted(components.map(ArchitectureComponentSnapshot::id), "architecture components")
        requireUniqueAndSorted(dependencies.map(ArchitectureDependencySnapshot::id), "architecture dependencies")
        requireUniqueAndSorted(entryPoints.map(ArchitectureEntryPointSnapshot::id), "architecture entry points")
        requireUniqueAndSorted(cycles.map(ArchitectureCycleSnapshot::id), "architecture cycles")
        requireUniqueAndSorted(legacyNameCycles.map(NamedCycleSnapshot::id), "legacy analysis cycles")

        val componentIds = components.mapTo(mutableSetOf(), ArchitectureComponentSnapshot::id)
        require(dependencies.all { it.sourceComponentId in componentIds && it.targetComponentId in componentIds }) {
            "Architecture dependencies must reference components from their project analysis"
        }
        require(entryPoints.all { it.componentId in componentIds }) {
            "Architecture entry points must reference components from their project analysis"
        }
        val directedSteps = dependencies.mapTo(mutableSetOf()) { it.sourceComponentId to it.targetComponentId }
        require(
            cycles.all { cycle ->
                cycle.componentIds.all(componentIds::contains) &&
                    cycle.componentIds.zipWithNext().all(directedSteps::contains)
            },
        ) {
            "Architecture cycles must follow directed dependencies from their project analysis"
        }
        require(findings.flatMap(FindingSnapshot::resolvedComponentIds).all(componentIds::contains)) {
            "Resolved finding components must reference components from their project analysis"
        }
    }
}

@Serializable
data class ArchitectureComponentSnapshot(
    val id: String,
    val componentId: String,
    val name: String,
    val packageName: String,
    val packageGroup: String,
    val role: String,
    val layer: ArchitectureLayer,
    val filePath: String,
    val line: Int,
    val isTest: Boolean,
    val sourceFileId: String? = null,
    val symbolId: String? = null,
) {
    init {
        requireValidId(id, "Architecture component")
        require(componentId.isNotBlank()) { "Architecture component key must not be blank" }
        require(name.isNotBlank()) { "Architecture component name must not be blank" }
        require(packageGroup.isNotBlank()) { "Architecture component package group must not be blank" }
        requireNormalizedRelativePath(filePath, "Architecture component file path")
        require(line > 0) { "Architecture component line must be positive" }
        sourceFileId?.let { requireValidId(it, "Architecture component source file") }
        symbolId?.let { requireValidId(it, "Architecture component symbol") }
    }
}

@Serializable
enum class ArchitectureLayer(
    val label: String,
) {
    PRESENTATION("Presentation"),
    DOMAIN("Domain"),
    DATA("Data"),
    MODEL("Model"),
    INFRASTRUCTURE("Infrastructure"),
    TEST("Test"),
    OTHER("Other"),
}

@Serializable
data class ArchitectureDependencySnapshot(
    val id: String,
    val sourceComponentId: String,
    val targetComponentId: String,
) {
    init {
        requireValidId(id, "Architecture dependency")
        requireValidId(sourceComponentId, "Architecture dependency source")
        requireValidId(targetComponentId, "Architecture dependency target")
        require(sourceComponentId != targetComponentId) { "Architecture dependency endpoints must differ" }
    }
}

@Serializable
data class ArchitectureEntryPointSnapshot(
    val id: String,
    val componentId: String,
    val reason: String,
    val kind: ArchitectureEntryPointKind,
) {
    init {
        requireValidId(id, "Architecture entry point")
        requireValidId(componentId, "Architecture entry-point component")
        require(reason.isNotBlank()) { "Architecture entry-point reason must not be blank" }
    }
}

@Serializable
enum class ArchitectureEntryPointKind(
    val label: String,
) {
    EXPLICIT("Explicit"),
    GRAPH_ROOT("Graph root"),
}

@Serializable
data class ArchitectureCycleSnapshot(
    val id: String,
    val componentIds: List<String>,
) {
    init {
        requireValidId(id, "Architecture cycle")
        requireClosedRoute(componentIds, "Architecture cycle route")
    }
}

@Serializable
data class NamedCycleSnapshot(
    val id: String,
    val names: List<String>,
) {
    init {
        requireValidId(id, "Named cycle")
        requireClosedRoute(names, "Named cycle route")
    }
}

@Serializable
data class FindingSnapshot(
    val id: String,
    val severity: FindingSeverity,
    val message: String,
    val suggestion: String,
    val fileId: String? = null,
    val filePath: String? = null,
    val line: Int? = null,
    val componentIds: List<String> = emptyList(),
    val resolvedComponentIds: List<String> = emptyList(),
    val symbolIds: List<String> = emptyList(),
    val componentCycle: List<String>? = null,
) {
    init {
        requireValidId(id, "Finding")
        require(message.isNotBlank()) { "Finding message must not be blank" }
        require(suggestion.isNotBlank()) { "Finding suggestion must not be blank" }
        fileId?.let { requireValidId(it, "Finding file") }
        filePath?.let { requireNormalizedRelativePath(it, "Finding file path") }
        require(fileId == null || filePath != null) { "A resolved finding file requires its original source path" }
        requireOptionalLine(line, "Finding line")
        require(line == null || filePath != null) { "A finding line requires its original source path" }
        requireDistinctAndSorted(componentIds, "Finding component IDs")
        requireDistinctAndSorted(resolvedComponentIds, "Resolved finding component IDs")
        requireDistinctAndSorted(symbolIds, "Finding symbol IDs")
        componentCycle?.let { requireClosedRoute(it, "Finding component-cycle route") }
    }
}

@Serializable
enum class FindingSeverity(
    val label: String,
) {
    FORBIDDEN("Forbidden"),
    WARNING("Warning"),
    INFO("Info"),
}

@Serializable
data class HubSnapshot(
    val id: String,
    val name: String,
    val dependentCount: Int,
    val role: String,
    val filePath: String? = null,
    val line: Int? = null,
    val sourceFileId: String? = null,
    val dependents: List<HubDependentSnapshot> = emptyList(),
    val isTest: Boolean = false,
) {
    init {
        requireValidId(id, "Hub")
        require(name.isNotBlank()) { "Hub name must not be blank" }
        require(dependentCount >= 0) { "Hub dependent count must not be negative" }
        filePath?.let { requireNormalizedRelativePath(it, "Hub file path") }
        requireOptionalLine(line, "Hub line")
        require(sourceFileId == null || filePath != null) { "A resolved hub file requires its original source path" }
        require(line == null || filePath != null) { "A hub line requires a file path" }
        sourceFileId?.let { requireValidId(it, "Hub source file") }
        require(dependents == dependents.distinct().sortedWith(HubDependentSnapshot.ORDER)) {
            "Hub dependents must be distinct and deterministic"
        }
        require(dependents.size <= dependentCount) { "Hub details cannot exceed its dependent count" }
    }
}

@Serializable
data class HubDependentSnapshot(
    val name: String,
    val filePath: String,
    val line: Int? = null,
) {
    init {
        require(name.isNotBlank()) { "Hub dependent name must not be blank" }
        requireNormalizedRelativePath(filePath, "Hub dependent file path")
        requireOptionalLine(line, "Hub dependent line")
    }

    internal companion object {
        val ORDER: Comparator<HubDependentSnapshot> =
            compareBy(HubDependentSnapshot::name, HubDependentSnapshot::filePath, HubDependentSnapshot::line)
    }
}

@Serializable
data class WorkspaceEntryPointSnapshot(
    val id: String,
    val name: String,
    val packageName: String,
    val kind: WorkspaceEntryPointKind,
    val projectId: String? = null,
    val symbolId: String? = null,
) {
    init {
        requireValidId(id, "Workspace entry point")
        require(name.isNotBlank()) { "Workspace entry-point name must not be blank" }
        require(packageName.isNotBlank()) { "Workspace entry-point package must not be blank" }
        projectId?.let { requireValidId(it, "Workspace entry-point project") }
        symbolId?.let { requireValidId(it, "Workspace entry-point symbol") }
    }
}

@Serializable
enum class WorkspaceEntryPointKind(
    val label: String,
) {
    APP("Application"),
    TEST("Test"),
    MOCK("Test double"),
}

@Serializable
data class InterfaceSnapshot(
    val id: String,
    val name: String,
    val packageName: String? = null,
    val implementationCount: Int,
    val hasMock: Boolean,
    val sourceSet: String,
    val qualifiedName: String,
    val buildId: String? = null,
    val projectId: String? = null,
    val symbolId: String? = null,
) {
    init {
        requireValidId(id, "Interface")
        require(name.isNotBlank()) { "Interface name must not be blank" }
        require(packageName == null || packageName.isNotBlank()) { "Interface package must not be blank" }
        require(implementationCount >= 0) { "Interface implementation count must not be negative" }
        require(sourceSet.isNotBlank()) { "Interface source set must not be blank" }
        require(qualifiedName.isNotBlank()) { "Interface qualified name must not be blank" }
        buildId?.let { requireValidId(it, "Interface build") }
        projectId?.let { requireValidId(it, "Interface project") }
        symbolId?.let { requireValidId(it, "Interface symbol") }
        require(projectId == null || buildId != null) { "Interface project ownership requires build ownership" }
    }
}

@Serializable
data class ImportantSymbolSnapshot(
    val id: String,
    val symbolId: String,
    val reasons: List<ImportantReasonSnapshot>,
    val score: Int,
    val usage: ImportantSymbolUsageSnapshot,
) {
    init {
        requireValidId(id, "Important symbol")
        requireValidId(symbolId, "Important symbol declaration")
        require(reasons.isNotEmpty()) { "Important-symbol reasons must not be empty" }
        require(reasons == reasons.distinct().sortedBy { it.reason.name }) {
            "Important-symbol reasons must be distinct and deterministic"
        }
        require(score == reasons.sumOf(ImportantReasonSnapshot::score)) {
            "Important-symbol score must equal its reason scores"
        }
    }
}

/** Exact incident relationship evidence and producer-derived usage signals for one ranked declaration. */
@Serializable
data class ImportantSymbolUsageSnapshot(
    val incomingRelationshipIds: List<String>,
    val outgoingRelationshipIds: List<String>,
    val localInboundCount: Int,
    val workspaceInboundCount: Int,
    val crossBuildInboundCount: Int,
    val isWorkspaceUsed: Boolean,
) {
    init {
        requireDistinctAndSorted(incomingRelationshipIds, "Important-symbol incoming relationships")
        requireDistinctAndSorted(outgoingRelationshipIds, "Important-symbol outgoing relationships")
        require(localInboundCount >= 0) { "Important-symbol local inbound count must not be negative" }
        require(workspaceInboundCount >= 0) { "Important-symbol workspace inbound count must not be negative" }
        require(crossBuildInboundCount in 0..workspaceInboundCount) {
            "Important-symbol cross-build inbound count must fit its workspace inbound count"
        }
        require(localInboundCount <= workspaceInboundCount) {
            "Important-symbol local inbound count must fit its workspace inbound count"
        }
        require(isWorkspaceUsed == (workspaceInboundCount > 0)) {
            "Important-symbol workspace-used signal must agree with its inbound count"
        }
    }
}

@Serializable
data class ImportantReasonSnapshot(
    val reason: ImportantSymbolReason,
    val score: Int,
    val label: String,
    val description: String,
) {
    init {
        require(score > 0) { "Important-symbol reason score must be positive" }
        require(label.isNotBlank()) { "Important-symbol reason label must not be blank" }
        require(description.isNotBlank()) { "Important-symbol reason description must not be blank" }
    }
}

@Serializable
enum class ImportantSymbolReason(
    val label: String,
) {
    CROSS_BUILD_INBOUND("Cross-build inbound"),
    HIGH_WORKSPACE_INBOUND("High workspace inbound"),
    MULTIPLE_IMPLEMENTATIONS("Multiple implementations"),
    ENTRY_POINT("Entry point"),
    DEPENDENCY_CYCLE("Dependency cycle"),
    HIGH_WORKSPACE_OUTBOUND("High workspace outbound"),
    UNUSUAL_CONNECTIVITY("Unusual connectivity"),
    ANTI_PATTERN_INVOLVEMENT("Anti-pattern involvement"),
}

@Serializable
data class DependencyInjectionSnapshot(
    val bindings: List<DependencyInjectionBindingSnapshot>,
    val edges: List<DependencyInjectionEdgeSnapshot>,
) {
    init {
        requireUniqueAndSorted(bindings.map(DependencyInjectionBindingSnapshot::id), "dependency-injection bindings")
        requireUniqueAndSorted(edges.map(DependencyInjectionEdgeSnapshot::id), "dependency-injection edges")
        val bindingIds = bindings.mapTo(mutableSetOf(), DependencyInjectionBindingSnapshot::id)
        require(edges.all { it.sourceBindingId in bindingIds && it.targetBindingId in bindingIds }) {
            "Dependency-injection edges must reference known bindings"
        }
    }
}

@Serializable
data class DependencyInjectionBindingSnapshot(
    val id: String,
    val framework: DependencyInjectionFramework,
    val key: String,
    val declaringSymbolId: String? = null,
    val scope: String? = null,
) {
    init {
        requireValidId(id, "Dependency-injection binding")
        require(key.isNotBlank()) { "Dependency-injection key must not be blank" }
        declaringSymbolId?.let { requireValidId(it, "Dependency-injection declaring symbol") }
        require(scope == null || scope.isNotBlank()) { "Dependency-injection scope must not be blank" }
    }
}

@Serializable
enum class DependencyInjectionFramework(
    val label: String,
) {
    DAGGER("Dagger"),
    HILT("Hilt"),
}

@Serializable
data class DependencyInjectionEdgeSnapshot(
    val id: String,
    val sourceBindingId: String,
    val targetBindingId: String,
) {
    init {
        requireValidId(id, "Dependency-injection edge")
        requireValidId(sourceBindingId, "Dependency-injection edge source")
        requireValidId(targetBindingId, "Dependency-injection edge target")
        require(sourceBindingId != targetBindingId) { "Dependency-injection edge endpoints must differ" }
    }
}
