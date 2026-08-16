package zone.clanker.docx.web.state

import zone.clanker.report.model.ProjectGraphShard
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame

class SelectedProjectShardTest {
    @Test
    fun promotesAProjectAlreadyLoadedForItsBuildScope() {
        val project = project("project:fixture:app")

        val selected =
            selectedProjectShard(
                selectedProjectId = project.projectId,
                project = null,
                loadedProjects = listOf(project),
            )

        assertSame(project, selected)
    }

    @Test
    fun returnsNoProjectWhenTheSelectionIsNotLoaded() {
        val loaded = project("project:fixture:library")

        assertNull(
            selectedProjectShard(
                selectedProjectId = "project:fixture:app",
                project = null,
                loadedProjects = listOf(loaded),
            ),
        )
    }

    private fun project(projectId: String): ProjectGraphShard =
        ProjectGraphShard(
            projectId = projectId,
            sourceSets = emptyList(),
            files = emptyList(),
            symbols = emptyList(),
            relationships = emptyList(),
        )
}
