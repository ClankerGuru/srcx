package zone.clanker.docx.web.atlas.session

import zone.clanker.report.model.BuildKind
import zone.clanker.report.model.BuildSnapshot
import zone.clanker.report.model.ProjectShardReference
import zone.clanker.report.model.ProjectSnapshot
import zone.clanker.report.model.WorkspaceIdentity
import zone.clanker.report.model.WorkspaceSourceSetSummary
import zone.clanker.report.model.WorkspaceSummaryShard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkspaceAtlasHierarchyTest {
    @Test
    fun `builds projects and source sets retain authoritative parents`() {
        val hierarchy = workspaceAtlasHierarchy(summary())

        assertEquals(
            listOf("build", "project", "source"),
            hierarchy.pathTo(AtlasSemanticId("source")).map(AtlasSemanticId::value),
        )
        assertTrue(hierarchy.containsAt(AtlasSemanticId("build"), AtlasHierarchyLevel.BUILD))
        assertTrue(hierarchy.containsAt(AtlasSemanticId("project"), AtlasHierarchyLevel.PROJECT))
        assertTrue(hierarchy.containsAt(AtlasSemanticId("source"), AtlasHierarchyLevel.SOURCE_SET))
    }

    private fun summary(): WorkspaceSummaryShard =
        WorkspaceSummaryShard(
            workspace = WorkspaceIdentity("workspace", "Workspace"),
            builds = listOf(BuildSnapshot("build", "Build", BuildKind.ROOT, ".")),
            projects = listOf(ProjectSnapshot("project", "build", ":app", "build.gradle.kts")),
            sourceSets = listOf(WorkspaceSourceSetSummary("source", "project", "main", 1)),
            projectShards = listOf(ProjectShardReference("project", "data/projects/project.json")),
        )
}
