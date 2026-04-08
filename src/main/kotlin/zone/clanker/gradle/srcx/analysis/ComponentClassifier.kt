package zone.clanker.gradle.srcx.analysis

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
