package zone.clanker.gradle.srcx.scan

import zone.clanker.gradle.srcx.model.SourceSetName
import java.io.File
import java.io.Serializable

/** Configured source roots owned by one Gradle source set. */
data class ConfiguredSourceSet(
    val name: SourceSetName,
    val directories: List<File>,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
