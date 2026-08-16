package zone.clanker.gradle.srcx.model

private const val MINIMUM_CLOSED_COMPONENT_CYCLE_SIZE = 3

/** Structural source evidence for one Gradle project. */
data class ArchitectureSummary(
    val components: List<ArchitectureComponent> = emptyList(),
    val dependencies: List<ArchitectureDependency> = emptyList(),
    val entryPoints: List<ArchitectureEntryPoint> = emptyList(),
    val cycles: List<ArchitectureComponentCycle> = emptyList(),
) {
    init {
        require(components.distinctBy { it.id }.size == components.size) {
            "architecture component IDs must be unique within a project"
        }
        val componentIds = components.mapTo(mutableSetOf()) { it.id }
        require(dependencies.all { dependency -> dependency.from in componentIds && dependency.to in componentIds }) {
            "architecture dependency endpoints must identify project components"
        }
        require(entryPoints.all { entryPoint -> entryPoint.componentId in componentIds }) {
            "architecture entry points must identify project components"
        }
        require(cycles.all { cycle -> cycle.componentIds.dropLast(1).all { it in componentIds } }) {
            "architecture cycle members must identify project components"
        }
        val dependencyPairs = dependencies.mapTo(mutableSetOf()) { it.from to it.to }
        require(cycles.all { cycle -> cycle.componentIds.zipWithNext().all { it in dependencyPairs } }) {
            "architecture cycle steps must identify directed project dependencies"
        }
    }

    val runtimeComponents: List<ArchitectureComponent>
        get() = components.filterNot { it.isTest }

    val runtimeDependencies: List<ArchitectureDependency>
        get() {
            val runtimeIds = runtimeComponents.mapTo(mutableSetOf()) { it.id }
            return dependencies.filter { it.from in runtimeIds && it.to in runtimeIds }
        }
}

/** A closed, directed analyzer-inferred route through source components. */
data class ArchitectureComponentCycle(
    val componentIds: List<String>,
) {
    init {
        require(componentIds.size >= MINIMUM_CLOSED_COMPONENT_CYCLE_SIZE) {
            "a component cycle must contain at least two members and a repeated start"
        }
        require(componentIds.first() == componentIds.last()) { "a component cycle must start and end at the same ID" }
        require(componentIds.dropLast(1).all { it.isNotBlank() }) { "component cycle IDs must not be blank" }
        require(componentIds.dropLast(1).distinct().size == componentIds.size - 1) {
            "a component cycle route must not repeat a member before closing"
        }
    }
}

/** A source component that participates in the internal dependency graph. */
data class ArchitectureComponent(
    val id: String,
    val name: String,
    val packageName: String,
    val packageGroup: String,
    val role: String,
    val layer: ArchitectureLayer,
    val filePath: String,
    val line: Int,
    val isTest: Boolean,
) {
    init {
        require(id.isNotBlank()) { "id must not be blank" }
        require(name.isNotBlank()) { "name must not be blank" }
        require(packageGroup.isNotBlank()) { "packageGroup must not be blank" }
        require(filePath.isNotBlank()) { "filePath must not be blank" }
        require(line > 0) { "line must be > 0" }
    }
}

/** Coarse source layer inferred from package placement. */
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

/** A directional internal dependency: [from] depends on [to]. */
data class ArchitectureDependency(
    val from: String,
    val to: String,
) {
    init {
        require(from.isNotBlank()) { "from must not be blank" }
        require(to.isNotBlank()) { "to must not be blank" }
        require(from != to) { "dependency endpoints must differ" }
    }
}

/** An observed source boundary where an application flow can start. */
data class ArchitectureEntryPoint(
    val componentId: String,
    val reason: String,
    val kind: ArchitectureEntryPointKind,
) {
    init {
        require(componentId.isNotBlank()) { "componentId must not be blank" }
        require(reason.isNotBlank()) { "reason must not be blank" }
    }
}

/** Whether a source root is framework evidence or a dependency-graph inference. */
enum class ArchitectureEntryPointKind {
    EXPLICIT,
    GRAPH_ROOT,
}
