package zone.clanker.gradle.srcx.model

/**
 * A single dependency extracted from a project's configurations.
 *
 * Captures the Maven coordinates and the Gradle configuration scope
 * (api, implementation, compileOnly, etc.) for reporting.
 *
 * @property group the Maven group ID (e.g. "org.jetbrains.kotlin")
 * @property artifact the Maven artifact ID (e.g. "kotlin-stdlib")
 * @property version the resolved version string, or "unspecified" if none
 * @property scope the Gradle configuration name (e.g. "implementation")
 */
data class DependencyEntry(
    val group: String,
    val artifact: String,
    val version: String,
    val scope: String,
)
