package zone.clanker.docx.web.fixture

import zone.clanker.report.model.ProjectShardReference
import zone.clanker.report.model.ProjectSnapshot
import zone.clanker.report.model.WorkspaceDashboardFinding

internal data class DocxScaleProject(
    val dimensions: DocxScaleDimensions,
    val buildIndex: Int,
    val projectIndex: Int,
) {
    val ordinal: Int = buildIndex * dimensions.projectsPerBuild + projectIndex
    val fileCount: Int = dimensions.filesInProject(buildIndex, projectIndex)
    val symbolCount: Int = dimensions.symbolsInProject(buildIndex, projectIndex)
    val id: String =
        "project:scale:${dimensions.profileName}:${padded(buildIndex, BUILD_WIDTH)}:" +
            padded(projectIndex, PROJECT_WIDTH)
    val buildId: String = dimensions.buildId(buildIndex)
    val projectPath: String =
        ":${dimensions.buildName(buildIndex).removePrefix("scale-")}-project-${padded(projectIndex, PROJECT_WIDTH)}"
    val packageName: String = "scale.b${padded(buildIndex, BUILD_WIDTH)}.p${padded(projectIndex, PROJECT_WIDTH)}"
    val shardFile: String =
        "data/shards/project-scale-${dimensions.profileName}-${padded(buildIndex, BUILD_WIDTH)}-" +
            "${padded(projectIndex, PROJECT_WIDTH)}.json"
    val findingId: String =
        "finding:scale:${dimensions.profileName}:${padded(buildIndex, BUILD_WIDTH)}:" +
            padded(projectIndex, PROJECT_WIDTH)
    val dashboardFindingId: String =
        "dashboard-finding:scale:${dimensions.profileName}:${padded(buildIndex, BUILD_WIDTH)}:" +
            padded(projectIndex, PROJECT_WIDTH)

    fun shardReference(): ProjectShardReference = ProjectShardReference(id, shardFile)

    fun snapshot(): ProjectSnapshot =
        ProjectSnapshot(
            id = id,
            buildId = buildId,
            path = projectPath,
            buildFile = "build.gradle.kts",
            sourceDirectories = listOf("src/main/kotlin", "src/test/kotlin"),
        )

    fun dashboardFinding(): WorkspaceDashboardFinding =
        WorkspaceDashboardFinding(
            id = dashboardFindingId,
            buildId = buildId,
            projectId = id,
            sourceSetNames = SOURCE_SET_NAMES,
            finding = DocxScaleRelationshipFactory(this).finding(),
        )

    fun sourceSetId(name: String): String = "$id:source-set:$name"

    fun fileId(index: Int): String = scaleFactId("file", index, FILE_WIDTH)

    fun symbolId(index: Int): String = scaleFactId("symbol", index, SYMBOL_WIDTH)

    fun symbolName(index: Int): String = "Node${padded(index, SYMBOL_WIDTH)}"

    fun referenceId(index: Int): String = scaleFactId("reference", index, SYMBOL_WIDTH)

    fun relationshipId(index: Int): String = scaleFactId("relationship", index, SYMBOL_WIDTH)

    fun dependencyReferenceId(): String = "$id:dependency-reference"

    fun dependencyRelationshipId(): String = "$id:dependency-relationship"

    private fun scaleFactId(
        kind: String,
        index: Int,
        width: Int,
    ): String =
        "$kind:scale:${dimensions.profileName}:${padded(buildIndex, BUILD_WIDTH)}:" +
            "${padded(projectIndex, PROJECT_WIDTH)}:${padded(index, width)}"
}
