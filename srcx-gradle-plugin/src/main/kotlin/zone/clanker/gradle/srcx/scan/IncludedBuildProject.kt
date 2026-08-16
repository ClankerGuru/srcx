package zone.clanker.gradle.srcx.scan

import java.io.File

/** One configured project discovered inside a specific included build. */
internal data class IncludedBuildProject(
    val path: String,
    val directory: File,
    val sourceLayout: ProjectSourceLayout,
)
