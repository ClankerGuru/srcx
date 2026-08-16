package zone.clanker.gradle.docx.site

import zone.clanker.report.model.ProjectGraphShard

internal data class ProjectedSourceContent(
    val projectId: String,
    val fileId: String,
    val content: String,
)

internal data class SourceSeparatedProjectGraphs(
    val graphs: List<ProjectGraphShard>,
    val contents: List<ProjectedSourceContent>,
)

internal fun List<ProjectGraphShard>.separateSourceContents(): SourceSeparatedProjectGraphs {
    val contents =
        flatMap { project ->
            project.files.mapNotNull { file ->
                file.content?.let { content ->
                    ProjectedSourceContent(project.projectId, file.id, content)
                }
            }
        }
    val graphs =
        map { project ->
            project.copy(files = project.files.map { file -> file.copy(content = null) })
        }
    return SourceSeparatedProjectGraphs(graphs, contents)
}
