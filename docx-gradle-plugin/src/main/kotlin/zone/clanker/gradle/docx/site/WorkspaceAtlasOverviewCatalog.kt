package zone.clanker.gradle.docx.site

import zone.clanker.report.model.ArchitectureComponentSnapshot
import zone.clanker.report.model.AtlasBuild
import zone.clanker.report.model.AtlasCount
import zone.clanker.report.model.AtlasEdge
import zone.clanker.report.model.AtlasEdgeCategory
import zone.clanker.report.model.AtlasLens
import zone.clanker.report.model.AtlasNode
import zone.clanker.report.model.AtlasNodeType
import zone.clanker.report.model.BuildSnapshot
import zone.clanker.report.model.FindingSnapshot
import zone.clanker.report.model.ProjectGraphShard
import zone.clanker.report.model.ProjectSnapshot
import zone.clanker.report.model.ReferenceSnapshot
import zone.clanker.report.model.RelationshipEvidence
import zone.clanker.report.model.RelationshipKind
import zone.clanker.report.model.RelationshipSnapshot
import zone.clanker.report.model.SourceFileSnapshot
import zone.clanker.report.model.SourceSetSnapshot
import zone.clanker.report.model.SymbolSnapshot
import zone.clanker.report.model.WorkspaceSnapshot

internal data class WorkspaceOverviewNodeCandidate(
    val id: String,
    val buildId: String,
    val findingCount: Int,
    val importanceScore: Int,
    val observedCycle: Boolean,
    val analysisCycle: Boolean,
    val relationshipCount: Int,
)

internal data class WorkspaceOverviewRelationshipFact(
    val relationship: RelationshipSnapshot,
    val sourceId: String,
    val targetId: String,
)

internal class WorkspaceAtlasOverviewCatalog(
    snapshot: WorkspaceSnapshot,
    projection: ProjectedDocxSite,
) {
    private val projectGraphs = projection.projectGraphs
    private val buildsById = snapshot.builds.associateBy(BuildSnapshot::id)
    private val projectsById = snapshot.projects.associateBy(ProjectSnapshot::id)
    private val sourceSetsById = snapshot.sourceSets.associateBy(SourceSetSnapshot::id)
    private val filesById = projectGraphs.flattenDistinct(ProjectGraphShard::files, SourceFileSnapshot::id)
    private val symbolsById = projectGraphs.flattenDistinct(ProjectGraphShard::symbols, SymbolSnapshot::id)
    private val referencesById = projectGraphs.flattenDistinct(ProjectGraphShard::references, ReferenceSnapshot::id)
    private val endpoints = WorkspaceOverviewEndpoints(filesById, symbolsById, referencesById)
    private val ownedSymbolIds =
        projectGraphs.flatMapTo(mutableSetOf()) { project -> project.ownedSymbolIds }
    private val relationships =
        projectGraphs
            .flatMap(ProjectGraphShard::relationships)
            .filter { relationship -> relationship.kind != RelationshipKind.IMPORT }
            .distinctBy(RelationshipSnapshot::id)
            .sortedBy(RelationshipSnapshot::id)
    private val findings = WorkspaceOverviewFindingIndex(projectGraphs, filesById, symbolsById)
    private val cycles = WorkspaceOverviewCycleIndex(projectGraphs, filesById, symbolsById)
    private val importanceBySymbolId =
        snapshot.importantSymbols
            .filter { important -> important.symbolId in symbolsById }
            .associate { important -> important.symbolId to important.score }
    private val importanceByFileId =
        importanceBySymbolId.entries
            .groupBy { (symbolId) -> symbolsById.getValue(symbolId).fileId }
            .mapValues { (_, entries) -> entries.maxOf { entry -> entry.value } }

    fun candidates(lens: AtlasLens): List<WorkspaceOverviewNodeCandidate> {
        val nodeIds = candidateNodeIds(lens)
        val facts = relationshipFacts(lens, nodeIds.toSet())
        val relationshipCounts =
            facts
                .flatMap { fact -> listOf(fact.sourceId, fact.targetId) }
                .groupingBy { id -> id }
                .eachCount()
        return nodeIds.map { nodeId ->
            WorkspaceOverviewNodeCandidate(
                id = nodeId,
                buildId = buildIdForNode(nodeId),
                findingCount = findings.idsForNode(nodeId).size,
                importanceScore = importanceScore(nodeId),
                observedCycle = cycles.isObserved(nodeId),
                analysisCycle = cycles.isAnalysis(nodeId),
                relationshipCount = relationshipCounts[nodeId] ?: 0,
            )
        }
    }

    fun relationshipFacts(
        lens: AtlasLens,
        primaryNodeIds: Set<String>,
    ): List<WorkspaceOverviewRelationshipFact> =
        relationships
            .asSequence()
            .filter { relationship -> lens != AtlasLens.CYCLES || relationship.id in cycles.observedRelationshipIds }
            .mapNotNull { relationship ->
                endpoints.resolve(lens, relationship, primaryNodeIds)?.let { (sourceId, targetId) ->
                    WorkspaceOverviewRelationshipFact(relationship, sourceId, targetId)
                }
            }.sortedBy { fact -> fact.relationship.id }
            .toList()

    fun node(
        nodeId: String,
        primary: Boolean,
        facts: List<WorkspaceOverviewRelationshipFact>,
    ): AtlasNode {
        val incident = facts.filter { fact -> nodeId == fact.sourceId || nodeId == fact.targetId }
        val internal = incident.count { fact -> fact.sourceId == fact.targetId }
        return filesById[nodeId]?.let { file -> fileNode(file, primary, incident.size, internal) }
            ?: symbolNode(symbolsById.getValue(nodeId), primary, incident.size, internal)
    }

    fun edges(facts: List<WorkspaceOverviewRelationshipFact>): List<AtlasEdge> =
        facts
            .filter { fact -> fact.sourceId != fact.targetId }
            .groupBy { fact -> fact.sourceId to fact.targetId }
            .values
            .map { records -> edge(records.sortedBy { fact -> fact.relationship.id }) }
            .sortedBy(AtlasEdge::id)

    fun builds(nodes: List<AtlasNode>): List<AtlasBuild> {
        val representedBuildIds = nodes.mapNotNullTo(mutableSetOf(), AtlasNode::buildId)
        return representedBuildIds.sorted().map { buildId ->
            val build = buildsById.getValue(buildId)
            AtlasBuild(build.id, build.name, build.kind.label, stableBuildColor(build.name))
        }
    }

    private fun candidateNodeIds(lens: AtlasLens): List<String> =
        when (lens) {
            AtlasLens.FILES -> error("The established file overview owns the Files lens")
            AtlasLens.SYMBOLS -> ownedSymbolIds.sorted()
            AtlasLens.PROBLEMS -> findings.problemNodeIds
            AtlasLens.CYCLES -> cycles.cycleNodeIds
        }

    private fun fileNode(
        file: SourceFileSnapshot,
        primary: Boolean,
        relationshipCount: Int,
        internalRecordCount: Int,
    ): AtlasNode {
        val ownership = ownership(file)
        val findingIds = findings.idsForFile(file.id)
        return AtlasNode(
            id = file.id,
            type = AtlasNodeType.FILE,
            name = file.projectRelativePath.substringAfterLast('/'),
            path = file.projectRelativePath,
            buildId = ownership.build.id,
            buildName = ownership.build.name,
            projectId = ownership.project.id,
            projectPath = ownership.project.path,
            sourceSet = ownership.sourceSet.name,
            sourceFileId = file.id,
            language = file.language.label,
            kind = file.language.name,
            primary = primary,
            important = file.id in importanceByFileId,
            importanceScore = importanceByFileId[file.id],
            findingIds = findingIds,
            sourceFileFindingCount = findingIds.size,
            hasObservedCycle = cycles.fileHasObservedCycle(file.id),
            hasAnalysisCycle = cycles.fileHasAnalysisCycle(file.id),
            relationshipRecordCount = relationshipCount,
            internalRecordCount = internalRecordCount,
        )
    }

    private fun symbolNode(
        symbol: SymbolSnapshot,
        primary: Boolean,
        relationshipCount: Int,
        internalRecordCount: Int,
    ): AtlasNode {
        val file = filesById.getValue(symbol.fileId)
        val ownership = ownership(file)
        val findingIds = findings.idsForNode(symbol.id)
        return AtlasNode(
            id = symbol.id,
            type = AtlasNodeType.SYMBOL,
            name = symbol.name,
            qualifiedName = symbol.qualifiedName,
            path = file.projectRelativePath,
            buildId = ownership.build.id,
            buildName = ownership.build.name,
            projectId = ownership.project.id,
            projectPath = ownership.project.path,
            sourceSet = ownership.sourceSet.name,
            sourceFileId = file.id,
            language = file.language.label,
            kind = symbol.kind.name,
            semantic = symbol.declarationSemantic,
            primary = primary,
            important = symbol.id in importanceBySymbolId,
            importanceScore = importanceBySymbolId[symbol.id],
            findingIds = findingIds,
            sourceFileFindingCount = findings.idsForFile(file.id).size,
            hasObservedCycle = cycles.isObserved(symbol.id),
            hasAnalysisCycle = cycles.isAnalysis(symbol.id),
            relationshipRecordCount = relationshipCount,
            internalRecordCount = internalRecordCount,
        )
    }

    private fun edge(facts: List<WorkspaceOverviewRelationshipFact>): AtlasEdge {
        val relationships = facts.map(WorkspaceOverviewRelationshipFact::relationship)
        val evidenceSample = relationships.take(OVERVIEW_EDGE_EVIDENCE_LIMIT)
        val first = facts.first()
        return AtlasEdge(
            id = relationships.first().id,
            sourceId = first.sourceId,
            targetId = first.targetId,
            category = relationships.predominantOverviewCategory(),
            relationshipIds = evidenceSample.map(RelationshipSnapshot::id),
            referenceIds = evidenceSample.map(RelationshipSnapshot::referenceId).distinct().sorted(),
            recordCount = relationships.size,
            kindCounts = relationships.overviewCounts(RelationshipSnapshot::kind) { kind -> kind.label },
            evidenceCounts =
                relationships.overviewCounts(RelationshipSnapshot::resolutionEvidence) { evidence -> evidence.label },
            crossBuild = buildIdForNode(first.sourceId) != buildIdForNode(first.targetId),
            hasHeuristic =
                relationships.any { relationship ->
                    relationship.resolutionEvidence == RelationshipEvidence.HEURISTIC
                },
            isObservedCycleEdge =
                relationships.any { relationship -> relationship.id in cycles.observedRelationshipIds },
            isAnalysisCycleEdge = (first.sourceId to first.targetId) in cycles.analysisSteps,
        )
    }

    private fun ownership(file: SourceFileSnapshot): WorkspaceOverviewOwnership {
        val sourceSet = sourceSetsById.getValue(file.sourceSetId)
        val project = projectsById.getValue(sourceSet.projectId)
        return WorkspaceOverviewOwnership(sourceSet, project, buildsById.getValue(project.buildId))
    }

    private fun buildIdForNode(nodeId: String): String {
        val file = filesById[nodeId] ?: symbolsById[nodeId]?.let { symbol -> filesById.getValue(symbol.fileId) }
        return ownership(requireNotNull(file)).build.id
    }

    private fun importanceScore(nodeId: String): Int =
        importanceBySymbolId[nodeId] ?: importanceByFileId[nodeId] ?: 0
}

private data class WorkspaceOverviewOwnership(
    val sourceSet: SourceSetSnapshot,
    val project: ProjectSnapshot,
    val build: BuildSnapshot,
)

private class WorkspaceOverviewEndpoints(
    private val filesById: Map<String, SourceFileSnapshot>,
    private val symbolsById: Map<String, SymbolSnapshot>,
    private val referencesById: Map<String, ReferenceSnapshot>,
) {
    fun resolve(
        lens: AtlasLens,
        relationship: RelationshipSnapshot,
        primaryNodeIds: Set<String>,
    ): Pair<String, String>? =
        when (lens) {
            AtlasLens.FILES -> file(relationship)
            AtlasLens.SYMBOLS -> symbol(relationship)?.takeIf { endpoints -> endpoints.touches(primaryNodeIds) }
            AtlasLens.PROBLEMS, AtlasLens.CYCLES ->
                symbol(relationship)?.takeIf { endpoints -> endpoints.touches(primaryNodeIds) }
                    ?: file(relationship)?.takeIf { endpoints -> endpoints.touches(primaryNodeIds) }
        }

    private fun file(relationship: RelationshipSnapshot): Pair<String, String>? =
        referencesById[relationship.referenceId]
            ?.sourceFileId
            ?.let { sourceId ->
                symbolsById[relationship.targetSymbolId]?.fileId?.let { targetId -> sourceId to targetId }
            }?.takeIf { (sourceId, targetId) -> sourceId in filesById && targetId in filesById }

    private fun symbol(relationship: RelationshipSnapshot): Pair<String, String>? =
        relationship.sourceSymbolId
            ?.let { sourceId -> sourceId to relationship.targetSymbolId }
            ?.takeIf { (sourceId, targetId) -> sourceId in symbolsById && targetId in symbolsById }
}

private fun Pair<String, String>.touches(nodeIds: Set<String>): Boolean = first in nodeIds || second in nodeIds

private fun <Owner, Item> List<Owner>.flattenDistinct(
    items: (Owner) -> List<Item>,
    id: (Item) -> String,
): Map<String, Item> = flatMap(items).distinctBy(id).associateBy(id)

private fun RelationshipKind.overviewCategory(): AtlasEdgeCategory =
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

private fun List<RelationshipSnapshot>.predominantOverviewCategory(): AtlasEdgeCategory =
    groupBy { relationship -> relationship.kind.overviewCategory() }
        .entries
        .sortedWith(
            compareByDescending<Map.Entry<AtlasEdgeCategory, List<RelationshipSnapshot>>> { entry -> entry.value.size }
                .thenBy { entry -> OVERVIEW_CATEGORY_PRIORITY.getValue(entry.key) },
        ).first()
        .key

private fun <T : Enum<T>> List<RelationshipSnapshot>.overviewCounts(
    selector: (RelationshipSnapshot) -> T,
    label: (T) -> String,
): List<AtlasCount> =
    groupBy(selector)
        .entries
        .sortedBy { entry -> entry.key.name }
        .map { (value, records) -> AtlasCount(value.name, label(value), records.size) }

private val OVERVIEW_CATEGORY_PRIORITY =
    mapOf(
        AtlasEdgeCategory.INHERITANCE to 0,
        AtlasEdgeCategory.CALLS to 1,
        AtlasEdgeCategory.REFERENCES to 2,
        AtlasEdgeCategory.IMPORTS to 3,
        AtlasEdgeCategory.STRUCTURAL to 4,
        AtlasEdgeCategory.MIXED to 5,
    )

private const val OVERVIEW_EDGE_EVIDENCE_LIMIT = 3

private class WorkspaceOverviewFindingIndex(
    projectGraphs: List<ProjectGraphShard>,
    private val filesById: Map<String, SourceFileSnapshot>,
    private val symbolsById: Map<String, SymbolSnapshot>,
) {
    private val nodeIdsByFindingId =
        projectGraphs
            .flatMap { project -> project.resolvedFindings(filesById, symbolsById) }
            .associate { resolution -> resolution.finding.id to resolution.nodeIds }
    private val findingIdsByNodeId =
        nodeIdsByFindingId.entries
            .flatMap { (findingId, nodeIds) -> nodeIds.map { nodeId -> nodeId to findingId } }
            .groupBy({ pair -> pair.first }, { pair -> pair.second })
            .mapValues { (_, ids) -> ids.distinct().sorted() }
    private val findingIdsByFileId =
        findingIdsByNodeId.entries
            .flatMap { (nodeId, findingIds) ->
                val fileId = filesById[nodeId]?.id ?: symbolsById[nodeId]?.fileId
                findingIds.mapNotNull { findingId -> fileId?.let { id -> id to findingId } }
            }.groupBy({ pair -> pair.first }, { pair -> pair.second })
            .mapValues { (_, ids) -> ids.distinct().sorted() }

    val problemNodeIds: List<String> = findingIdsByNodeId.keys.sorted()

    fun idsForNode(nodeId: String): List<String> = findingIdsByNodeId[nodeId].orEmpty()

    fun idsForFile(fileId: String): List<String> = findingIdsByFileId[fileId].orEmpty()
}

private data class WorkspaceFindingResolution(
    val finding: FindingSnapshot,
    val nodeIds: Set<String>,
)

private fun ProjectGraphShard.resolvedFindings(
    filesById: Map<String, SourceFileSnapshot>,
    symbolsById: Map<String, SymbolSnapshot>,
): List<WorkspaceFindingResolution> {
    val components = analysis?.components.orEmpty().associateBy(ArchitectureComponentSnapshot::id)
    val projectFilesByPath = files.associateBy(SourceFileSnapshot::projectRelativePath)
    return (findings + analysis?.findings.orEmpty())
        .distinctBy(FindingSnapshot::id)
        .map { finding ->
            val nodeIds =
                buildSet {
                    finding.fileId?.takeIf(filesById::containsKey)?.let(::add)
                    finding.filePath
                        ?.let(projectFilesByPath::get)
                        ?.id
                        ?.let(::add)
                    finding.symbolIds.filter(symbolsById::containsKey).forEach(::add)
                    finding.resolvedComponentIds
                        .mapNotNull(components::get)
                        .mapNotNull { component ->
                            component.symbolId?.takeIf(symbolsById::containsKey)
                                ?: component.sourceFileId?.takeIf(filesById::containsKey)
                        }.forEach(::add)
                }
            WorkspaceFindingResolution(finding, nodeIds)
        }
}

private class WorkspaceOverviewCycleIndex(
    projectGraphs: List<ProjectGraphShard>,
    filesById: Map<String, SourceFileSnapshot>,
    private val symbolsById: Map<String, SymbolSnapshot>,
) {
    val observedRelationshipIds =
        projectGraphs.flatMapTo(mutableSetOf()) { graph -> graph.cycles.flatMap { cycle -> cycle.relationshipIds } }
    private val observedSymbolIds =
        projectGraphs.flatMapTo(mutableSetOf()) { graph -> graph.cycles.flatMap { cycle -> cycle.symbolIds } }
    private val analysisNodeIds = mutableSetOf<String>()
    val analysisSteps = mutableSetOf<Pair<String, String>>()

    init {
        projectGraphs.forEach { graph ->
            val analysis = graph.analysis ?: return@forEach
            val components = analysis.components.associateBy(ArchitectureComponentSnapshot::id)
            analysis.cycles.forEach { cycle ->
                cycle.componentIds
                    .mapNotNull { id -> components[id]?.nodeId(filesById, symbolsById) }
                    .forEach(analysisNodeIds::add)
                cycle.componentIds
                    .zipWithNext()
                    .mapNotNull { (sourceId, targetId) ->
                        val source = components[sourceId]?.nodeId(filesById, symbolsById)
                        val target = components[targetId]?.nodeId(filesById, symbolsById)
                        if (source == null || target == null) null else source to target
                    }.forEach(analysisSteps::add)
            }
        }
    }

    val cycleNodeIds: List<String> = (observedSymbolIds + analysisNodeIds).sorted()

    fun isObserved(nodeId: String): Boolean = nodeId in observedSymbolIds

    fun isAnalysis(nodeId: String): Boolean = nodeId in analysisNodeIds

    fun fileHasObservedCycle(fileId: String): Boolean =
        observedSymbolIds.any { symbolId -> symbolsById[symbolId]?.fileId == fileId }

    fun fileHasAnalysisCycle(fileId: String): Boolean =
        fileId in analysisNodeIds ||
            analysisNodeIds.any { nodeId -> symbolsById[nodeId]?.fileId == fileId }
}

private fun ArchitectureComponentSnapshot.nodeId(
    filesById: Map<String, SourceFileSnapshot>,
    symbolsById: Map<String, SymbolSnapshot>,
): String? =
    symbolId?.takeIf(symbolsById::containsKey)
        ?: sourceFileId?.takeIf(filesById::containsKey)
