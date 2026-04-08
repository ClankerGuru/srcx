package zone.clanker.gradle.srcx.model

/**
 * A Maven group ID (e.g. "org.jetbrains.kotlin").
 *
 * @property value the group ID, must not be blank and must contain no whitespace
 */
@JvmInline
value class ArtifactGroup(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "ArtifactGroup must not be blank" }
        require(!value.any { it.isWhitespace() }) { "ArtifactGroup must not contain whitespace" }
    }

    override fun toString(): String = value
}

/**
 * A Maven artifact ID (e.g. "kotlin-stdlib").
 *
 * @property value the artifact ID, must not be blank
 */
@JvmInline
value class ArtifactName(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "ArtifactName must not be blank" }
    }

    override fun toString(): String = value
}

/**
 * A dependency version string (e.g. "2.1.20" or "unspecified").
 *
 * @property value the version string, must not be blank
 */
@JvmInline
value class ArtifactVersion(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "ArtifactVersion must not be blank" }
    }

    override fun toString(): String = value
}

/**
 * A single dependency extracted from a project's configurations.
 *
 * Captures the Maven coordinates and the Gradle configuration scope
 * (api, implementation, compileOnly, etc.) for reporting.
 *
 * @property group the Maven group ID (e.g. "org.jetbrains.kotlin")
 * @property artifact the Maven artifact ID (e.g. "kotlin-stdlib")
 * @property version the resolved version string, or "unspecified" if none
 * @property scope the Gradle configuration name (e.g. "implementation"), must not be blank
 */
data class DependencyEntry(
    val group: ArtifactGroup,
    val artifact: ArtifactName,
    val version: ArtifactVersion,
    val scope: String,
) {
    init {
        require(scope.isNotBlank()) { "scope must not be blank" }
    }
}
