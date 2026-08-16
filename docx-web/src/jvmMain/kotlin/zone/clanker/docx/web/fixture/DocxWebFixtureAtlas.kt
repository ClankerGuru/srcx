package zone.clanker.docx.web.fixture

import zone.clanker.docx.web.atlas.projection.AtlasProjectFrameRequest
import zone.clanker.docx.web.atlas.projection.AtlasScopeSelection
import zone.clanker.docx.web.atlas.projection.atlasSelectionFrame
import zone.clanker.report.model.AtlasBuild
import zone.clanker.report.model.AtlasCount
import zone.clanker.report.model.AtlasEdge
import zone.clanker.report.model.AtlasEdgeCategory
import zone.clanker.report.model.AtlasFrame
import zone.clanker.report.model.AtlasLens
import zone.clanker.report.model.AtlasNode
import zone.clanker.report.model.AtlasNodeType
import zone.clanker.report.model.AtlasScope
import zone.clanker.report.model.AtlasScopeKind
import zone.clanker.report.model.ProjectGraphShard
import zone.clanker.report.model.WorkspaceAtlasOverviewShard
import zone.clanker.report.model.WorkspaceSummaryShard

internal fun fixtureAtlasOverviews(
    summary: WorkspaceSummaryShard,
    projects: List<ProjectGraphShard>,
    filesOverview: AtlasFrame,
): WorkspaceAtlasOverviewShard {
    val selection = AtlasScopeSelection(buildIds = summary.builds.mapTo(mutableSetOf()) { build -> build.id })
    return WorkspaceAtlasOverviewShard(
        AtlasLens.entries.map { lens ->
            if (lens == AtlasLens.FILES) {
                filesOverview
            } else {
                atlasSelectionFrame(summary, selection, projects, AtlasProjectFrameRequest(lens))
                    .copy(frameId = "atlas:${summary.workspace.id}:${lens.name.lowercase()}:overview")
            }
        },
    )
}

internal fun fixtureAtlasOverview(): AtlasFrame =
    AtlasFrame(
        frameId = "atlas:workspace:fixture:files:overview",
        scope = AtlasScope(AtlasScopeKind.WORKSPACE, fixtureWorkspaceIdentity.id, fixtureWorkspaceIdentity.name),
        lens = AtlasLens.FILES,
        builds = fixtureAtlasBuilds(),
        nodes = fixtureAtlasNodes(),
        edges = listOf(fixtureAtlasRelationship()),
        totalNodeCount = 3,
        matchingNodeCount = 3,
        pageIndex = 0,
        pageCount = 1,
        totalRelationshipRecordCount = 1,
        shownRelationshipRecordCount = 1,
    )

private fun fixtureAtlasBuilds(): List<AtlasBuild> =
    listOf(
        AtlasBuild("build:fixture", "fixture", "Root build", "hsl(321 58% 66%)"),
        AtlasBuild(
            "build:fixture:library",
            "fixture-library",
            "Included build",
            "hsl(295 58% 66%)",
        ),
    )

private fun fixtureAtlasNodes(): List<AtlasNode> =
    listOf(
        AtlasNode(
            id = "file:fixture:consumer",
            type = AtlasNodeType.FILE,
            name = "Consumer.kt",
            path = "src/main/kotlin/fixture/Consumer.kt",
            buildId = "build:fixture",
            buildName = "fixture",
            projectId = "project:fixture:app",
            projectPath = ":app",
            sourceSet = "main",
            sourceFileId = "file:fixture:consumer",
            language = "Kotlin",
            kind = "KOTLIN",
            findingIds = listOf("finding:fixture:boundary"),
            relationshipRecordCount = 1,
        ),
        AtlasNode(
            id = "file:fixture:library-port",
            type = AtlasNodeType.FILE,
            name = "LibraryPort.kt",
            path = "src/main/kotlin/fixture/library/LibraryPort.kt",
            buildId = "build:fixture:library",
            buildName = "fixture-library",
            projectId = "project:fixture:library",
            projectPath = ":library",
            sourceSet = "main",
            sourceFileId = "file:fixture:library-port",
            language = "Kotlin",
            kind = "KOTLIN",
        ),
        AtlasNode(
            id = "file:fixture:target",
            type = AtlasNodeType.FILE,
            name = "Target.kt",
            path = "src/main/kotlin/fixture/Target.kt",
            buildId = "build:fixture",
            buildName = "fixture",
            projectId = "project:fixture:app",
            projectPath = ":app",
            sourceSet = "main",
            sourceFileId = "file:fixture:target",
            language = "Kotlin",
            kind = "KOTLIN",
            relationshipRecordCount = 1,
        ),
    )

private fun fixtureAtlasRelationship(): AtlasEdge =
    AtlasEdge(
        id = "relationship:fixture:constructor",
        sourceId = "file:fixture:consumer",
        targetId = "file:fixture:target",
        category = AtlasEdgeCategory.CALLS,
        relationshipIds = listOf("relationship:fixture:constructor"),
        referenceIds = listOf("reference:fixture:constructor"),
        recordCount = 1,
        kindCounts = listOf(AtlasCount("CONSTRUCTOR", "Constructor", 1)),
        evidenceCounts = listOf(AtlasCount("DIRECT", "Direct", 1)),
    )
