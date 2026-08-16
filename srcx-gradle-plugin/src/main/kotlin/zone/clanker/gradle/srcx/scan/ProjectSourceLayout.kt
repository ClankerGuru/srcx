package zone.clanker.gradle.srcx.scan

import java.io.File
import java.io.Serializable

/** Immutable execution-time source layout captured from one configured Gradle project. */
data class ProjectSourceLayout(
    val sourceSets: List<ConfiguredSourceSet>,
    val excludedDirectories: List<File>,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
