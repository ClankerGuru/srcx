package zone.clanker.docx.web.atlas.hierarchy

import zone.clanker.report.model.BuildEdgeSnapshot
import zone.clanker.report.model.BuildKind
import zone.clanker.report.model.BuildSnapshot
import zone.clanker.report.model.DeclarationSemantic
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
import zone.clanker.report.model.WorkspaceSourceSetSummary
import zone.clanker.report.model.WorkspaceSummaryShard

internal fun largeSourceHierarchySummary(): WorkspaceSummaryShard {
    val builds = (0 until LARGE_BUILD_COUNT).map(::largeBuild)
    val projects =
        builds.flatMapIndexed { buildIndex, build ->
            (0 until LARGE_PROJECTS_PER_BUILD).map { projectIndex ->
                largeProject(build, buildIndex, projectIndex)
            }
        }
    return WorkspaceSummaryShard(
        workspace = WorkspaceIdentity(LARGE_WORKSPACE_ID, "Large workspace"),
        builds = builds,
        projects = projects,
        sourceSets = projects.map(::largeSourceSet),
        projectShards = projects.map(::largeShardReference),
        buildEdges = (0 until LARGE_BUILD_EDGE_COUNT).map(::largeBuildEdge),
    )
}

internal fun externalEndpointSummary(): WorkspaceSummaryShard =
    WorkspaceSummaryShard(
        workspace = WorkspaceIdentity(EXTERNAL_WORKSPACE_ID, "Endpoint workspace"),
        builds =
            listOf(
                BuildSnapshot(
                    id = EXTERNAL_BUILD_ID,
                    name = "endpoint-build",
                    kind = BuildKind.ROOT,
                    relativePath = ".",
                ),
            ),
        projects =
            listOf(
                ProjectSnapshot(EXTERNAL_ALPHA_PROJECT_ID, EXTERNAL_BUILD_ID, ":alpha", "build.gradle.kts"),
                ProjectSnapshot(EXTERNAL_BETA_PROJECT_ID, EXTERNAL_BUILD_ID, ":beta", "build.gradle.kts"),
            ),
        sourceSets =
            listOf(
                WorkspaceSourceSetSummary(EXTERNAL_ALPHA_SOURCE_SET_ID, EXTERNAL_ALPHA_PROJECT_ID, "main", 1),
                WorkspaceSourceSetSummary(EXTERNAL_BETA_SOURCE_SET_ID, EXTERNAL_BETA_PROJECT_ID, "main", 1),
            ),
        projectShards =
            listOf(
                ProjectShardReference(EXTERNAL_ALPHA_PROJECT_ID, "projects/alpha.cbor"),
                ProjectShardReference(EXTERNAL_BETA_PROJECT_ID, "projects/beta.cbor"),
            ),
    )

internal fun externalEndpointShard(): ProjectGraphShard =
    ProjectGraphShard(
        projectId = EXTERNAL_ALPHA_PROJECT_ID,
        sourceSets =
            listOf(
                SourceSetSnapshot(EXTERNAL_ALPHA_SOURCE_SET_ID, EXTERNAL_ALPHA_PROJECT_ID, "main"),
                SourceSetSnapshot(EXTERNAL_BETA_SOURCE_SET_ID, EXTERNAL_BETA_PROJECT_ID, "main"),
            ),
        files =
            listOf(
                SourceFileSnapshot(
                    id = EXTERNAL_ALPHA_FILE_ID,
                    sourceSetId = EXTERNAL_ALPHA_SOURCE_SET_ID,
                    projectRelativePath = "src/main/kotlin/example/Alpha.kt",
                    language = SourceLanguage.KOTLIN,
                ),
                SourceFileSnapshot(
                    id = EXTERNAL_BETA_FILE_ID,
                    sourceSetId = EXTERNAL_BETA_SOURCE_SET_ID,
                    projectRelativePath = "src/main/kotlin/example/Beta.kt",
                    language = SourceLanguage.KOTLIN,
                ),
            ),
        symbols =
            listOf(
                SymbolSnapshot(
                    id = EXTERNAL_ALPHA_SYMBOL_ID,
                    fileId = EXTERNAL_ALPHA_FILE_ID,
                    name = "Alpha",
                    qualifiedName = "example.Alpha",
                    packageName = "example",
                    kind = SymbolKind.CLASS,
                    declarationSemantic = DeclarationSemantic.CONCRETE_CLASS,
                    declarationLine = 1,
                ),
                SymbolSnapshot(
                    id = EXTERNAL_BETA_SYMBOL_ID,
                    fileId = EXTERNAL_BETA_FILE_ID,
                    name = "Beta",
                    qualifiedName = "example.Beta",
                    packageName = "example",
                    kind = SymbolKind.CLASS,
                    declarationSemantic = DeclarationSemantic.CONCRETE_CLASS,
                    declarationLine = 1,
                ),
            ),
        references = (0 until EXTERNAL_RELATIONSHIP_COUNT).map(::externalReference),
        relationships = (0 until EXTERNAL_RELATIONSHIP_COUNT).map(::externalRelationship),
    )

internal fun largeBuildId(index: Int): String = "build:${padded(index, STANDARD_ID_WIDTH)}"

internal fun largeProjectId(
    buildIndex: Int,
    projectIndex: Int,
): String = "project:${padded(buildIndex, STANDARD_ID_WIDTH)}:${padded(projectIndex, PROJECT_ID_WIDTH)}"

internal fun largeSourceSetId(
    buildIndex: Int,
    projectIndex: Int,
): String =
    "source-set:${padded(buildIndex, STANDARD_ID_WIDTH)}:${padded(projectIndex, PROJECT_ID_WIDTH)}:main"

internal fun externalRelationshipId(index: Int): String = "relationship:${padded(index, STANDARD_ID_WIDTH)}"

private fun largeBuild(index: Int): BuildSnapshot =
    BuildSnapshot(
        id = largeBuildId(index),
        name = "build-${padded(index, STANDARD_ID_WIDTH)}",
        kind = if (index == 0) BuildKind.ROOT else BuildKind.INCLUDED,
        relativePath = if (index == 0) "." else "included/build-${padded(index, STANDARD_ID_WIDTH)}",
    )

private fun largeProject(
    build: BuildSnapshot,
    buildIndex: Int,
    projectIndex: Int,
): ProjectSnapshot =
    ProjectSnapshot(
        id = largeProjectId(buildIndex, projectIndex),
        buildId = build.id,
        path = ":module-${padded(projectIndex, PROJECT_ID_WIDTH)}",
        buildFile = "build.gradle.kts",
    )

private fun largeSourceSet(project: ProjectSnapshot): WorkspaceSourceSetSummary =
    WorkspaceSourceSetSummary(
        id = "source-set:${project.id.removePrefix("project:")}:main",
        projectId = project.id,
        name = "main",
        fileCount = 1,
    )

private fun largeShardReference(project: ProjectSnapshot): ProjectShardReference {
    val suffix = project.id.removePrefix("project:").replace(':', '-')
    return ProjectShardReference(project.id, "projects/$suffix.cbor")
}

private fun largeBuildEdge(index: Int): BuildEdgeSnapshot {
    val sourceIndex = index % LARGE_BUILD_COUNT
    val offset = index / LARGE_BUILD_COUNT + 1
    val targetIndex = (sourceIndex + offset) % LARGE_BUILD_COUNT
    return BuildEdgeSnapshot(
        id = "build-edge:${padded(index, BUILD_EDGE_ID_WIDTH)}",
        sourceBuildId = largeBuildId(sourceIndex),
        targetBuildId = largeBuildId(targetIndex),
    )
}

private fun externalReference(index: Int): ReferenceSnapshot =
    ReferenceSnapshot(
        id = "reference:${padded(index, STANDARD_ID_WIDTH)}",
        sourceFileId = EXTERNAL_ALPHA_FILE_ID,
        sourceSymbolId = EXTERNAL_ALPHA_SYMBOL_ID,
        line = index + 1,
        context = "Beta()",
        targetName = "Beta",
        targetQualifiedName = "example.Beta",
        kind = ReferenceKind.CALL,
        evidence = RelationshipEvidence.DIRECT,
    )

private fun externalRelationship(index: Int): RelationshipSnapshot =
    RelationshipSnapshot(
        id = externalRelationshipId(index),
        referenceId = "reference:${padded(index, STANDARD_ID_WIDTH)}",
        sourceSymbolId = EXTERNAL_ALPHA_SYMBOL_ID,
        targetSymbolId = EXTERNAL_BETA_SYMBOL_ID,
        kind = RelationshipKind.CALL,
        resolutionEvidence = RelationshipEvidence.DIRECT,
    )

private fun padded(
    value: Int,
    width: Int,
): String = value.toString().padStart(width, '0')

internal const val LARGE_WORKSPACE_ID: String = "workspace:large"
internal const val LARGE_BUILD_COUNT: Int = 80
internal const val LARGE_PROJECTS_PER_BUILD: Int = 32
internal const val LARGE_PROJECT_COUNT: Int = LARGE_BUILD_COUNT * LARGE_PROJECTS_PER_BUILD
internal const val LARGE_BUILD_EDGE_COUNT: Int = 900
internal const val EXTERNAL_WORKSPACE_ID: String = "workspace:endpoint"
internal const val EXTERNAL_BUILD_ID: String = "build:endpoint"
internal const val EXTERNAL_ALPHA_PROJECT_ID: String = "project:alpha"
internal const val EXTERNAL_BETA_PROJECT_ID: String = "project:beta"
internal const val EXTERNAL_ALPHA_SOURCE_SET_ID: String = "source-set:alpha:main"
internal const val EXTERNAL_BETA_SOURCE_SET_ID: String = "source-set:beta:main"
private const val EXTERNAL_ALPHA_FILE_ID: String = "file:alpha"
private const val EXTERNAL_BETA_FILE_ID: String = "file:beta"
private const val EXTERNAL_ALPHA_SYMBOL_ID: String = "symbol:alpha"
private const val EXTERNAL_BETA_SYMBOL_ID: String = "symbol:beta"
internal const val EXTERNAL_RELATIONSHIP_COUNT: Int = 5
private const val STANDARD_ID_WIDTH: Int = 3
private const val PROJECT_ID_WIDTH: Int = 2
private const val BUILD_EDGE_ID_WIDTH: Int = 4
