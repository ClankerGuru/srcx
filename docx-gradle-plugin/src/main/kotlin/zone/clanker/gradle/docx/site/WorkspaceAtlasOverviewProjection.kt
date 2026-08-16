@file:Suppress("LongMethod", "TooManyFunctions")

package zone.clanker.gradle.docx.site

import zone.clanker.report.model.ArchitectureComponentSnapshot
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
import zone.clanker.report.model.BuildSnapshot
import zone.clanker.report.model.FindingSnapshot
import zone.clanker.report.model.ImportantSymbolSnapshot
import zone.clanker.report.model.ProjectGraphShard
import zone.clanker.report.model.ReferenceSnapshot
import zone.clanker.report.model.RelationshipEvidence
import zone.clanker.report.model.RelationshipKind
import zone.clanker.report.model.RelationshipSnapshot
import zone.clanker.report.model.SourceFileSnapshot
import zone.clanker.report.model.SourceSetSnapshot
import zone.clanker.report.model.SymbolSnapshot
import zone.clanker.report.model.WorkspaceSnapshot

/** Builds the small bounded workspace frame shown before any project shard is requested. */
internal object WorkspaceAtlasOverviewProjection {
    fun apply(
        snapshot: WorkspaceSnapshot,
        projection: ProjectedDocxSite,
    ): AtlasFrame {
        val catalog = OverviewCatalog(snapshot, projection)
        val relationships = catalog.relationships.filter { it.kind != RelationshipKind.IMPORT }
        val endpoints = relationships.associateWith(catalog::fileEndpoints)
        val metrics = catalog.fileMetrics(relationships, endpoints)
        val candidates = catalog.candidates(metrics)
        val selectedFileIds = selectOverviewFiles(candidates, endpoints, relationships)
        val edges = catalog.edges(selectedFileIds, relationships, endpoints)
        val nodes = selectedFileIds.sorted().map { fileId -> catalog.node(fileId, metrics.getValue(fileId)) }
        val shownInternalRecords = nodes.sumOf(AtlasNode::internalRecordCount)
        return AtlasFrame(
            frameId = "atlas:${snapshot.workspace.id}:files:overview",
            scope =
                AtlasScope(
                    kind = AtlasScopeKind.WORKSPACE,
                    workspaceId = snapshot.workspace.id,
                    workspaceName = snapshot.workspace.name,
                ),
            lens = AtlasLens.FILES,
            builds = catalog.atlasBuilds(nodes),
            nodes = nodes,
            edges = edges,
            totalNodeCount = candidates.size,
            matchingNodeCount = candidates.size,
            pageIndex = 0,
            pageCount = if (nodes.isEmpty()) 0 else 1,
            totalRelationshipRecordCount = relationships.size,
            shownRelationshipRecordCount = edges.sumOf(AtlasEdge::recordCount) + shownInternalRecords,
        )
    }
}

private class OverviewCatalog(
    private val snapshot: WorkspaceSnapshot,
    projection: ProjectedDocxSite,
) {
    private val projectGraphs = projection.projectGraphs
    private val buildsById = snapshot.builds.associateBy(BuildSnapshot::id)
    private val projectsById = snapshot.projects.associateBy { it.id }
    private val sourceSetsById = snapshot.sourceSets.associateBy(SourceSetSnapshot::id)
    private val filesById = snapshot.files.associateBy(SourceFileSnapshot::id)
    private val symbolsById = snapshot.symbols.associateBy(SymbolSnapshot::id)
    private val referencesById = snapshot.references.associateBy(ReferenceSnapshot::id)
    private val visibleFileIds =
        projectGraphs.flatMapTo(mutableSetOf()) { graph -> graph.files.map(SourceFileSnapshot::id) }
    private val findings =
        projectGraphs
            .flatMap { graph -> graph.findings + graph.analysis?.findings.orEmpty() }
            .distinctBy(FindingSnapshot::id)
            .sortedBy(FindingSnapshot::id)
    private val findingsByFile = findings.filter { it.fileId != null }.groupBy { requireNotNull(it.fileId) }
    private val importantByFile =
        snapshot.importantSymbols
            .mapNotNull { important -> symbolsById[important.symbolId]?.fileId?.let { it to important } }
            .filter { (fileId) -> fileId in visibleFileIds }
            .groupBy({ it.first }, { it.second })
    private val observedCycleRelationshipIds =
        projectGraphs.flatMapTo(mutableSetOf()) { graph -> graph.cycles.flatMap { it.relationshipIds } }
    private val observedCycleSymbolIds =
        projectGraphs.flatMapTo(mutableSetOf()) { graph -> graph.cycles.flatMap { it.symbolIds } }
    private val analysisCycleFileIds = analysisCycleFiles(projectGraphs)
    private val analysisCycleFileSteps = analysisCycleSteps(projectGraphs)
    val relationships: List<RelationshipSnapshot> =
        projectGraphs
            .flatMap(ProjectGraphShard::relationships)
            .distinctBy(RelationshipSnapshot::id)
            .sortedBy(RelationshipSnapshot::id)

    fun fileEndpoints(relationship: RelationshipSnapshot): Pair<String, String> {
        val sourceFileId = referencesById.getValue(relationship.referenceId).sourceFileId
        val targetFileId = symbolsById.getValue(relationship.targetSymbolId).fileId
        return sourceFileId to targetFileId
    }

    fun fileMetrics(
        relationships: List<RelationshipSnapshot>,
        endpoints: Map<RelationshipSnapshot, Pair<String, String>>,
    ): Map<String, FileMetrics> {
        val metrics = mutableMapOf<String, MutableFileMetrics>()
        relationships.forEach { relationship ->
            val (sourceId, targetId) = endpoints.getValue(relationship)
            val source = metrics.getOrPut(sourceId, ::MutableFileMetrics)
            source.relationshipRecords += 1
            if (sourceId == targetId) {
                source.internalRecords += 1
            } else {
                val target = metrics.getOrPut(targetId, ::MutableFileMetrics)
                target.relationshipRecords += 1
                source.crossFileRecords += 1
                target.crossFileRecords += 1
                source.neighbors += targetId
                target.neighbors += sourceId
                if (buildIdForFile(sourceId) != buildIdForFile(targetId)) {
                    source.crossBuildRecords += 1
                    target.crossBuildRecords += 1
                }
            }
        }
        return candidateFileIds(metrics.keys).associateWith { fileId ->
            val accumulator = metrics[fileId] ?: MutableFileMetrics()
            FileMetrics(
                relationshipRecords = accumulator.relationshipRecords,
                internalRecords = accumulator.internalRecords,
                crossFileRecords = accumulator.crossFileRecords,
                crossBuildRecords = accumulator.crossBuildRecords,
                neighborCount = accumulator.neighbors.size,
            )
        }
    }

    fun candidates(metrics: Map<String, FileMetrics>): List<FileCandidate> =
        metrics.map { (fileId, item) ->
            FileCandidate(
                fileId = fileId,
                buildId = buildIdForFile(fileId),
                findingCount = findingsByFile[fileId].orEmpty().size,
                importanceScore = importantByFile[fileId].orEmpty().maxOfOrNull(ImportantSymbolSnapshot::score) ?: 0,
                metrics = item,
            )
        }

    fun edges(
        selectedFileIds: Set<String>,
        relationships: List<RelationshipSnapshot>,
        endpoints: Map<RelationshipSnapshot, Pair<String, String>>,
    ): List<AtlasEdge> =
        relationships
            .filter { relationship ->
                val (sourceId, targetId) = endpoints.getValue(relationship)
                sourceId != targetId && sourceId in selectedFileIds && targetId in selectedFileIds
            }.groupBy(endpoints::getValue)
            .values
            .map { records -> edge(records.sortedBy(RelationshipSnapshot::id), endpoints) }
            .sortedBy(AtlasEdge::id)

    fun node(
        fileId: String,
        metrics: FileMetrics,
    ): AtlasNode {
        val file = filesById.getValue(fileId)
        val sourceSet = sourceSetsById.getValue(file.sourceSetId)
        val project = projectsById.getValue(sourceSet.projectId)
        val build = buildsById.getValue(project.buildId)
        val fileFindingIds = findingsByFile[fileId].orEmpty().map(FindingSnapshot::id).sorted()
        val importance = importantByFile[fileId].orEmpty().maxOfOrNull(ImportantSymbolSnapshot::score)
        val fileSymbolIds = snapshot.symbols.filter { it.fileId == fileId }.mapTo(mutableSetOf(), SymbolSnapshot::id)
        return AtlasNode(
            id = file.id,
            type = AtlasNodeType.FILE,
            name = file.projectRelativePath.substringAfterLast('/'),
            path = file.projectRelativePath,
            buildId = build.id,
            buildName = build.name,
            projectId = project.id,
            projectPath = project.path,
            sourceSet = sourceSet.name,
            sourceFileId = file.id,
            language = file.language.label,
            kind = file.language.name,
            important = importance != null,
            importanceScore = importance,
            findingIds = fileFindingIds,
            sourceFileFindingCount = fileFindingIds.size,
            hasObservedCycle = fileSymbolIds.any(observedCycleSymbolIds::contains),
            hasAnalysisCycle = fileId in analysisCycleFileIds,
            relationshipRecordCount = metrics.relationshipRecords,
            internalRecordCount = metrics.internalRecords,
        )
    }

    fun atlasBuilds(nodes: List<AtlasNode>): List<AtlasBuild> {
        val represented = nodes.mapNotNullTo(mutableSetOf(), AtlasNode::buildId)
        return buildsById.values
            .filter { build -> build.id in represented }
            .sortedBy(BuildSnapshot::id)
            .map { build ->
                AtlasBuild(
                    id = build.id,
                    name = build.name,
                    context = build.kind.label,
                    color = stableBuildColor(build.name),
                )
            }
    }

    private fun candidateFileIds(relationshipFileIds: Set<String>): Set<String> =
        relationshipFileIds + findingsByFile.keys + importantByFile.keys

    private fun edge(
        records: List<RelationshipSnapshot>,
        endpoints: Map<RelationshipSnapshot, Pair<String, String>>,
    ): AtlasEdge {
        val (sourceId, targetId) = endpoints.getValue(records.first())
        val relationshipIds = records.map(RelationshipSnapshot::id)
        val referenceIds = records.map(RelationshipSnapshot::referenceId).distinct().sorted()
        val kindCounts = records.atlasCounts(RelationshipSnapshot::kind) { kind -> kind.label }
        val evidenceCounts =
            records.atlasCounts(RelationshipSnapshot::resolutionEvidence) { evidence -> evidence.label }
        return AtlasEdge(
            id = relationshipIds.first(),
            sourceId = sourceId,
            targetId = targetId,
            category = records.predominantCategory(),
            relationshipIds = relationshipIds,
            referenceIds = referenceIds,
            recordCount = records.size,
            kindCounts = kindCounts,
            evidenceCounts = evidenceCounts,
            crossBuild = buildIdForFile(sourceId) != buildIdForFile(targetId),
            hasHeuristic = records.any { it.resolutionEvidence == RelationshipEvidence.HEURISTIC },
            isObservedCycleEdge = records.any { it.id in observedCycleRelationshipIds },
            isAnalysisCycleEdge = (sourceId to targetId) in analysisCycleFileSteps,
        )
    }

    private fun buildIdForFile(fileId: String): String {
        val sourceSetId = filesById.getValue(fileId).sourceSetId
        val projectId = sourceSetsById.getValue(sourceSetId).projectId
        return projectsById.getValue(projectId).buildId
    }
}

private fun selectOverviewFiles(
    candidates: List<FileCandidate>,
    endpoints: Map<RelationshipSnapshot, Pair<String, String>>,
    relationships: List<RelationshipSnapshot>,
): Set<String> {
    val ranked = candidates.sortedWith(FILE_CANDIDATE_COMPARATOR)
    val byId = candidates.associateBy(FileCandidate::fileId)
    val selected = linkedSetOf<String>()
    ranked.distinctBy(FileCandidate::buildId).forEach { candidate -> selected.addWithinLimit(candidate.fileId) }
    ranked.filter { it.findingCount > 0 }.forEach { candidate -> selected.addWithinLimit(candidate.fileId) }
    ranked.filter { it.importanceScore > 0 }.forEach { candidate -> selected.addWithinLimit(candidate.fileId) }
    relationships
        .asSequence()
        .map(endpoints::getValue)
        .filter { (sourceId, targetId) -> byId.getValue(sourceId).buildId != byId.getValue(targetId).buildId }
        .groupingBy { pair -> pair }
        .eachCount()
        .entries
        .sortedWith(compareByDescending<Map.Entry<Pair<String, String>, Int>> { it.value }.thenBy { it.key.first })
        .firstOrNull { entry ->
            entry.key.toList().count { fileId -> fileId !in selected } <=
                AtlasFrame.MAX_VISIBLE_NODES - selected.size
        }?.key
        ?.toList()
        ?.sorted()
        ?.forEach { fileId -> selected.addWithinLimit(fileId) }
    ranked.forEach { candidate -> selected.addWithinLimit(candidate.fileId) }
    return selected
}

private fun MutableSet<String>.addWithinLimit(id: String) {
    if (size < AtlasFrame.MAX_VISIBLE_NODES) add(id)
}

private fun analysisCycleFiles(graphs: List<ProjectGraphShard>): Set<String> =
    graphs.flatMapTo(mutableSetOf()) { graph ->
        val analysis = graph.analysis ?: return@flatMapTo emptyList()
        val cycleComponents = analysis.cycles.flatMapTo(mutableSetOf()) { cycle -> cycle.componentIds }
        analysis.components.filter { it.id in cycleComponents }.mapNotNull(ArchitectureComponentSnapshot::sourceFileId)
    }

private fun analysisCycleSteps(graphs: List<ProjectGraphShard>): Set<Pair<String, String>> =
    graphs.flatMapTo(mutableSetOf()) { graph ->
        val analysis = graph.analysis ?: return@flatMapTo emptyList()
        val components = analysis.components.associateBy { it.id }
        analysis.cycles.flatMap { cycle ->
            cycle.componentIds.zipWithNext().mapNotNull { (sourceId, targetId) ->
                val sourceFileId = components[sourceId]?.sourceFileId
                val targetFileId = components[targetId]?.sourceFileId
                if (sourceFileId == null || targetFileId == null) null else sourceFileId to targetFileId
            }
        }
    }

internal fun stableBuildColor(name: String): String {
    val rawHue = name.hashCode().toLong() * BUILD_COLOR_HUE_STEP
    val hue = ((rawHue % BUILD_COLOR_HUE_COUNT) + BUILD_COLOR_HUE_COUNT) % BUILD_COLOR_HUE_COUNT
    return "hsl($hue 58% 66%)"
}

private fun RelationshipKind.atlasCategory(): AtlasEdgeCategory =
    when (this) {
        RelationshipKind.IMPORT -> AtlasEdgeCategory.IMPORTS
        RelationshipKind.EXTENDS, RelationshipKind.IMPLEMENTS -> AtlasEdgeCategory.INHERITANCE
        RelationshipKind.CALL, RelationshipKind.CONSTRUCTOR -> AtlasEdgeCategory.CALLS
        RelationshipKind.NAME_REFERENCE,
        RelationshipKind.TYPE_REFERENCE,
        RelationshipKind.PROPERTY_TYPE,
        RelationshipKind.PARAMETER_TYPE,
        RelationshipKind.RETURN_TYPE,
        -> AtlasEdgeCategory.REFERENCES
    }

private fun List<RelationshipSnapshot>.predominantCategory(): AtlasEdgeCategory =
    groupBy { relationship -> relationship.kind.atlasCategory() }
        .entries
        .sortedWith(
            compareByDescending<Map.Entry<AtlasEdgeCategory, List<RelationshipSnapshot>>> { (_, records) ->
                records.size
            }.thenBy { entry -> ATLAS_CATEGORY_PRIORITY.getValue(entry.key) },
        ).first()
        .key

private fun <T : Enum<T>> List<RelationshipSnapshot>.atlasCounts(
    selector: (RelationshipSnapshot) -> T,
    label: (T) -> String,
): List<AtlasCount> =
    groupBy(selector)
        .entries
        .sortedBy { (value) -> value.name }
        .map { (value, records) -> AtlasCount(value.name, label(value), records.size) }

private data class MutableFileMetrics(
    var relationshipRecords: Int = 0,
    var internalRecords: Int = 0,
    var crossFileRecords: Int = 0,
    var crossBuildRecords: Int = 0,
    val neighbors: MutableSet<String> = mutableSetOf(),
)

private data class FileMetrics(
    val relationshipRecords: Int,
    val internalRecords: Int,
    val crossFileRecords: Int,
    val crossBuildRecords: Int,
    val neighborCount: Int,
)

private data class FileCandidate(
    val fileId: String,
    val buildId: String,
    val findingCount: Int,
    val importanceScore: Int,
    val metrics: FileMetrics,
)

private val FILE_CANDIDATE_COMPARATOR =
    compareByDescending<FileCandidate> { it.findingCount > 0 }
        .thenByDescending { it.findingCount }
        .thenByDescending { it.importanceScore }
        .thenByDescending { it.metrics.crossBuildRecords }
        .thenByDescending { it.metrics.neighborCount }
        .thenByDescending { it.metrics.crossFileRecords }
        .thenByDescending { it.metrics.relationshipRecords }
        .thenBy(FileCandidate::fileId)

private const val BUILD_COLOR_HUE_COUNT = 360L
private const val BUILD_COLOR_HUE_STEP = 137L
private val ATLAS_CATEGORY_PRIORITY =
    mapOf(
        AtlasEdgeCategory.INHERITANCE to 0,
        AtlasEdgeCategory.CALLS to 1,
        AtlasEdgeCategory.REFERENCES to 2,
        AtlasEdgeCategory.IMPORTS to 3,
        AtlasEdgeCategory.STRUCTURAL to 4,
        AtlasEdgeCategory.MIXED to 5,
    )
