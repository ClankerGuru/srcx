@file:Suppress("ktlint:standard:filename", "TooManyFunctions")

package zone.clanker.gradle.srcx.analysis

import java.io.File

private const val MAX_IMPORTS = 30
private const val MAX_METHODS = 25
private const val MAX_LINE_COUNT = 1000
private const val MAX_INHERITANCE_DEPTH = 3
private const val MAX_UNTESTED_BEFORE_SUMMARY = 10
private const val MAX_CYCLE_REPORT = 10
private const val UNTESTED_PREVIEW_COUNT = 5

/**
 * A detected anti-pattern in the codebase.
 *
 * @property severity how serious the finding is
 * @property message human-readable description of the anti-pattern
 * @property file the file where the anti-pattern was found (relative)
 * @property suggestion actionable advice for fixing the anti-pattern
 */
data class AntiPattern(
    val severity: Severity,
    val message: String,
    val file: File,
    val suggestion: String,
) {
    /** Severity level of an anti-pattern finding. */
    enum class Severity(
        val icon: String,
    ) {
        FORBIDDEN("\uD83D\uDEAB"),
        WARNING("⚠\uFE0F"),
        INFO("ℹ\uFE0F"),
    }
}

/** Detect anti-patterns across the classified components and dependency edges. */
fun detectAntiPatterns(
    components: List<ClassifiedComponent>,
    edges: List<ClassDependency>,
    rootDir: File,
    forbiddenPackages: Set<String> = zone.clanker.gradle.srcx.Srcx.DEFAULT_FORBIDDEN_PACKAGES,
    forbiddenClassPatterns: Set<String> = zone.clanker.gradle.srcx.Srcx.DEFAULT_FORBIDDEN_CLASS_PATTERNS,
): List<AntiPattern> {
    val resolver = SupertypeResolver(components)
    val patterns = mutableListOf<AntiPattern>()

    patterns.addAll(detectSmellClasses(components, rootDir, forbiddenPackages))
    patterns.addAll(detectForbiddenNames(components, rootDir, forbiddenPackages))
    patterns.addAll(detectForbiddenClassNames(components, rootDir, forbiddenClassPatterns))
    patterns.addAll(detectSingleImplInterfaces(components, resolver, rootDir))
    patterns.addAll(detectGodClasses(components, rootDir))
    patterns.addAll(detectDeepInheritance(components, resolver, rootDir))
    patterns.addAll(detectCircularDeps(edges))
    patterns.addAll(detectDependencyInversionViolations(components, resolver, rootDir))
    patterns.addAll(detectMissingTests(components, rootDir))

    return patterns.sortedWith(compareBy({ it.severity }, { it.file.path }))
}

private class SupertypeResolver(
    components: List<ClassifiedComponent>,
) {
    private val byQualifiedName = components.associateBy { it.source.qualifiedName }
    private val bySimpleName = components.groupBy { it.source.simpleName }

    fun resolve(owner: ClassifiedComponent, supertype: String): ClassifiedComponent? =
        if ('.' in supertype) {
            byQualifiedName[supertype]
        } else {
            owner.source.imports
                .firstOrNull { it.substringAfterLast(".") == supertype }
                ?.let { byQualifiedName[it] }
                ?: byQualifiedName["${owner.source.packageName}.$supertype"]
                ?: bySimpleName[supertype]?.singleOrNull()
        }

    fun findImplementors(iface: ClassifiedComponent): List<ClassifiedComponent> =
        bySimpleName.values.flatten().filter { c ->
            c.source.supertypes.any { supertype -> resolve(c, supertype) === iface }
        }
}

private fun detectSmellClasses(
    components: List<ClassifiedComponent>,
    rootDir: File,
    forbiddenPackages: Set<String>,
): List<AntiPattern> =
    components
        .filter { it.role in setOf(ComponentRole.MANAGER, ComponentRole.HELPER, ComponentRole.UTIL) }
        .map { c ->
            val roleLabel = c.role.name.lowercase()
            val lastSegment = c.source.packageName.substringAfterLast(".")
            val severity =
                if (lastSegment in forbiddenPackages) {
                    AntiPattern.Severity.FORBIDDEN
                } else {
                    AntiPattern.Severity.WARNING
                }
            AntiPattern(
                severity = severity,
                message = "`${c.source.simpleName}` is a $roleLabel class",
                file = c.source.file.relativeTo(rootDir),
                suggestion =
                    "Behavior in $roleLabel classes usually belongs in a specific class " +
                        "closer to where it's used. Consider moving methods to the classes that actually need them.",
            )
        }

@Suppress("UnusedParameter")
private fun detectForbiddenNames(
    components: List<ClassifiedComponent>,
    rootDir: File,
    forbiddenPackages: Set<String>,
): List<AntiPattern> {
    val patterns = mutableListOf<AntiPattern>()

    val inForbiddenPackages =
        components.filter { c ->
            val lastSegment = c.source.packageName.substringAfterLast(".")
            lastSegment in forbiddenPackages
        }
    val packageGroups = inForbiddenPackages.groupBy { it.source.packageName }
    for ((pkg, _) in packageGroups) {
        val lastSegment = pkg.substringAfterLast(".")
        patterns.add(
            AntiPattern(
                severity = AntiPattern.Severity.FORBIDDEN,
                message = "Package `$pkg` uses forbidden name `$lastSegment`",
                file = File("."),
                suggestion =
                    "Rename the package to describe what it actually does " +
                        "instead of using a generic catch-all name.",
            ),
        )
    }

    return patterns
}

private fun detectForbiddenClassNames(
    components: List<ClassifiedComponent>,
    rootDir: File,
    forbiddenPatterns: Set<String>,
): List<AntiPattern> =
    components
        .filter { c -> forbiddenPatterns.any { pattern -> c.source.simpleName.contains(pattern) } }
        .filter {
            !it.source.file.path
                .contains("/test/")
        }.map { c ->
            val matched = forbiddenPatterns.first { c.source.simpleName.contains(it) }
            AntiPattern(
                severity = AntiPattern.Severity.WARNING,
                message = "`${c.source.simpleName}` contains forbidden pattern `$matched`",
                file = c.source.file.relativeTo(rootDir),
                suggestion = "Rename to describe what the class does instead of using a generic name.",
            )
        }

private fun detectDependencyInversionViolations(
    components: List<ClassifiedComponent>,
    resolver: SupertypeResolver,
    rootDir: File,
): List<AntiPattern> {
    val nonTestComponents =
        components.filter { c ->
            !c.source.file.path
                .contains("/test/") &&
                !c.source.file.path
                    .contains("\\test\\") &&
                !c.source.isInterface
        }

    val patterns = mutableListOf<AntiPattern>()

    nonTestComponents.forEach { c ->
        patterns.addAll(checkImportsForDiViolations(c, resolver, rootDir))
    }

    return patterns.distinctBy { it.message }
}

private fun checkImportsForDiViolations(
    c: ClassifiedComponent,
    resolver: SupertypeResolver,
    rootDir: File,
): List<AntiPattern> =
    c.source.imports.mapNotNull { importedFqn ->
        val importedSimpleName = importedFqn.substringAfterLast(".")
        val resolved = resolver.resolve(c, importedSimpleName) ?: return@mapNotNull null
        val isAbstraction = resolved.source.isInterface || resolved.source.isAbstract || resolved.source.isDataClass
        if (isAbstraction) return@mapNotNull null
        buildDiViolationPattern(c, resolved, resolver, rootDir)
    }

private fun buildDiViolationPattern(
    c: ClassifiedComponent,
    resolved: ClassifiedComponent,
    resolver: SupertypeResolver,
    rootDir: File,
): AntiPattern {
    val implementedInterfaces =
        resolved.source.supertypes
            .mapNotNull { supertype ->
                resolver.resolve(resolved, supertype)
            }.filter { it.source.isInterface }

    return if (implementedInterfaces.isNotEmpty()) {
        val ifaceName = implementedInterfaces.first().source.simpleName
        AntiPattern(
            severity = AntiPattern.Severity.WARNING,
            message =
                "Constructor takes concrete `${resolved.source.simpleName}` " +
                    "instead of interface `$ifaceName`",
            file = c.source.file.relativeTo(rootDir),
            suggestion =
                "Depend on the interface `$ifaceName` instead of the concrete class " +
                    "to improve testability and flexibility.",
        )
    } else {
        AntiPattern(
            severity = AntiPattern.Severity.INFO,
            message =
                "Dependency on concrete class `${resolved.source.simpleName}` " +
                    "in `${c.source.simpleName}`",
            file = c.source.file.relativeTo(rootDir),
            suggestion = "Consider extracting an interface for `${resolved.source.simpleName}`.",
        )
    }
}

private fun detectSingleImplInterfaces(
    components: List<ClassifiedComponent>,
    resolver: SupertypeResolver,
    rootDir: File,
): List<AntiPattern> =
    components.filter { it.source.isInterface }.mapNotNull { iface ->
        val impls = resolver.findImplementors(iface)
        if (impls.size == 1) {
            val impl = impls[0]
            AntiPattern(
                severity = AntiPattern.Severity.INFO,
                message =
                    "Interface `${iface.source.simpleName}` has only one implementation: " +
                        "`${impl.source.simpleName}`",
                file = iface.source.file.relativeTo(rootDir),
                suggestion =
                    "If this interface isn't meant for testing or future extension, " +
                        "consider using `${impl.source.simpleName}` directly.",
            )
        } else {
            null
        }
    }

private fun detectGodClasses(
    components: List<ClassifiedComponent>,
    rootDir: File,
): List<AntiPattern> =
    components
        .filter {
            it.source.imports.size > MAX_IMPORTS ||
                it.source.methods.size > MAX_METHODS ||
                it.source.lineCount > MAX_LINE_COUNT
        }.filter { it.role != ComponentRole.CONFIGURATION }
        .map { c ->
            val reasons = buildGodClassReasons(c)
            AntiPattern(
                severity = AntiPattern.Severity.WARNING,
                message = "`${c.source.simpleName}` may be doing too much (${reasons.joinToString(", ")})",
                file = c.source.file.relativeTo(rootDir),
                suggestion =
                    "Consider splitting into smaller, focused classes. " +
                        "Each class should have a single responsibility.",
            )
        }

private fun buildGodClassReasons(c: ClassifiedComponent): List<String> {
    val reasons = mutableListOf<String>()
    if (c.source.imports.size > MAX_IMPORTS) reasons.add("${c.source.imports.size} imports")
    if (c.source.methods.size > MAX_METHODS) reasons.add("${c.source.methods.size} methods")
    if (c.source.lineCount > MAX_LINE_COUNT) reasons.add("${c.source.lineCount} lines")
    return reasons
}

private fun detectDeepInheritance(
    components: List<ClassifiedComponent>,
    resolver: SupertypeResolver,
    rootDir: File,
): List<AntiPattern> =
    components
        .filter { !it.source.isInterface }
        .mapNotNull { c ->
            val d = inheritanceDepth(c, resolver)
            if (d >= MAX_INHERITANCE_DEPTH) {
                val chain = buildChain(c, resolver).joinToString(" -> ")
                AntiPattern(
                    severity = AntiPattern.Severity.WARNING,
                    message = "`${c.source.simpleName}` has inheritance depth $d: $chain",
                    file = c.source.file.relativeTo(rootDir),
                    suggestion = "Deep inheritance makes code rigid. Prefer composition.",
                )
            } else {
                null
            }
        }

@Suppress("ReturnCount")
private fun inheritanceDepth(
    c: ClassifiedComponent,
    resolver: SupertypeResolver,
    visited: Set<String> = emptySet(),
): Int {
    if (c.source.qualifiedName in visited) return 0
    val parentName = c.source.supertypes.firstOrNull() ?: return 0
    val parent = resolver.resolve(c, parentName) ?: return 0
    if (parent.source.isInterface) return 0
    return 1 + inheritanceDepth(parent, resolver, visited + c.source.qualifiedName)
}

@Suppress("LoopWithTooManyJumpStatements")
private fun buildChain(c: ClassifiedComponent, resolver: SupertypeResolver): List<String> {
    val chain = mutableListOf(c.source.simpleName)
    var current = c
    val visited = mutableSetOf(c.source.qualifiedName)
    while (true) {
        val parentName = current.source.supertypes.firstOrNull() ?: break
        val parent = resolver.resolve(current, parentName) ?: break
        if (parent.source.isInterface || parent.source.qualifiedName in visited) break
        chain.add(parent.source.simpleName)
        visited.add(parent.source.qualifiedName)
        current = parent
    }
    return chain
}

private fun detectCircularDeps(edges: List<ClassDependency>): List<AntiPattern> {
    val cycles = findCycles(edges)
    return cycles.take(MAX_CYCLE_REPORT).map { cycle ->
        AntiPattern(
            severity = AntiPattern.Severity.WARNING,
            message = "Circular dependency: ${cycle.joinToString(" -> ")}",
            file = File("."),
            suggestion =
                "Break the cycle by extracting a shared interface or " +
                    "moving shared logic to a separate class.",
        )
    }
}

private fun detectMissingTests(
    components: List<ClassifiedComponent>,
    rootDir: File,
): List<AntiPattern> {
    val testNames =
        components
            .filter {
                it.source.file.path
                    .contains("/test/") ||
                    it.source.file.path
                        .contains("\\test\\")
            }.map {
                it.source.simpleName
                    .removeSuffix("Test")
                    .removeSuffix("Spec")
            }.toSet()

    val untested =
        components
            .filter {
                !it.source.file.path
                    .contains("/test/") &&
                    !it.source.file.path
                        .contains("\\test\\")
            }.filter { isTestableComponent(it) }
            .filter { it.source.simpleName !in testNames }

    return if (untested.size > MAX_UNTESTED_BEFORE_SUMMARY) {
        listOf(
            AntiPattern(
                severity = AntiPattern.Severity.INFO,
                message = "${untested.size} classes have no corresponding test file",
                file = File("."),
                suggestion =
                    "Consider adding tests for key components, especially: " +
                        untested.take(UNTESTED_PREVIEW_COUNT).joinToString(", ") { "`${it.source.simpleName}`" },
            ),
        )
    } else {
        untested.map { c ->
            AntiPattern(
                severity = AntiPattern.Severity.INFO,
                message = "`${c.source.simpleName}` has no test",
                file = c.source.file.relativeTo(rootDir),
                suggestion = "Consider adding `${c.source.simpleName}Test`.",
            )
        }
    }
}

private fun isTestableComponent(c: ClassifiedComponent): Boolean =
    c.role != ComponentRole.OTHER &&
        c.role != ComponentRole.CONFIGURATION &&
        c.role != ComponentRole.ENTITY &&
        !c.source.isInterface &&
        !c.source.isDataClass
