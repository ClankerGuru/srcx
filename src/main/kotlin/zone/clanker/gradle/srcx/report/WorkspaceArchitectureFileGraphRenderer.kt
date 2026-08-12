@file:Suppress("TooManyFunctions")

package zone.clanker.gradle.srcx.report

import zone.clanker.gradle.srcx.model.Finding
import zone.clanker.gradle.srcx.model.ImportantSymbol
import zone.clanker.gradle.srcx.model.SymbolDetailKind
import zone.clanker.gradle.srcx.model.WorkspaceRelationship
import zone.clanker.gradle.srcx.model.WorkspaceReport
import zone.clanker.gradle.srcx.model.WorkspaceSymbol
import zone.clanker.gradle.srcx.model.WorkspaceSymbolIdentity

internal data class WorkspaceArchitectureFileGraphRenderer(
    val nodes: List<ArchitectureFileNodeRenderer>,
    val edges: List<ArchitectureFileEdgeRenderer>,
    val cycles: List<ArchitectureFileCycleRenderer>,
    val findings: List<ArchitectureFindingRenderer>,
    val omittedNodeCount: Int,
)

internal fun buildWorkspaceArchitectureFileGraph(
    report: WorkspaceReport,
    selectedSymbols: List<WorkspaceSymbol>,
    relationships: List<WorkspaceRelationship>,
    importantByIdentity: Map<WorkspaceSymbolIdentity, ImportantSymbol>,
): WorkspaceArchitectureFileGraphRenderer {
    val selection = selectGraphFiles(selectedSymbols, relationships)
    val selectedScopes = selection.scopes.toSet()
    val symbolsByFile =
        report.workspaceIndex.symbols
            .filter { symbol -> symbol.graphFileScope() in selectedScopes }
            .groupBy { it.graphFileScope() }
    val importantByFile = importantByIdentity.values.groupBy { it.symbol.graphFileScope() }
    val relationshipCounts = graphFileRelationshipCounts(relationships)
    val findings = graphFindings(report)
    val findingsByProject = findings.groupBy { GraphProjectScopeRenderer(it.build, it.project) }
    val nodes =
        selection.scopes
            .map { scope ->
                graphFileNode(
                    scope = scope,
                    selectedSymbols = symbolsByFile[scope].orEmpty(),
                    importantSymbols = importantByFile[scope].orEmpty(),
                    relationshipCounts = relationshipCounts[scope] ?: GraphFileRelationshipCountsRenderer(),
                    findings = findingsByProject[GraphProjectScopeRenderer(scope.build, scope.project)].orEmpty(),
                    importantByIdentity = importantByIdentity,
                )
            }.sortedBy { it.id }
    val edges = graphFileEdges(relationships, selectedScopes)
    return WorkspaceArchitectureFileGraphRenderer(
        nodes = nodes,
        edges = edges,
        cycles = graphFileCycles(nodes, edges),
        findings = findings,
        omittedNodeCount = (selection.candidateCount - nodes.size).coerceAtLeast(0),
    )
}

private fun selectGraphFiles(
    selectedSymbols: List<WorkspaceSymbol>,
    relationships: List<WorkspaceRelationship>,
): GraphFileSelectionRenderer {
    if (selectedSymbols.isNotEmpty()) {
        val scopes =
            selectedSymbols
                .map { it.graphFileScope() }
                .distinct()
                .sortedBy { it.id }
        return GraphFileSelectionRenderer(scopes.take(ARCHITECTURE_GRAPH_FILE_NODE_LIMIT), scopes.size)
    }

    val candidates = connectedFileCandidates(relationships).sortedWith(FILE_CANDIDATE_COMPARATOR)
    return GraphFileSelectionRenderer(
        scopes = candidates.take(ARCHITECTURE_GRAPH_FILE_NODE_LIMIT).map { it.scope },
        candidateCount = candidates.size,
    )
}

private fun connectedFileCandidates(
    relationships: List<WorkspaceRelationship>,
): List<GraphConnectedFileCandidateRenderer> {
    val accumulators = mutableMapOf<GraphFileScopeRenderer, GraphFileRankAccumulatorRenderer>()
    relationships.forEach { relationship ->
        val source = requireNotNull(relationship.source).graphFileScope()
        val target = relationship.target.graphFileScope()
        val sourceAccumulator = accumulators.getOrPut(source, ::GraphFileRankAccumulatorRenderer)
        sourceAccumulator.relationshipCount += 1
        if (source != target) {
            val targetAccumulator = accumulators.getOrPut(target, ::GraphFileRankAccumulatorRenderer)
            targetAccumulator.relationshipCount += 1
            sourceAccumulator.crossFileCount += 1
            targetAccumulator.crossFileCount += 1
            sourceAccumulator.neighbors += target
            targetAccumulator.neighbors += source
        }
    }
    return accumulators.map { (scope, accumulator) ->
        GraphConnectedFileCandidateRenderer(
            scope = scope,
            neighborCount = accumulator.neighbors.size,
            crossFileCount = accumulator.crossFileCount,
            relationshipCount = accumulator.relationshipCount,
        )
    }
}

private fun graphFileRelationshipCounts(
    relationships: List<WorkspaceRelationship>,
): Map<GraphFileScopeRenderer, GraphFileRelationshipCountsRenderer> {
    val counts = mutableMapOf<GraphFileScopeRenderer, MutableGraphFileRelationshipCountsRenderer>()
    relationships.forEach { relationship ->
        val source = requireNotNull(relationship.source).graphFileScope()
        val target = relationship.target.graphFileScope()
        if (source == target) {
            counts.getOrPut(source, ::MutableGraphFileRelationshipCountsRenderer).internal += 1
        } else {
            counts.getOrPut(source, ::MutableGraphFileRelationshipCountsRenderer).outgoing += 1
            counts.getOrPut(target, ::MutableGraphFileRelationshipCountsRenderer).incoming += 1
        }
    }
    return counts.mapValues { (_, value) ->
        GraphFileRelationshipCountsRenderer(value.incoming, value.outgoing, value.internal)
    }
}

private fun graphFindings(report: WorkspaceReport): List<ArchitectureFindingRenderer> {
    val scoped =
        report.rootProjects.flatMap { project ->
            project.analysis?.findings.orEmpty().map { finding ->
                GraphScopedFindingRenderer(report.name, project.projectPath.value, finding)
            }
        } +
            report.includedBuilds.flatMap { build ->
                build.projects.flatMap { project ->
                    project.analysis?.findings.orEmpty().map { finding ->
                        GraphScopedFindingRenderer(build.name, project.projectPath.value, finding)
                    }
                }
            }
    return scoped
        .sortedWith(
            compareBy(
                { it.build },
                { it.project },
                { it.finding.severity.name },
                { it.finding.filePath.orEmpty() },
                { it.finding.message },
                { it.finding.suggestion },
            ),
        ).mapIndexed { index, item ->
            ArchitectureFindingRenderer(
                id = "finding-${index + 1}",
                build = item.build,
                project = item.project,
                filePath = item.finding.filePath,
                severity = item.finding.severity.name,
                message = item.finding.message,
                suggestion = item.finding.suggestion,
            )
        }
}

@Suppress("LongParameterList")
private fun graphFileNode(
    scope: GraphFileScopeRenderer,
    selectedSymbols: List<WorkspaceSymbol>,
    importantSymbols: List<ImportantSymbol>,
    relationshipCounts: GraphFileRelationshipCountsRenderer,
    findings: List<ArchitectureFindingRenderer>,
    importantByIdentity: Map<WorkspaceSymbolIdentity, ImportantSymbol>,
): ArchitectureFileNodeRenderer {
    val symbols =
        selectedSymbols
            .sortedBy { it.identity.value }
            .map { symbol -> graphFileSymbol(symbol, importantByIdentity[symbol.identity]) }
    val rankedImportant = importantSymbols.sortedWith(FILE_IMPORTANT_SYMBOL_COMPARATOR)
    val exactFindingIds = findings.filter { it.filePath == scope.path }.map { it.id }
    return ArchitectureFileNodeRenderer(
        id = scope.id,
        name = scope.path.fileName(),
        path = scope.path,
        scope = scope.scope,
        build = scope.build,
        project = scope.project,
        sourceSet = scope.sourceSet,
        selectedSymbolIds = symbols.map { it.id },
        symbols = symbols,
        importance = rankedImportant.maxOfOrNull { it.score },
        importanceReasons =
            rankedImportant
                .flatMap { it.reasons }
                .distinct()
                .sortedBy { it.ordinal }
                .map { it.label },
        importantSymbolCount = rankedImportant.size,
        incomingCount = relationshipCounts.incoming,
        outgoingCount = relationshipCounts.outgoing,
        internalCount = relationshipCounts.internal,
        findingIds = exactFindingIds,
        projectFindingIds = findings.map { it.id },
        fileFindingCount = exactFindingIds.size,
        projectFindingCount = findings.size,
    )
}

private fun graphFileSymbol(
    symbol: WorkspaceSymbol,
    importantSymbol: ImportantSymbol?,
): ArchitectureFileSymbolRenderer =
    ArchitectureFileSymbolRenderer(
        id = symbol.identity.value,
        name = symbol.name,
        qualifiedName = symbol.qualifiedName,
        kind = symbol.kind.label,
        line = symbol.declarationLine,
        importance = importantSymbol?.score,
    )

private fun graphFileEdges(
    relationships: List<WorkspaceRelationship>,
    selectedScopes: Set<GraphFileScopeRenderer>,
): List<ArchitectureFileEdgeRenderer> =
    relationships
        .filter { relationship ->
            val source = requireNotNull(relationship.source).graphFileScope()
            val target = relationship.target.graphFileScope()
            source != target && source in selectedScopes && target in selectedScopes
        }.groupBy { relationship ->
            GraphFileEdgeKeyRenderer(
                source = requireNotNull(relationship.source).graphFileScope(),
                target = relationship.target.graphFileScope(),
            )
        }.entries
        .sortedWith(compareBy({ it.key.source.id }, { it.key.target.id }))
        .mapIndexed { index, (key, groupedRelationships) ->
            val sortedRelationships = groupedRelationships.sortedWith(FILE_RELATIONSHIP_COMPARATOR)
            ArchitectureFileEdgeRenderer(
                id = "file-edge-${index + 1}",
                source = key.source.id,
                target = key.target.id,
                crossBuild = key.source.build != key.target.build,
                kindCounts = graphFileKindCounts(sortedRelationships),
                evidenceCounts = graphFileEvidenceCounts(sortedRelationships),
                occurrences = sortedRelationships.map(::graphFileOccurrence),
            )
        }

private fun graphFileKindCounts(
    relationships: List<WorkspaceRelationship>,
): List<ArchitectureFileKindCountRenderer> =
    relationships
        .groupingBy { it.kind }
        .eachCount()
        .entries
        .sortedBy { it.key.name }
        .map { (kind, count) -> ArchitectureFileKindCountRenderer(kind.name, kind.label, count) }

private fun graphFileEvidenceCounts(
    relationships: List<WorkspaceRelationship>,
): List<ArchitectureFileEvidenceCountRenderer> =
    relationships
        .groupingBy { it.evidence }
        .eachCount()
        .entries
        .sortedBy { it.key.name }
        .map { (evidence, count) -> ArchitectureFileEvidenceCountRenderer(evidence.name, evidence.label, count) }

private fun graphFileOccurrence(relationship: WorkspaceRelationship): ArchitectureFileOccurrenceRenderer {
    val source = requireNotNull(relationship.source)
    val evidence = relationship.sourceEvidence
    return ArchitectureFileOccurrenceRenderer(
        sourceSymbolId = source.identity.value,
        targetSymbolId = relationship.target.identity.value,
        kind = relationship.kind.name,
        kindLabel = relationship.kind.label,
        evidence = relationship.evidence.name,
        build = evidence.build,
        project = evidence.project,
        sourceSet = evidence.sourceSet,
        file = evidence.projectRelativeFile,
        line = evidence.line,
        context = evidence.context,
    )
}

private fun graphFileCycles(
    nodes: List<ArchitectureFileNodeRenderer>,
    edges: List<ArchitectureFileEdgeRenderer>,
): List<ArchitectureFileCycleRenderer> {
    val memberIds = nodes.map { it.id }.sorted()
    val adjacency = memberIds.associateWith { mutableListOf<String>() }
    val reverseAdjacency = memberIds.associateWith { mutableListOf<String>() }
    edges.forEach { edge ->
        adjacency.getValue(edge.source) += edge.target
        reverseAdjacency.getValue(edge.target) += edge.source
    }
    adjacency.values.forEach { it.sort() }
    reverseAdjacency.values.forEach { it.sort() }
    val visited = mutableSetOf<String>()
    val finishOrder = mutableListOf<String>()
    memberIds.forEach { memberId -> graphDepthFirstOrder(memberId, adjacency, visited, finishOrder) }
    visited.clear()
    val components = mutableListOf<List<String>>()
    finishOrder.asReversed().forEach { memberId ->
        if (memberId !in visited) {
            val component = mutableListOf<String>()
            graphCollectComponent(memberId, reverseAdjacency, visited, component)
            if (component.size > 1) components += component.sorted()
        }
    }
    return components
        .sortedBy { it.joinToString("\u0000") }
        .mapIndexed { index, members ->
            val memberSet = members.toSet()
            ArchitectureFileCycleRenderer(
                id = "cycle-${index + 1}",
                memberIds = members,
                edgeIds =
                    edges
                        .filter { it.source in memberSet && it.target in memberSet }
                        .map { it.id }
                        .sorted(),
            )
        }
}

private fun graphDepthFirstOrder(
    memberId: String,
    adjacency: Map<String, List<String>>,
    visited: MutableSet<String>,
    finishOrder: MutableList<String>,
) {
    if (!visited.add(memberId)) return
    adjacency.getValue(memberId).forEach { neighbor ->
        graphDepthFirstOrder(neighbor, adjacency, visited, finishOrder)
    }
    finishOrder += memberId
}

private fun graphCollectComponent(
    memberId: String,
    adjacency: Map<String, List<String>>,
    visited: MutableSet<String>,
    component: MutableList<String>,
) {
    if (!visited.add(memberId)) return
    component += memberId
    adjacency.getValue(memberId).forEach { neighbor ->
        graphCollectComponent(neighbor, adjacency, visited, component)
    }
}

private data class GraphFileScopeRenderer(
    val build: String,
    val project: String,
    val sourceSet: String,
    val path: String,
) {
    val id: String get() = "file::$build::$project::$sourceSet::$path"
    val scope: String get() = "$build::$project::$sourceSet"
}

private data class GraphFileSelectionRenderer(
    val scopes: List<GraphFileScopeRenderer>,
    val candidateCount: Int,
)

private data class GraphConnectedFileCandidateRenderer(
    val scope: GraphFileScopeRenderer,
    val neighborCount: Int,
    val crossFileCount: Int,
    val relationshipCount: Int,
)

private class GraphFileRankAccumulatorRenderer {
    var crossFileCount: Int = 0
    var relationshipCount: Int = 0
    val neighbors: MutableSet<GraphFileScopeRenderer> = mutableSetOf()
}

private data class GraphFileRelationshipCountsRenderer(
    val incoming: Int = 0,
    val outgoing: Int = 0,
    val internal: Int = 0,
)

private class MutableGraphFileRelationshipCountsRenderer {
    var incoming: Int = 0
    var outgoing: Int = 0
    var internal: Int = 0
}

private data class GraphProjectScopeRenderer(
    val build: String,
    val project: String,
)

private data class GraphScopedFindingRenderer(
    val build: String,
    val project: String,
    val finding: Finding,
)

private data class GraphFileEdgeKeyRenderer(
    val source: GraphFileScopeRenderer,
    val target: GraphFileScopeRenderer,
)

private fun WorkspaceSymbol.graphFileScope(): GraphFileScopeRenderer =
    GraphFileScopeRenderer(build, project, sourceSet, projectRelativeFile)

private fun String.fileName(): String =
    substringAfterLast('/').substringAfterLast('\\')

private fun SymbolDetailKind.fileGraphRank(): Int =
    FILE_GRAPH_KIND_ORDER.indexOf(this)

private val FILE_IMPORTANT_SYMBOL_COMPARATOR =
    compareByDescending<ImportantSymbol> { it.score }
        .thenBy { it.symbol.kind.fileGraphRank() }
        .thenBy { it.symbol.identity.value }

private val FILE_CANDIDATE_COMPARATOR =
    compareByDescending<GraphConnectedFileCandidateRenderer> { it.neighborCount }
        .thenByDescending { it.crossFileCount }
        .thenByDescending { it.relationshipCount }
        .thenBy { it.scope.id }

private val FILE_RELATIONSHIP_COMPARATOR =
    compareBy<WorkspaceRelationship> { it.sourceIdentity?.value.orEmpty() }
        .thenBy { it.targetIdentity.value }
        .thenBy { it.kind.name }
        .thenBy { it.evidence.name }
        .thenBy { it.sourceEvidence.build }
        .thenBy { it.sourceEvidence.project }
        .thenBy { it.sourceEvidence.sourceSet }
        .thenBy { it.sourceEvidence.projectRelativeFile }
        .thenBy { it.sourceEvidence.line }
        .thenBy { it.sourceEvidence.context }

private val FILE_GRAPH_KIND_ORDER =
    listOf(
        SymbolDetailKind.INTERFACE,
        SymbolDetailKind.CLASS,
        SymbolDetailKind.DATA_CLASS,
        SymbolDetailKind.ENUM,
        SymbolDetailKind.OBJECT,
        SymbolDetailKind.FUNCTION,
        SymbolDetailKind.PROPERTY,
    )
