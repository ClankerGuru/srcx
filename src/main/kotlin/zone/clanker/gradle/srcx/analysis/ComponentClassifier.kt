package zone.clanker.gradle.srcx.analysis

/**
 * Architectural layer of a package based on its last segment.
 */
enum class ArchitecturalLayer {
    PRESENTATION,
    DOMAIN,
    DATA,
    MODEL,
    INFRASTRUCTURE,
    TEST,
    OTHER,
}

/**
 * Detect the architectural layer for a given package name.
 * Uses the last segment of the package to determine the layer.
 */
fun detectLayer(packageName: String, isTest: Boolean): ArchitecturalLayer {
    if (isTest) return ArchitecturalLayer.TEST

    val lastSegment = packageName.split(".").lastOrNull()?.lowercase() ?: return ArchitecturalLayer.OTHER

    return when (lastSegment) {
        "task", "ui", "screen", "view", "route", "controller", "activity", "fragment" ->
            ArchitecturalLayer.PRESENTATION
        "usecase", "interactor", "service", "workflow" ->
            ArchitecturalLayer.DOMAIN
        "repository", "datasource", "api", "db", "cache", "dao" ->
            ArchitecturalLayer.DATA
        "model", "entity", "dto", "domain" ->
            ArchitecturalLayer.MODEL
        "config", "configuration", "di", "injection", "plugin" ->
            ArchitecturalLayer.INFRASTRUCTURE
        "test", "mock", "fake", "stub", "fixture" ->
            ArchitecturalLayer.TEST
        else -> ArchitecturalLayer.OTHER
    }
}

/**
 * Detect architectural layers for all classified components.
 * Returns a mapping from package name to its detected layer.
 */
fun detectLayers(components: List<ClassifiedComponent>): Map<String, ArchitecturalLayer> =
    components
        .map { it.source.packageName }
        .distinct()
        .associateWith { pkg ->
            detectLayer(pkg, isTest = false)
        }

/**
 * The kind of entry point a component represents.
 */
enum class EntryPointKind {
    APP,
    TEST,
    MOCK,
}

/**
 * A classified entry point with its component and kind.
 */
data class ClassifiedEntryPoint(
    val component: ClassifiedComponent,
    val kind: EntryPointKind,
)

/**
 * Classify entry points by their kind: APP, TEST, or MOCK.
 *
 * - TEST: file path contains "/test/" or class name ends with "Test" or "Spec"
 * - MOCK: class name contains "Mock", "Fake", or "Stub"
 * - APP: main() functions, controllers, Plugin.apply(), or graph roots
 */
fun classifyEntryPoints(
    components: List<ClassifiedComponent>,
    edges: List<ClassDependency> = emptyList(),
): List<ClassifiedEntryPoint> {
    val result = components.mapNotNull { classifySingleEntryPoint(it) }
    if (result.isNotEmpty()) return result.distinctBy { it.component.source.qualifiedName }

    // Fall back to graph roots
    val roots = findGraphRoots(components, edges)
    return roots.map { ClassifiedEntryPoint(it, EntryPointKind.APP) }
}

private fun classifySingleEntryPoint(component: ClassifiedComponent): ClassifiedEntryPoint? {
    val name = component.source.simpleName
    val filePath = component.source.file.path
    val isTestClass =
        filePath.contains("/test/") || name.endsWith("Test") || name.endsWith("Spec")
    val isMockClass =
        name.contains("Mock") || name.contains("Fake") || name.contains("Stub")
    val isPlugin =
        "apply" in component.source.methods &&
            component.source.supertypes.any { it.contains("Plugin") }

    return when {
        isTestClass -> ClassifiedEntryPoint(component, EntryPointKind.TEST)
        isMockClass -> ClassifiedEntryPoint(component, EntryPointKind.MOCK)
        "main" in component.source.methods -> ClassifiedEntryPoint(component, EntryPointKind.APP)
        component.role == ComponentRole.CONTROLLER -> ClassifiedEntryPoint(component, EntryPointKind.APP)
        isPlugin -> ClassifiedEntryPoint(component, EntryPointKind.APP)
        else -> null
    }
}

private fun findGraphRoots(
    components: List<ClassifiedComponent>,
    edges: List<ClassDependency>,
): List<ClassifiedComponent> {
    if (edges.isEmpty()) return emptyList()
    val hasInbound = edges.map { it.to.source.qualifiedName }.toSet()
    val hasOutbound = edges.map { it.from.source.qualifiedName }.toSet()
    return components.filter {
        it.source.qualifiedName in hasOutbound &&
            it.source.qualifiedName !in hasInbound &&
            !it.source.isDataClass
    }
}

/**
 * What we can objectively observe about a source file's role.
 * Detected from annotations only -- naming is unreliable.
 * When nothing is detected, we just say OTHER and let the graph speak.
 */
enum class ComponentRole(
    val label: String,
) {
    CONTROLLER("Controller"),
    SERVICE("Service"),
    REPOSITORY("Repository"),
    ENTITY("Entity"),
    CONFIGURATION("Configuration"),
    DAO("DAO"),
    MANAGER("Manager"),
    HELPER("Helper"),
    UTIL("Util"),
    OTHER(""),
}

/**
 * A source file classified by its architectural role and package grouping.
 *
 * @property source the parsed source file metadata
 * @property role the detected architectural role
 * @property packageGroup the top-level package segment relative to the base package
 */
data class ClassifiedComponent(
    val source: SourceFileMetadata,
    val role: ComponentRole,
    val packageGroup: String,
)

/** Classify a single source file by detecting its role from annotations and naming. */
fun classifyComponent(source: SourceFileMetadata): ClassifiedComponent {
    val role = detectRole(source)
    return ClassifiedComponent(source, role, "")
}

/**
 * Classify all components and compute package groups relative to
 * the longest common package prefix.
 */
fun classifyAll(sources: List<SourceFileMetadata>): List<ClassifiedComponent> {
    val components = sources.map { classifyComponent(it) }
    val packages = sources.map { it.packageName }.filter { it.isNotEmpty() }
    val basePackage = commonPackagePrefix(packages)

    return components.map { c ->
        val relative =
            if (basePackage.isNotEmpty()) {
                c.source.packageName
                    .removePrefix(basePackage)
                    .removePrefix(".")
            } else {
                c.source.packageName
            }
        val group = relative.split(".").firstOrNull()?.takeIf { it.isNotEmpty() } ?: "(root)"
        c.copy(packageGroup = group)
    }
}

/** Find the longest common package prefix across all packages. */
fun commonPackagePrefix(packages: List<String>): String {
    if (packages.isEmpty()) return ""
    val segments = packages.map { it.split(".") }
    val minLen = segments.minOf { it.size }
    val common = mutableListOf<String>()
    for (i in 0 until minLen) {
        val seg = segments[0][i]
        if (segments.all { it[i] == seg }) common.add(seg) else break
    }
    return common.joinToString(".")
}

/**
 * Identify entry points -- things that look like they start a flow.
 * Detected from: main() functions, annotated endpoints, or root nodes in the graph.
 */
fun findEntryPoints(
    components: List<ClassifiedComponent>,
    edges: List<ClassDependency> = emptyList(),
): List<ClassifiedComponent> {
    val result = mutableListOf<ClassifiedComponent>()

    result.addAll(components.filter { "main" in it.source.methods })
    result.addAll(components.filter { it.role == ComponentRole.CONTROLLER })

    if (result.isNotEmpty()) return result.distinctBy { it.source.qualifiedName }

    if (edges.isNotEmpty()) {
        val hasInbound = edges.map { it.to.source.qualifiedName }.toSet()
        val hasOutbound = edges.map { it.from.source.qualifiedName }.toSet()
        val roots =
            components.filter {
                it.source.qualifiedName in hasOutbound &&
                    it.source.qualifiedName !in hasInbound &&
                    !it.source.isDataClass
            }
        if (roots.isNotEmpty()) return roots
    }

    return emptyList()
}

private val ANNOTATION_ROLE_MAP =
    mapOf(
        "RestController" to ComponentRole.CONTROLLER,
        "Controller" to ComponentRole.CONTROLLER,
        "RequestMapping" to ComponentRole.CONTROLLER,
        "Service" to ComponentRole.SERVICE,
        "Repository" to ComponentRole.REPOSITORY,
        "Entity" to ComponentRole.ENTITY,
        "Table" to ComponentRole.ENTITY,
        "Configuration" to ComponentRole.CONFIGURATION,
        "SpringBootApplication" to ComponentRole.CONFIGURATION,
        "Dao" to ComponentRole.DAO,
        "Database" to ComponentRole.DAO,
    )

private fun detectRole(s: SourceFileMetadata): ComponentRole {
    val annos = s.annotations.map { it.substringAfterLast(".") }.toSet()

    val annotationRole = annos.firstNotNullOfOrNull { ANNOTATION_ROLE_MAP[it] }
    if (annotationRole != null) return annotationRole

    return detectRoleByName(s.simpleName)
}

private fun detectRoleByName(name: String): ComponentRole =
    when {
        name.endsWith("Manager") -> ComponentRole.MANAGER
        name.endsWith("Helper") -> ComponentRole.HELPER
        name.endsWith("Util") || name.endsWith("Utils") || name.endsWith("Utility") -> ComponentRole.UTIL
        else -> ComponentRole.OTHER
    }
