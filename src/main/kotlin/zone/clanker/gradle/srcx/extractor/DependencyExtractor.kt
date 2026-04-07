package zone.clanker.gradle.srcx.extractor

import org.gradle.api.Project
import zone.clanker.gradle.srcx.model.DependencyEntry

/**
 * Extracts dependency information from a Gradle project's configurations.
 *
 * Reads the api, implementation, compileOnly, runtimeOnly, and
 * testImplementation configurations to produce a list of dependency
 * entries with their scope, group, artifact, and version.
 *
 * ```kotlin
 * val extractor = DependencyExtractor(project)
 * val deps = extractor.extract()
 * ```
 *
 * @property project the Gradle project to extract dependencies from
 */
internal class DependencyExtractor(
    private val project: Project,
) {
    private val scopes =
        listOf(
            "api",
            "implementation",
            "compileOnly",
            "runtimeOnly",
            "testImplementation",
        )

    /**
     * Extract all dependencies from the project's configurations.
     *
     * Only processes configurations that exist on the project.
     * Dependencies without a group are skipped.
     *
     * @return a list of all extracted dependency entries
     */
    fun extract(): List<DependencyEntry> {
        val results = mutableListOf<DependencyEntry>()
        for (scope in scopes) {
            val config = project.configurations.findByName(scope) ?: continue
            config.dependencies.forEach { dep ->
                if (dep.group != null) {
                    results.add(
                        DependencyEntry(
                            group = dep.group.orEmpty(),
                            artifact = dep.name,
                            version = dep.version ?: "unspecified",
                            scope = scope,
                        ),
                    )
                }
            }
        }
        return results
    }
}
