package zone.clanker.docx.web.atlas.projection

import zone.clanker.report.model.AtlasLens
import zone.clanker.report.model.BuildKind
import zone.clanker.report.model.BuildSnapshot
import zone.clanker.report.model.DeclarationSemantic
import zone.clanker.report.model.ProjectGraphShard
import zone.clanker.report.model.ProjectShardReference
import zone.clanker.report.model.ProjectSnapshot
import zone.clanker.report.model.SourceFileSnapshot
import zone.clanker.report.model.SourceLanguage
import zone.clanker.report.model.SourceSetSnapshot
import zone.clanker.report.model.SymbolKind
import zone.clanker.report.model.SymbolSnapshot
import zone.clanker.report.model.WorkspaceIdentity
import zone.clanker.report.model.WorkspaceSummaryShard
import kotlin.test.Test
import kotlin.test.assertEquals

class AtlasFilterProjectionTest {
    @Test
    fun filtersAProjectByMultipleSourceSetsAndDeclarationKindsDeterministically() {
        val project = AtlasFilterFixture.projects.first()
        val sourceSetIds =
            linkedSetOf(
                AtlasFilterFixture.sourceSetId("alpha", "main"),
                AtlasFilterFixture.sourceSetId("alpha", "test"),
            )
        val symbolKinds = linkedSetOf(SymbolKind.CLASS, SymbolKind.FUNCTION)
        val expectedNodeIds =
            listOf(
                AtlasFilterFixture.symbolId("alpha", "main", SymbolKind.CLASS),
                AtlasFilterFixture.symbolId("alpha", "main", SymbolKind.FUNCTION),
                AtlasFilterFixture.symbolId("alpha", "test", SymbolKind.CLASS),
                AtlasFilterFixture.symbolId("alpha", "test", SymbolKind.FUNCTION),
            )
        val first =
            atlasProjectFrame(
                summary = AtlasFilterFixture.summary,
                project = project,
                request =
                    AtlasProjectFrameRequest(
                        lens = AtlasLens.SYMBOLS,
                        sourceSetIds = sourceSetIds,
                        symbolKinds = symbolKinds,
                    ),
            )
        val second =
            atlasProjectFrame(
                summary = AtlasFilterFixture.summary,
                project = project,
                request =
                    AtlasProjectFrameRequest(
                        lens = AtlasLens.SYMBOLS,
                        sourceSetIds = sourceSetIds.reversedSet(),
                        symbolKinds = symbolKinds.reversedSet(),
                    ),
            )
        val bounded =
            boundedGraphFrame(
                project,
                GraphFrameRequest(
                    query = "",
                    requestedPage = 0,
                    sourceSetIds = sourceSetIds.reversedSet(),
                    symbolKinds = symbolKinds.reversedSet(),
                ),
            )

        assertEquals(expectedNodeIds, first.nodes.map { node -> node.id })
        assertEquals(setOf("CLASS", "FUNCTION"), first.nodes.map { node -> node.kind }.toSet())
        assertEquals(expectedNodeIds.size, first.matchingNodeCount)
        assertEquals(project.symbols.size, first.totalNodeCount)
        assertEquals(first, second)
        assertEquals(expectedNodeIds, bounded.primaryNodeIds)
        assertEquals(expectedNodeIds.size, bounded.matchingNodeCount)
    }

    @Test
    fun filtersABuildAcrossProjectShardsDeterministically() {
        val sourceSetIds =
            linkedSetOf(
                AtlasFilterFixture.sourceSetId("alpha", "main"),
                AtlasFilterFixture.sourceSetId("beta", "test"),
            )
        val symbolKinds = linkedSetOf(SymbolKind.CLASS, SymbolKind.FUNCTION)
        val expectedNodeIds =
            listOf(
                AtlasFilterFixture.symbolId("alpha", "main", SymbolKind.CLASS),
                AtlasFilterFixture.symbolId("alpha", "main", SymbolKind.FUNCTION),
                AtlasFilterFixture.symbolId("beta", "test", SymbolKind.CLASS),
                AtlasFilterFixture.symbolId("beta", "test", SymbolKind.FUNCTION),
            )
        val first =
            atlasBuildFrame(
                summary = AtlasFilterFixture.summary,
                buildId = AtlasFilterFixture.BUILD_ID,
                projects = AtlasFilterFixture.projects.reversed(),
                request =
                    AtlasProjectFrameRequest(
                        lens = AtlasLens.SYMBOLS,
                        sourceSetIds = sourceSetIds,
                        symbolKinds = symbolKinds,
                    ),
            )
        val second =
            atlasBuildFrame(
                summary = AtlasFilterFixture.summary,
                buildId = AtlasFilterFixture.BUILD_ID,
                projects = AtlasFilterFixture.projects,
                request =
                    AtlasProjectFrameRequest(
                        lens = AtlasLens.SYMBOLS,
                        sourceSetIds = sourceSetIds.reversedSet(),
                        symbolKinds = symbolKinds.reversedSet(),
                    ),
            )

        assertEquals(expectedNodeIds, first.nodes.map { node -> node.id })
        assertEquals(setOf("CLASS", "FUNCTION"), first.nodes.map { node -> node.kind }.toSet())
        assertEquals(expectedNodeIds.size, first.matchingNodeCount)
        assertEquals(AtlasFilterFixture.projects.sumOf { project -> project.symbols.size }, first.totalNodeCount)
        assertEquals(first, second)
    }
}

private object AtlasFilterFixture {
    const val BUILD_ID = "build:filter"
    private val projectNames = listOf("alpha", "beta")
    private val sourceSetNames = listOf("integration", "main", "test")
    private val symbolKinds = listOf(SymbolKind.CLASS, SymbolKind.FUNCTION, SymbolKind.PROPERTY)

    val summary =
        WorkspaceSummaryShard(
            workspace = WorkspaceIdentity("workspace:filter", "Filter fixture"),
            builds = listOf(BuildSnapshot(BUILD_ID, "filter", BuildKind.ROOT, ".")),
            projects =
                projectNames.map { projectName ->
                    ProjectSnapshot(
                        id = projectId(projectName),
                        buildId = BUILD_ID,
                        path = ":$projectName",
                        buildFile = "$projectName.gradle.kts",
                    )
                },
            projectShards =
                projectNames.map { projectName ->
                    ProjectShardReference(
                        projectId = projectId(projectName),
                        file = "data/shards/$projectName.json",
                    )
                },
        )

    val projects = projectNames.map(::project)

    fun sourceSetId(
        projectName: String,
        sourceSetName: String,
    ): String = "source-set:filter:$projectName:$sourceSetName"

    fun symbolId(
        projectName: String,
        sourceSetName: String,
        kind: SymbolKind,
    ): String = "symbol:filter:$projectName:$sourceSetName:${kind.name.lowercase()}"

    private fun project(projectName: String): ProjectGraphShard {
        val projectId = projectId(projectName)
        val sourceSets =
            sourceSetNames.map { sourceSetName ->
                SourceSetSnapshot(sourceSetId(projectName, sourceSetName), projectId, sourceSetName)
            }
        val files =
            sourceSetNames.map { sourceSetName ->
                SourceFileSnapshot(
                    id = fileId(projectName, sourceSetName),
                    sourceSetId = sourceSetId(projectName, sourceSetName),
                    projectRelativePath = "src/$sourceSetName/kotlin/filter/$projectName/Declarations.kt",
                    language = SourceLanguage.KOTLIN,
                )
            }
        val symbols =
            sourceSetNames.flatMap { sourceSetName ->
                symbolKinds.map { kind -> symbol(projectName, sourceSetName, kind) }
            }
        return ProjectGraphShard(
            projectId = projectId,
            sourceSets = sourceSets,
            files = files,
            symbols = symbols,
            relationships = emptyList(),
        )
    }

    private fun symbol(
        projectName: String,
        sourceSetName: String,
        kind: SymbolKind,
    ): SymbolSnapshot {
        val name = "${sourceSetName.replaceFirstChar(Char::uppercase)}${kind.name.lowercase()}"
        return SymbolSnapshot(
            id = symbolId(projectName, sourceSetName, kind),
            fileId = fileId(projectName, sourceSetName),
            name = name,
            qualifiedName = "filter.$projectName.$name",
            packageName = "filter.$projectName",
            kind = kind,
            declarationSemantic =
                if (kind == SymbolKind.CLASS) {
                    DeclarationSemantic.CONCRETE_CLASS
                } else {
                    DeclarationSemantic.OTHER
                },
            declarationLine = kind.ordinal + 1,
        )
    }

    private fun projectId(projectName: String): String = "project:filter:$projectName"

    private fun fileId(
        projectName: String,
        sourceSetName: String,
    ): String = "file:filter:$projectName:$sourceSetName"
}

private fun <T> Set<T>.reversedSet(): Set<T> = reversed().toSet()
