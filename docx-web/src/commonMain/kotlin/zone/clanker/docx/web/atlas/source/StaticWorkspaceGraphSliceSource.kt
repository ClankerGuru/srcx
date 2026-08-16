package zone.clanker.docx.web.atlas.source

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import zone.clanker.report.model.ProjectGraphShard
import zone.clanker.report.model.ProjectSnapshot
import zone.clanker.report.model.SourceSetSnapshot
import zone.clanker.report.model.SymbolSnapshot
import zone.clanker.report.model.WorkspaceGraphFilter
import zone.clanker.report.model.WorkspaceGraphRequest
import zone.clanker.report.model.WorkspaceGraphSearchTarget
import zone.clanker.report.model.WorkspaceGraphSlice
import zone.clanker.report.model.WorkspaceSummaryShard

/**
 * Static equivalent of the live query boundary.
 *
 * Workspace/build navigation is answered from the small summary. Project shards are fetched only for an
 * explicitly drilled project/source set, a previously discovered leaf, or bounded search candidates.
 */
internal class StaticWorkspaceGraphSliceSource(
    summary: WorkspaceSummaryShard,
    private val projects: ProjectGraphShardSource,
    private val search: WorkspaceGraphSearchProjectSource = WorkspaceGraphSearchProjectSource.EMPTY,
    private val projector: WorkspaceGraphSliceProjector,
) : WorkspaceGraphSliceSource {
    private val projectIds = summary.projects.mapTo(mutableSetOf(), ProjectSnapshot::id)
    private val sourceSetOwners = summary.sourceSets.associate { sourceSet -> sourceSet.id to sourceSet.projectId }
    private val discoveredOwners = mutableMapOf<String, String>()

    override suspend fun load(request: WorkspaceGraphRequest): WorkspaceGraphSlice {
        val requiredProjectIds = requiredProjectIds(request)
        require(requiredProjectIds.size <= MAX_PROJECT_SHARDS_PER_SLICE) {
            "Static workspace-graph drill requires ${requiredProjectIds.size} project shards; " +
                "narrow the scope to at most $MAX_PROJECT_SHARDS_PER_SLICE"
        }
        val loadedProjects = loadProjects(requiredProjectIds)
        loadedProjects.forEach(::rememberOwners)
        return projector.project(request, loadedProjects)
    }

    private suspend fun requiredProjectIds(request: WorkspaceGraphRequest): List<String> {
        val selection = request.view.selection
        val scopeProjectIds = selection.scopeRootIds.mapNotNull(::projectOwnerOf).distinct()
        val singletonScopeProjectId = scopeProjectIds.singleOrNull()
        val explicitlyDrilledProjectIds =
            (selection.expandedNodeIds + selection.focusNodeIds)
                .mapNotNull(::projectOwnerOf)
                .distinct()
        val searchProjectIds = search.projectIds(request)
        return (
            listOfNotNull(singletonScopeProjectId) +
                explicitlyDrilledProjectIds +
                searchProjectIds
        ).distinct().sorted()
    }

    private fun projectOwnerOf(nodeId: String): String? =
        nodeId.takeIf(projectIds::contains) ?: ownerOf(nodeId)

    private fun ownerOf(nodeId: String): String? = sourceSetOwners[nodeId] ?: discoveredOwners[nodeId]

    private suspend fun loadProjects(requiredProjectIds: List<String>): List<ProjectGraphShard> {
        val loaded =
            coroutineScope {
                requiredProjectIds
                    .map { projectId -> async { projects.load(projectId) } }
                    .awaitAll()
            }
        val byId = loaded.associateBy(ProjectGraphShard::projectId)
        require(byId.size == loaded.size && byId.keys == requiredProjectIds.toSet()) {
            "Static workspace-graph shard source returned conflicting project identities"
        }
        return requiredProjectIds.map(byId::getValue)
    }

    private fun rememberOwners(project: ProjectGraphShard) {
        project.sourceSets.forEach { sourceSet -> discoveredOwners[sourceSet.id] = sourceSet.projectId }
        val sourceSetIds = project.sourceSets.associateBy(SourceSetSnapshot::id)
        project.files.forEach { file ->
            discoveredOwners[file.id] = sourceSetIds.getValue(file.sourceSetId).projectId
        }
        val sourceSetIdsByFile = project.files.associate { file -> file.id to file.sourceSetId }
        project.symbols.forEach { symbol ->
            rememberSymbolOwner(
                symbol = symbol,
                projectId = discoveredOwners.getValue(symbol.fileId),
                sourceSetId = sourceSetIdsByFile.getValue(symbol.fileId),
            )
        }
        project.findings.forEach { finding -> discoveredOwners[finding.id] = project.projectId }
        project.cycles.forEach { cycle -> discoveredOwners[cycle.id] = project.projectId }
    }

    private fun rememberSymbolOwner(
        symbol: SymbolSnapshot,
        projectId: String,
        sourceSetId: String,
    ) {
        discoveredOwners[symbol.id] = projectId
        packagePrefixes(symbol.packageName).forEach { packageName ->
            discoveredOwners[sourcePackageId(sourceSetId, packageName)] = projectId
        }
        if (symbol.packageName.isBlank()) {
            discoveredOwners[unknownSourcePackageId(sourceSetId)] = projectId
        }
    }
}

internal fun WorkspaceGraphFilter.mayMatchProjectOwnedFacts(): Boolean =
    searchTargets.isEmpty() || searchTargets.any(PROJECT_OWNED_SEARCH_TARGETS::contains)

private fun packagePrefixes(packageName: String): List<String> {
    if (packageName.isBlank()) return emptyList()
    val segments = packageName.split('.')
    return segments.indices.map { index -> segments.take(index + 1).joinToString(".") }
}

private fun sourcePackageId(
    sourceSetId: String,
    qualifiedName: String,
): String = "source-package:${sourceSetId.length}:$sourceSetId:${qualifiedName.length}:$qualifiedName"

private fun unknownSourcePackageId(sourceSetId: String): String =
    "source-unknown-package:${sourceSetId.length}:$sourceSetId"

private val PROJECT_OWNED_SEARCH_TARGETS =
    setOf(
        WorkspaceGraphSearchTarget.PACKAGE,
        WorkspaceGraphSearchTarget.FILE,
        WorkspaceGraphSearchTarget.CLASS,
        WorkspaceGraphSearchTarget.SYMBOL,
        WorkspaceGraphSearchTarget.METHOD,
        WorkspaceGraphSearchTarget.FILE_EXTENSION,
    )
private const val MAX_PROJECT_SHARDS_PER_SLICE: Int = 8
