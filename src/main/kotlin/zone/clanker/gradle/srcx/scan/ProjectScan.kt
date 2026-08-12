package zone.clanker.gradle.srcx.scan

import zone.clanker.gradle.srcx.model.ProjectPath
import zone.clanker.gradle.srcx.model.ProjectSummary
import zone.clanker.gradle.srcx.model.Reference
import zone.clanker.gradle.srcx.model.SourceSetName
import zone.clanker.gradle.srcx.model.Symbol

/** Raw PSI facts for one project-relative source file. */
internal data class ProjectFileScan(
    val sourceSet: SourceSetName,
    val projectRelativeFile: String,
    val declarations: List<Symbol>,
    val references: List<Reference>,
)

/** Raw source facts and the compatible legacy summary for one owned Gradle project. */
internal data class ProjectScan(
    val build: String,
    val projectPath: ProjectPath,
    val files: List<ProjectFileScan>,
    val summary: ProjectSummary,
) {
    init {
        require(build.isNotBlank()) { "build must not be blank" }
        require(summary.projectPath == projectPath) { "summary project path must match scan project path" }
    }
}
