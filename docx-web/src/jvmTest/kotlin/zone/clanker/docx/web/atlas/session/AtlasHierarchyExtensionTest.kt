package zone.clanker.docx.web.atlas.session

import zone.clanker.report.model.WorkspaceGraphAvailabilityState
import zone.clanker.report.model.WorkspaceGraphFacet
import zone.clanker.report.model.WorkspaceGraphFactAvailability
import zone.clanker.report.model.WorkspaceGraphFactKind
import zone.clanker.report.model.WorkspaceGraphNode
import zone.clanker.report.model.WorkspaceGraphNodeHierarchy
import zone.clanker.report.model.WorkspaceGraphNodeKind
import zone.clanker.report.model.WorkspaceGraphNodePresentation
import zone.clanker.report.model.WorkspaceGraphNodeRole
import zone.clanker.report.model.WorkspaceGraphSlice
import zone.clanker.report.model.WorkspaceGraphSliceContent
import zone.clanker.report.model.WorkspaceGraphSliceCounts
import zone.clanker.report.model.WorkspaceGraphSliceTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class AtlasHierarchyExtensionTest {
    @Test
    fun `bounded slice nodes extend the canonical hierarchy without resetting navigation`() {
        val build = AtlasHierarchyNode(id("build"), AtlasHierarchyLevel.BUILD)
        val project = AtlasHierarchyNode(id("project"), AtlasHierarchyLevel.PROJECT, build.id)
        val session =
            AtlasSession(
                generation = AtlasGeneration(id("workspace"), "generation"),
                hierarchy = AtlasHierarchyIndex.of(listOf(build, project)),
                scope = AtlasScope(listOf(AtlasScopeLevelSelection(AtlasHierarchyLevel.PROJECT, listOf(project.id)))),
            )
        val file =
            graphNode(
                id = "file",
                kind = WorkspaceGraphNodeKind.FILE,
                hierarchy = graphNodeHierarchy(parentId = "project", depth = 2),
            )

        val extended = reduceAtlasSession(session, AtlasHierarchyExtended(listOfNotNull(file.atlasHierarchyNode())))

        assertEquals(session.scope, extended.scope)
        assertEquals(session.history, extended.history)
        assertEquals(listOf(build.id, project.id, id("file")), extended.hierarchy.pathTo(id("file")))
    }

    @Test
    fun `hierarchy extension rejects semantic id redefinition`() {
        val build = AtlasHierarchyNode(id("build"), AtlasHierarchyLevel.BUILD)
        val index = AtlasHierarchyIndex.of(listOf(build))

        assertFailsWith<IllegalArgumentException> {
            index.extending(listOf(build.copy(level = AtlasHierarchyLevel.PROJECT)))
        }
    }

    @Test
    fun `settling a bounded slice extends its hierarchy atomically`() {
        val build = AtlasHierarchyNode(id("build"), AtlasHierarchyLevel.BUILD)
        val project = AtlasHierarchyNode(id("project"), AtlasHierarchyLevel.PROJECT, build.id)
        val source = AtlasHierarchyNode(id("source"), AtlasHierarchyLevel.SOURCE_SET, project.id)
        val initial =
            AtlasSession(
                generation = AtlasGeneration(id("workspace"), "generation"),
                hierarchy = AtlasHierarchyIndex.of(listOf(build, project, source)),
            )
        val pending = reduceAtlasSession(initial, AtlasRequestStarted(initial.workspaceGraphRequest(SOURCE_FACET)))
        val token = assertIs<AtlasRequestState.Pending>(pending.request).token

        val settled = reduceAtlasSession(pending, AtlasRequestSucceeded(token, graphSliceWithPackage()))

        assertIs<AtlasRequestState.Settled>(settled.request)
        assertEquals(
            listOf(build.id, project.id, source.id, id("package-prefix"), id("package")),
            settled.hierarchy.pathTo(id("package")),
        )
    }
}

private fun graphSliceWithPackage(): WorkspaceGraphSlice {
    val nodes =
        listOf(
            graphNode("workspace", WorkspaceGraphNodeKind.WORKSPACE, graphNodeHierarchy(null, 0, 1, 5)),
            graphNode("build", WorkspaceGraphNodeKind.BUILD, graphNodeHierarchy("workspace", 1, 1, 4)),
            graphNode("project", WorkspaceGraphNodeKind.PROJECT, graphNodeHierarchy("build", 2, 1, 3)),
            graphNode("source", WorkspaceGraphNodeKind.SOURCE_SET, graphNodeHierarchy("project", 3, 1, 2)),
            graphNode("package-prefix", WorkspaceGraphNodeKind.PACKAGE, graphNodeHierarchy("source", 4, 1, 1)),
            graphNode("package", WorkspaceGraphNodeKind.PACKAGE, graphNodeHierarchy("package-prefix", 5)),
        ).sortedBy(WorkspaceGraphNode::id)
    return WorkspaceGraphSlice(
        target = WorkspaceGraphSliceTarget("workspace", "generation", SOURCE_FACET),
        content =
            WorkspaceGraphSliceContent(
                availability =
                    WorkspaceGraphFactKind.entries.sortedBy(WorkspaceGraphFactKind::name).map { fact ->
                        WorkspaceGraphFactAvailability(fact, WorkspaceGraphAvailabilityState.COMPLETE, 0)
                    },
                nodes = nodes,
                relations = emptyList(),
            ),
        counts = WorkspaceGraphSliceCounts(nodes.size.toLong(), 0),
    )
}

private fun graphNode(
    id: String,
    kind: WorkspaceGraphNodeKind,
    hierarchy: WorkspaceGraphNodeHierarchy,
): WorkspaceGraphNode =
    WorkspaceGraphNode(
        id = id,
        kind = kind,
        hierarchy = hierarchy,
        presentation = WorkspaceGraphNodePresentation(label = id),
        roles = listOf(WorkspaceGraphNodeRole.PRIMARY),
    )

private fun graphNodeHierarchy(
    parentId: String?,
    depth: Int,
    directChildCount: Long = 0,
    descendantCount: Long = 0,
): WorkspaceGraphNodeHierarchy =
    WorkspaceGraphNodeHierarchy(
        parentId = parentId,
        depth = depth,
        directChildCount = directChildCount,
        descendantCount = descendantCount,
    )

private fun id(value: String): AtlasSemanticId = AtlasSemanticId(value)

private val SOURCE_FACET = WorkspaceGraphFacet.SOURCE
