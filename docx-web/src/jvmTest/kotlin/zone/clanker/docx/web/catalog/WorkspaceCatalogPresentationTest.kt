package zone.clanker.docx.web.catalog

import zone.clanker.report.model.BuildKind
import zone.clanker.report.model.BuildSnapshot
import zone.clanker.report.model.ProjectShardReference
import zone.clanker.report.model.ProjectSnapshot
import zone.clanker.report.model.WorkspaceIdentity
import zone.clanker.report.model.WorkspaceSourceSetSummary
import zone.clanker.report.model.WorkspaceSummaryShard
import kotlin.test.Test
import kotlin.test.assertEquals

class WorkspaceCatalogPresentationTest {
    @Test
    fun ordersTheRootBuildFirstAndProjectsByPathWithinDisplaySortedBuilds() {
        val summary = summary(projects)

        assertEquals(listOf("build:m", "build:z", "build:a"), orderedBuilds(summary).map(BuildSnapshot::id))
        assertEquals(
            listOf("project:d", "project:c", "project:b", "project:a"),
            orderedProjects(summary).map(ProjectSnapshot::id),
        )
        assertEquals(
            listOf(
                "source-set:d:main",
                "source-set:d:test",
                "source-set:c:custom",
                "source-set:b:main",
                "source-set:b:test",
                "source-set:a:main",
            ),
            orderedSourceSets(summary).map(WorkspaceSourceSetSummary::id),
        )
        assertEquals("project:d", initialProject(summary)?.id)
        assertEquals(orderedProjects(summary), visibleProjects(summary, ""))
        assertEquals(
            listOf("project:d", "project:b"),
            visibleProjects(summary, "alpha").map(ProjectSnapshot::id),
        )
    }

    @Test
    fun fallsBackToTheFirstDisplaySortedIncludedBuildWhenTheRootHasNoProject() {
        val summary = summary(projects.take(2))

        assertEquals("project:b", initialProject(summary)?.id)
    }

    private fun summary(projects: List<ProjectSnapshot>): WorkspaceSummaryShard =
        WorkspaceSummaryShard(
            workspace = WorkspaceIdentity("workspace:presentation", "Presentation workspace"),
            builds = builds,
            projects = projects,
            sourceSets = sourceSets.filter { sourceSet -> projects.any { it.id == sourceSet.projectId } },
            projectShards =
                projects.map { project ->
                    ProjectShardReference(project.id, "data/${project.id.substringAfterLast(':')}.json")
                },
        )

    private companion object {
        val builds =
            listOf(
                BuildSnapshot("build:a", "Zebra", BuildKind.INCLUDED, "zebra"),
                BuildSnapshot("build:m", "Root", BuildKind.ROOT, "."),
                BuildSnapshot("build:z", "Alpha", BuildKind.INCLUDED, "alpha"),
            )
        val projects =
            listOf(
                ProjectSnapshot("project:a", "build:a", ":zeta", "zeta.gradle.kts"),
                ProjectSnapshot("project:b", "build:z", ":beta", "beta.gradle.kts"),
                ProjectSnapshot("project:c", "build:m", ":zulu", "zulu.gradle.kts"),
                ProjectSnapshot("project:d", "build:m", ":alpha", "alpha.gradle.kts"),
            )
        val sourceSets =
            listOf(
                WorkspaceSourceSetSummary("source-set:a:main", "project:a", "main", 1),
                WorkspaceSourceSetSummary("source-set:b:main", "project:b", "main", 2),
                WorkspaceSourceSetSummary("source-set:b:test", "project:b", "test", 1),
                WorkspaceSourceSetSummary("source-set:c:custom", "project:c", "custom", 3),
                WorkspaceSourceSetSummary("source-set:d:main", "project:d", "main", 4),
                WorkspaceSourceSetSummary("source-set:d:test", "project:d", "test", 2),
            )
    }
}
