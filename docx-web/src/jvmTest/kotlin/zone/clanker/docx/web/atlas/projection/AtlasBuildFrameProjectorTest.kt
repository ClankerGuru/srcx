package zone.clanker.docx.web.atlas.projection

import zone.clanker.docx.web.fixture.DocxWebFixture
import zone.clanker.report.model.AtlasFrame
import zone.clanker.report.model.AtlasFrameJson
import zone.clanker.report.model.AtlasLens
import zone.clanker.report.model.AtlasScopeKind
import zone.clanker.report.model.BuildKind
import zone.clanker.report.model.BuildSnapshot
import zone.clanker.report.model.CycleSnapshot
import zone.clanker.report.model.DeclarationSemantic
import zone.clanker.report.model.FindingSeverity
import zone.clanker.report.model.FindingSnapshot
import zone.clanker.report.model.ProjectGraphShard
import zone.clanker.report.model.ProjectShardReference
import zone.clanker.report.model.ProjectSnapshot
import zone.clanker.report.model.ReferenceKind
import zone.clanker.report.model.ReferenceSnapshot
import zone.clanker.report.model.RelationshipEvidence
import zone.clanker.report.model.RelationshipKind
import zone.clanker.report.model.RelationshipSnapshot
import zone.clanker.report.model.SourceFileSnapshot
import zone.clanker.report.model.SourceLanguage
import zone.clanker.report.model.SourceSetSnapshot
import zone.clanker.report.model.SymbolKind
import zone.clanker.report.model.SymbolSnapshot
import zone.clanker.report.model.WorkspaceIdentity
import zone.clanker.report.model.WorkspaceSummaryShard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AtlasBuildFrameProjectorTest {
    @Test
    fun mergesEverySelectedBuildProjectAcrossAllFourLensesWithoutDuplicatingEvidence() {
        val frames =
            AtlasLens.entries.associateWith { lens ->
                atlasBuildFrame(
                    summary = BuildScopeFixture.summary,
                    buildId = BuildScopeFixture.BUILD_ID,
                    projects = BuildScopeFixture.projects.reversed(),
                    request = AtlasProjectFrameRequest(lens),
                )
            }

        frames.forEach { (lens, frame) -> assertCompleteBuildFrame(lens, frame) }
        assertEquals(BuildScopeFixture.fileIds, frames.getValue(AtlasLens.FILES).nodes.map { node -> node.id })
        assertEquals(BuildScopeFixture.symbolIds, frames.getValue(AtlasLens.SYMBOLS).nodes.map { node -> node.id })
        assertEquals(
            BuildScopeFixture.problemNodeIds,
            frames.getValue(AtlasLens.PROBLEMS).nodes.map { node -> node.id },
        )
        assertEquals(BuildScopeFixture.symbolIds, frames.getValue(AtlasLens.CYCLES).nodes.map { node -> node.id })
        assertTrue(frames.getValue(AtlasLens.CYCLES).edges.all { edge -> edge.isObservedCycleEdge })
        assertTrue(frames.getValue(AtlasLens.CYCLES).nodes.all { node -> node.hasObservedCycle })
    }

    @Test
    fun prefersOwnedSourceContentOverARedactedClosureCopy() {
        val evidence = atlasBuildRawEvidence(BuildScopeFixture.projects.reversed())
        val alphaContent = requireNotNull(evidence.sourceFilesById.getValue(BuildScopeFixture.ALPHA_FILE_ID).content)
        val betaContent = requireNotNull(evidence.sourceFilesById.getValue(BuildScopeFixture.BETA_FILE_ID).content)

        assertTrue(alphaContent.contains("Alpha"))
        assertTrue(betaContent.contains("Beta"))
    }

    @Test
    fun filtersOneBuildWhileRetainingOnlyItsRealIncidentContext() {
        val frame =
            atlasBuildFrame(
                summary = BuildScopeFixture.summary,
                buildId = BuildScopeFixture.BUILD_ID,
                projects = BuildScopeFixture.projects,
                request = AtlasProjectFrameRequest(AtlasLens.FILES, query = "Alpha.kt"),
            )

        assertEquals(BuildScopeFixture.fileIds, frame.nodes.map { node -> node.id })
        assertEquals(
            listOf(BuildScopeFixture.ALPHA_FILE_ID),
            frame.nodes.filter { node -> node.primary }.map { node -> node.id },
        )
        assertEquals(
            listOf(BuildScopeFixture.BETA_FILE_ID),
            frame.nodes.filterNot { node -> node.primary }.map { node -> node.id },
        )
        assertEquals(
            BuildScopeFixture.relationshipIds,
            frame.edges.flatMap { edge -> edge.relationshipIds }.sorted(),
        )
        assertEquals(2, frame.totalNodeCount)
        assertEquals(1, frame.matchingNodeCount)
        assertEquals(2, frame.totalRelationshipRecordCount)
        assertEquals(2, frame.shownRelationshipRecordCount)
        assertEquals(1, frame.pageCount)
    }

    @Test
    fun retainsRawSelectionIdsAfterTheBuildMerge() {
        val frame =
            atlasBuildFrame(
                summary = BuildScopeFixture.summary,
                buildId = BuildScopeFixture.BUILD_ID,
                projects = BuildScopeFixture.projects,
                request =
                    AtlasProjectFrameRequest(
                        lens = AtlasLens.SYMBOLS,
                        selectedNodeIds = listOf(BuildScopeFixture.BETA_SYMBOL_ID),
                        selectedRelationshipId = BuildScopeFixture.BETA_ALPHA_RELATIONSHIP_ID,
                    ),
            )

        assertEquals(listOf(BuildScopeFixture.BETA_SYMBOL_ID), frame.selectedNodeIds)
        assertEquals(BuildScopeFixture.BETA_ALPHA_RELATIONSHIP_ID, frame.selectedEdgeId)
    }

    @Test
    fun keepsASelectedBuildFrameCompleteBeyondTheWorkspaceOverviewLimit() {
        val project = largeProject()
        val frame =
            atlasBuildFrame(
                summary = DocxWebFixture.summary,
                buildId = "build:fixture",
                projects = listOf(project),
                request = AtlasProjectFrameRequest(AtlasLens.SYMBOLS),
            )

        assertEquals(LARGE_NODE_COUNT, frame.nodes.size)
        assertTrue(frame.nodes.size > AtlasFrame.MAX_VISIBLE_NODES)
        assertEquals(LARGE_NODE_COUNT, frame.totalNodeCount)
        assertEquals(1, frame.pageCount)
    }

    @Test
    fun rejectsAnIncompleteSelectedBuildShardSet() {
        assertFailsWith<IllegalArgumentException> {
            atlasBuildFrame(
                summary = BuildScopeFixture.summary,
                buildId = BuildScopeFixture.BUILD_ID,
                projects = listOf(BuildScopeFixture.projects.first()),
                request = AtlasProjectFrameRequest(AtlasLens.FILES),
            )
        }
    }

    @Test
    fun mergesTwoProjectsAcrossTwoBuildsIntoOneCompleteDeterministicWorkspaceFrame() {
        val selection =
            AtlasScopeSelection(
                buildIds = setOf(CrossBuildSelectionFixture.SECOND_BUILD_ID, CrossBuildSelectionFixture.ROOT_BUILD_ID),
                projectIds =
                    setOf(
                        CrossBuildSelectionFixture.SECOND_PROJECT_ID,
                        CrossBuildSelectionFixture.ROOT_PROJECT_ID,
                    ),
            )
        val reversed =
            atlasSelectionFrame(
                summary = CrossBuildSelectionFixture.summary,
                selection = selection,
                projects = CrossBuildSelectionFixture.projects.reversed(),
                request = AtlasProjectFrameRequest(AtlasLens.FILES),
            )
        val ordered =
            atlasSelectionFrame(
                summary = CrossBuildSelectionFixture.summary,
                selection =
                    AtlasScopeSelection(
                        buildIds = selection.buildIds.reversed().toSet(),
                        projectIds = selection.projectIds.reversed().toSet(),
                    ),
                projects = CrossBuildSelectionFixture.projects,
                request = AtlasProjectFrameRequest(AtlasLens.FILES),
            )

        assertEquals(ordered, reversed)
        assertEquals(AtlasScopeKind.WORKSPACE, ordered.scope.kind)
        assertEquals(CrossBuildSelectionFixture.buildIds, ordered.builds.map { build -> build.id })
        assertEquals(CrossBuildSelectionFixture.fileIds, ordered.nodes.map { node -> node.id })
        assertEquals(
            listOf(CrossBuildSelectionFixture.RELATIONSHIP_ID),
            ordered.edges.single().relationshipIds,
        )
        assertEquals(listOf(CrossBuildSelectionFixture.REFERENCE_ID), ordered.edges.single().referenceIds)
        assertTrue(ordered.edges.single().crossBuild)
        assertEquals(2, ordered.totalNodeCount)
        assertEquals(2, ordered.matchingNodeCount)
        assertEquals(1, ordered.totalRelationshipRecordCount)
        assertEquals(1, ordered.shownRelationshipRecordCount)
        assertEquals(0, ordered.pageIndex)
        assertEquals(1, ordered.pageCount)
    }

    @Test
    fun rejectsASelectedProjectWhoseOwningBuildWasNotRetained() {
        assertFailsWith<IllegalArgumentException> {
            atlasSelectionFrame(
                summary = CrossBuildSelectionFixture.summary,
                selection =
                    AtlasScopeSelection(
                        buildIds = setOf(CrossBuildSelectionFixture.ROOT_BUILD_ID),
                        projectIds = setOf(CrossBuildSelectionFixture.SECOND_PROJECT_ID),
                    ),
                projects = listOf(CrossBuildSelectionFixture.projects.last()),
                request = AtlasProjectFrameRequest(AtlasLens.FILES),
            )
        }
    }

    private fun assertCompleteBuildFrame(
        lens: AtlasLens,
        frame: AtlasFrame,
    ) {
        assertEquals(AtlasScopeKind.BUILD, frame.scope.kind)
        assertEquals(BuildScopeFixture.BUILD_ID, frame.scope.buildId)
        assertEquals(lens, frame.lens)
        assertEquals(listOf(BuildScopeFixture.BUILD_ID), frame.builds.map { build -> build.id })
        assertEquals(BuildScopeFixture.relationshipIds, frame.edges.flatMap { edge -> edge.relationshipIds }.sorted())
        assertEquals(BuildScopeFixture.referenceIds, frame.edges.flatMap { edge -> edge.referenceIds }.sorted())
        assertEquals(2, frame.totalRelationshipRecordCount)
        assertEquals(2, frame.shownRelationshipRecordCount)
        assertEquals(0, frame.pageIndex)
        assertEquals(1, frame.pageCount)
        assertEquals(frame, AtlasFrameJson.decode(AtlasFrameJson.encode(frame)))
    }

    private fun largeProject(): ProjectGraphShard {
        val sourceSet = SourceSetSnapshot(LARGE_SOURCE_SET_ID, LARGE_PROJECT_ID, "main")
        val files = (0 until LARGE_NODE_COUNT).map { index -> largeFile(sourceSet.id, index) }
        return ProjectGraphShard(
            projectId = LARGE_PROJECT_ID,
            sourceSets = listOf(sourceSet),
            files = files,
            symbols = files.mapIndexed(::largeSymbol),
            relationships = emptyList(),
        )
    }

    private fun largeFile(
        sourceSetId: String,
        index: Int,
    ): SourceFileSnapshot {
        val suffix = index.toString().padStart(3, '0')
        return SourceFileSnapshot(
            id = "file:build-large:$suffix",
            sourceSetId = sourceSetId,
            projectRelativePath = "src/main/kotlin/large/Node$suffix.kt",
            language = SourceLanguage.KOTLIN,
        )
    }

    private fun largeSymbol(
        index: Int,
        file: SourceFileSnapshot,
    ): SymbolSnapshot {
        val suffix = index.toString().padStart(3, '0')
        return SymbolSnapshot(
            id = "symbol:build-large:$suffix",
            fileId = file.id,
            name = "Node$suffix",
            qualifiedName = "large.Node$suffix",
            packageName = "large",
            kind = SymbolKind.CLASS,
            declarationSemantic = DeclarationSemantic.CONCRETE_CLASS,
            declarationLine = 1,
        )
    }

    private companion object {
        const val LARGE_PROJECT_ID = "project:fixture:app"
        const val LARGE_SOURCE_SET_ID = "source-set:build-large:main"
        const val LARGE_NODE_COUNT = 64
    }
}

private object CrossBuildSelectionFixture {
    const val ROOT_BUILD_ID = "build:cross-a"
    const val SECOND_BUILD_ID = "build:cross-b"
    const val ROOT_PROJECT_ID = "project:cross-a:app"
    const val SECOND_PROJECT_ID = "project:cross-b:lib"
    const val REFERENCE_ID = "reference:cross-a:app-lib"
    const val RELATIONSHIP_ID = "relationship:cross-a:app-lib"
    private const val ROOT_SOURCE_SET_ID = "source-set:cross-a:main"
    private const val SECOND_SOURCE_SET_ID = "source-set:cross-b:main"
    private const val ROOT_FILE_ID = "file:cross-a:App.kt"
    private const val SECOND_FILE_ID = "file:cross-b:Lib.kt"
    private const val ROOT_SYMBOL_ID = "symbol:cross-a:App"
    private const val SECOND_SYMBOL_ID = "symbol:cross-b:Lib"

    val buildIds = listOf(ROOT_BUILD_ID, SECOND_BUILD_ID)
    val fileIds = listOf(ROOT_FILE_ID, SECOND_FILE_ID)
    val summary =
        WorkspaceSummaryShard(
            workspace = WorkspaceIdentity("workspace:cross", "Cross-build selection fixture"),
            builds =
                listOf(
                    BuildSnapshot(ROOT_BUILD_ID, "cross-a", BuildKind.ROOT, "."),
                    BuildSnapshot(SECOND_BUILD_ID, "cross-b", BuildKind.INCLUDED, "included/cross-b"),
                ),
            projects =
                listOf(
                    ProjectSnapshot(ROOT_PROJECT_ID, ROOT_BUILD_ID, ":app", "app/build.gradle.kts"),
                    ProjectSnapshot(SECOND_PROJECT_ID, SECOND_BUILD_ID, ":lib", "lib/build.gradle.kts"),
                ),
            projectShards =
                listOf(
                    ProjectShardReference(ROOT_PROJECT_ID, "data/shards/cross-a.json"),
                    ProjectShardReference(SECOND_PROJECT_ID, "data/shards/cross-b.json"),
                ),
        )
    val projects: List<ProjectGraphShard>
        get() = listOf(project(ROOT_PROJECT_ID), project(SECOND_PROJECT_ID))

    private val sourceSets =
        listOf(
            SourceSetSnapshot(ROOT_SOURCE_SET_ID, ROOT_PROJECT_ID, "main"),
            SourceSetSnapshot(SECOND_SOURCE_SET_ID, SECOND_PROJECT_ID, "main"),
        )
    private val files =
        listOf(
            sourceFile(ROOT_FILE_ID, ROOT_SOURCE_SET_ID, "App", "class App { val lib = Lib() }"),
            sourceFile(SECOND_FILE_ID, SECOND_SOURCE_SET_ID, "Lib", "class Lib"),
        )
    private val symbols =
        listOf(
            symbol(ROOT_SYMBOL_ID, ROOT_FILE_ID, "App"),
            symbol(SECOND_SYMBOL_ID, SECOND_FILE_ID, "Lib"),
        )
    private val reference =
        ReferenceSnapshot(
            id = REFERENCE_ID,
            sourceFileId = ROOT_FILE_ID,
            sourceSymbolId = ROOT_SYMBOL_ID,
            line = 3,
            context = "Lib()",
            targetName = "Lib",
            targetQualifiedName = "cross.Lib",
            kind = ReferenceKind.CALL,
            evidence = RelationshipEvidence.DIRECT,
        )
    private val relationship =
        RelationshipSnapshot(
            id = RELATIONSHIP_ID,
            referenceId = REFERENCE_ID,
            sourceSymbolId = ROOT_SYMBOL_ID,
            targetSymbolId = SECOND_SYMBOL_ID,
            kind = RelationshipKind.CALL,
            resolutionEvidence = RelationshipEvidence.DIRECT,
        )

    private fun project(projectId: String): ProjectGraphShard {
        val ownedSourceSetId = if (projectId == ROOT_PROJECT_ID) ROOT_SOURCE_SET_ID else SECOND_SOURCE_SET_ID
        return ProjectGraphShard(
            projectId = projectId,
            sourceSets = sourceSets,
            files = files.map { file -> if (file.sourceSetId == ownedSourceSetId) file else file.copy(content = null) },
            symbols = symbols,
            references = if (projectId == ROOT_PROJECT_ID) listOf(reference) else emptyList(),
            relationships = if (projectId == ROOT_PROJECT_ID) listOf(relationship) else emptyList(),
        )
    }

    private fun sourceFile(
        id: String,
        sourceSetId: String,
        name: String,
        declaration: String,
    ): SourceFileSnapshot =
        SourceFileSnapshot(
            id = id,
            sourceSetId = sourceSetId,
            projectRelativePath = "src/main/kotlin/cross/$name.kt",
            language = SourceLanguage.KOTLIN,
            content = "package cross\n\n$declaration\n",
        )

    private fun symbol(
        id: String,
        fileId: String,
        name: String,
    ): SymbolSnapshot =
        SymbolSnapshot(
            id = id,
            fileId = fileId,
            name = name,
            qualifiedName = "cross.$name",
            packageName = "cross",
            kind = SymbolKind.CLASS,
            declarationSemantic = DeclarationSemantic.CONCRETE_CLASS,
            declarationLine = 3,
        )
}

private object BuildScopeFixture {
    const val BUILD_ID = "build:scope"
    const val ALPHA_FILE_ID = "file:scope:alpha"
    const val BETA_FILE_ID = "file:scope:beta"
    const val ALPHA_SYMBOL_ID = "symbol:scope:alpha"
    const val BETA_SYMBOL_ID = "symbol:scope:beta"
    const val BETA_ALPHA_RELATIONSHIP_ID = "relationship:scope:beta-alpha"
    private const val ALPHA_PROJECT_ID = "project:scope:alpha"
    private const val BETA_PROJECT_ID = "project:scope:beta"
    private const val ALPHA_REFERENCE_ID = "reference:scope:alpha-beta"
    private const val BETA_REFERENCE_ID = "reference:scope:beta-alpha"
    private const val ALPHA_RELATIONSHIP_ID = "relationship:scope:alpha-beta"
    private const val CYCLE_ID = "cycle:scope:alpha-beta"

    val fileIds = listOf(ALPHA_FILE_ID, BETA_FILE_ID)
    val symbolIds = listOf(ALPHA_SYMBOL_ID, BETA_SYMBOL_ID)
    val problemNodeIds = fileIds + symbolIds
    val relationshipIds = listOf(ALPHA_RELATIONSHIP_ID, BETA_ALPHA_RELATIONSHIP_ID)
    val referenceIds = listOf(ALPHA_REFERENCE_ID, BETA_REFERENCE_ID)
    val summary: WorkspaceSummaryShard = fixtureSummary()
    val projects: List<ProjectGraphShard>
        get() = listOf(project(ALPHA_PROJECT_ID), project(BETA_PROJECT_ID))

    private val sourceSets =
        listOf(
            SourceSetSnapshot("source-set:scope:alpha", ALPHA_PROJECT_ID, "main"),
            SourceSetSnapshot("source-set:scope:beta", BETA_PROJECT_ID, "main"),
        )
    private val files =
        listOf(
            sourceFile(ALPHA_FILE_ID, sourceSets[0].id, "Alpha"),
            sourceFile(BETA_FILE_ID, sourceSets[1].id, "Beta"),
        )
    private val symbols =
        listOf(
            symbol(ALPHA_SYMBOL_ID, ALPHA_FILE_ID, "Alpha"),
            symbol(BETA_SYMBOL_ID, BETA_FILE_ID, "Beta"),
        )
    private val references =
        listOf(
            reference(ALPHA_REFERENCE_ID, ALPHA_FILE_ID, ALPHA_SYMBOL_ID, "Beta"),
            reference(BETA_REFERENCE_ID, BETA_FILE_ID, BETA_SYMBOL_ID, "Alpha"),
        )
    private val relationships =
        listOf(
            relationship(ALPHA_RELATIONSHIP_ID, ALPHA_REFERENCE_ID, ALPHA_SYMBOL_ID, BETA_SYMBOL_ID),
            relationship(BETA_ALPHA_RELATIONSHIP_ID, BETA_REFERENCE_ID, BETA_SYMBOL_ID, ALPHA_SYMBOL_ID),
        )
    private val cycle =
        CycleSnapshot(
            CYCLE_ID,
            listOf(ALPHA_SYMBOL_ID, BETA_SYMBOL_ID, ALPHA_SYMBOL_ID),
            relationshipIds,
        )

    private fun fixtureSummary(): WorkspaceSummaryShard {
        val projectReferences =
            listOf(
                ProjectShardReference(ALPHA_PROJECT_ID, "data/shards/alpha.json"),
                ProjectShardReference(BETA_PROJECT_ID, "data/shards/beta.json"),
            )
        return WorkspaceSummaryShard(
            workspace = WorkspaceIdentity("workspace:scope", "Build scope fixture"),
            builds = listOf(BuildSnapshot(BUILD_ID, "scope", BuildKind.ROOT, ".")),
            projects =
                listOf(
                    ProjectSnapshot(ALPHA_PROJECT_ID, BUILD_ID, ":alpha", "alpha.gradle.kts"),
                    ProjectSnapshot(BETA_PROJECT_ID, BUILD_ID, ":beta", "beta.gradle.kts"),
                ),
            projectShards = projectReferences,
        )
    }

    private fun project(projectId: String): ProjectGraphShard {
        val ownAlpha = projectId == ALPHA_PROJECT_ID
        val ownedSourceSetId = if (ownAlpha) sourceSets[0].id else sourceSets[1].id
        val finding =
            if (ownAlpha) {
                finding("finding:scope:alpha", ALPHA_FILE_ID, ALPHA_SYMBOL_ID, "Alpha")
            } else {
                finding("finding:scope:beta", BETA_FILE_ID, BETA_SYMBOL_ID, "Beta")
            }
        return ProjectGraphShard(
            projectId = projectId,
            sourceSets = sourceSets,
            files = files.map { file -> if (file.sourceSetId == ownedSourceSetId) file else file.copy(content = null) },
            symbols = symbols,
            references = references,
            relationships = relationships,
            findings = listOf(finding),
            cycles = listOf(cycle),
        )
    }

    private fun sourceFile(
        id: String,
        sourceSetId: String,
        name: String,
    ): SourceFileSnapshot =
        SourceFileSnapshot(
            id = id,
            sourceSetId = sourceSetId,
            projectRelativePath = "src/main/kotlin/scope/$name.kt",
            language = SourceLanguage.KOTLIN,
            content = "package scope\n\nclass $name\n",
        )

    private fun symbol(
        id: String,
        fileId: String,
        name: String,
    ): SymbolSnapshot =
        SymbolSnapshot(
            id = id,
            fileId = fileId,
            name = name,
            qualifiedName = "scope.$name",
            packageName = "scope",
            kind = SymbolKind.CLASS,
            declarationSemantic = DeclarationSemantic.CONCRETE_CLASS,
            declarationLine = 3,
        )

    private fun reference(
        id: String,
        sourceFileId: String,
        sourceSymbolId: String,
        targetName: String,
    ): ReferenceSnapshot =
        ReferenceSnapshot(
            id = id,
            sourceFileId = sourceFileId,
            sourceSymbolId = sourceSymbolId,
            line = 3,
            context = "$targetName()",
            targetName = targetName,
            targetQualifiedName = "scope.$targetName",
            kind = ReferenceKind.CALL,
            evidence = RelationshipEvidence.DIRECT,
        )

    private fun relationship(
        id: String,
        referenceId: String,
        sourceSymbolId: String,
        targetSymbolId: String,
    ): RelationshipSnapshot =
        RelationshipSnapshot(
            id = id,
            referenceId = referenceId,
            sourceSymbolId = sourceSymbolId,
            targetSymbolId = targetSymbolId,
            kind = RelationshipKind.CALL,
            resolutionEvidence = RelationshipEvidence.DIRECT,
        )

    private fun finding(
        id: String,
        fileId: String,
        symbolId: String,
        name: String,
    ): FindingSnapshot =
        FindingSnapshot(
            id = id,
            severity = FindingSeverity.WARNING,
            message = "$name participates in the cycle",
            suggestion = "Review the $name boundary.",
            fileId = fileId,
            filePath = "src/main/kotlin/scope/$name.kt",
            line = 3,
            symbolIds = listOf(symbolId),
        )
}
