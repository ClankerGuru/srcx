@file:Suppress("TooManyFunctions")

package zone.clanker.gradle.srcx.report

import zone.clanker.gradle.srcx.model.AnalysisSummary
import zone.clanker.gradle.srcx.model.ArchitectureComponent
import zone.clanker.gradle.srcx.model.ArchitectureComponentCycle
import zone.clanker.gradle.srcx.model.Finding
import zone.clanker.gradle.srcx.model.ImportantSymbol
import zone.clanker.gradle.srcx.model.SymbolDetailKind
import zone.clanker.gradle.srcx.model.WorkspaceReference
import zone.clanker.gradle.srcx.model.WorkspaceRelationship
import zone.clanker.gradle.srcx.model.WorkspaceReport
import zone.clanker.gradle.srcx.model.WorkspaceSourceFile
import zone.clanker.gradle.srcx.model.WorkspaceSymbol
import zone.clanker.gradle.srcx.model.WorkspaceSymbolIdentity
import java.security.MessageDigest

private const val CROSS_BUILD_FILE_RESERVED_NODE_COUNT = 2
private const val GRAPH_CYCLE_ID_BYTE_COUNT = 12

internal data class WorkspaceArchitectureFileGraphRenderer(
    val nodes: List<ArchitectureFileNodeRenderer>,
    val edges: List<ArchitectureFileEdgeRenderer>,
    val cycles: List<ArchitectureFileCycleRenderer>,
    val analysisCycles: List<ArchitectureAnalysisCycleRenderer>,
    val findings: List<ArchitectureFindingRenderer>,
    val sourceFiles: List<ArchitectureSourceFileRenderer>,
    val omittedNodeCount: Int,
)

internal fun buildWorkspaceArchitectureFileGraph(
    report: WorkspaceReport,
    selection: GraphSymbolSelectionRenderer,
    relationships: List<WorkspaceRelationship>,
    importantByIdentity: Map<WorkspaceSymbolIdentity, ImportantSymbol>,
    availableEdges: List<ArchitectureFileEdgeRenderer>,
): WorkspaceArchitectureFileGraphRenderer {
    val selectedSymbols = selection.selectedSymbols
    val analysisEvidence = graphAnalysisEvidence(report)
    val findings = analysisEvidence.findings
    val findingFileCandidates = graphFindingFileCandidates(report, findings)
    val fileSelection =
        selectGraphFiles(selectedSymbols, selection.prioritySymbols, relationships, findingFileCandidates)
    val selectedScopes = fileSelection.scopes.toSet()
    val selectedSourceScopes = selectedScopes + selectedSymbols.map { symbol -> symbol.graphFileScope() }
    val availableSourceScopes = report.sourceFiles.map { sourceFile -> sourceFile.graphSourceFileScope() }.toSet()
    val missingSourceScopes = selectedSourceScopes - availableSourceScopes
    require(missingSourceScopes.isEmpty()) {
        "architecture source payload is missing selected files: " +
            missingSourceScopes.map { scope -> scope.id }.sorted().joinToString()
    }
    val symbolsByFile =
        report.workspaceIndex.symbols
            .filter { symbol -> symbol.graphFileScope() in selectedScopes }
            .groupBy { it.graphFileScope() }
    val importantByFile = importantByIdentity.values.groupBy { it.symbol.graphFileScope() }
    val relationshipMetrics = graphFileRelationshipMetrics(relationships, selectedScopes)
    val findingsByProject = findings.groupBy { GraphProjectScopeRenderer(it.build, it.project) }
    val nodes =
        fileSelection.scopes
            .map { scope ->
                graphFileNode(
                    scope = scope,
                    selectedSymbols = symbolsByFile[scope].orEmpty(),
                    importantSymbols = importantByFile[scope].orEmpty(),
                    relationshipMetrics = relationshipMetrics[scope] ?: GraphFileRelationshipMetricsRenderer(),
                    findings = findingsByProject[GraphProjectScopeRenderer(scope.build, scope.project)].orEmpty(),
                    importantByIdentity = importantByIdentity,
                )
            }.sortedBy { it.id }
    val selectedFileIds = selectedScopes.mapTo(mutableSetOf()) { scope -> scope.id }
    val edges =
        availableEdges.filter { edge ->
            edge.source in selectedFileIds && edge.target in selectedFileIds
        }
    return WorkspaceArchitectureFileGraphRenderer(
        nodes = nodes,
        edges = edges,
        cycles = graphFileCycles(nodes, edges),
        analysisCycles = analysisEvidence.cycles,
        findings = findings,
        sourceFiles =
            graphSourceFiles(
                report = report,
                selectedScopes = selectedSourceScopes,
                relationships = relationships,
            ),
        omittedNodeCount = (fileSelection.candidateCount - nodes.size).coerceAtLeast(0),
    )
}

/**
 * Builds the uncapped, shared file catalog used to refill a scoped Atlas view.
 *
 * Unlike [buildWorkspaceArchitectureFileGraph], this projection is deliberately linear in the
 * workspace input: every source file and every aggregated inter-file relationship is serialized
 * exactly once. The browser can therefore page a build, project, source set, or directory without
 * duplicating source text or losing files that did not fit the bounded overview.
 */
internal fun buildWorkspaceArchitectureAvailableFileGraph(
    report: WorkspaceReport,
    relationships: List<WorkspaceRelationship>,
    importantByIdentity: Map<WorkspaceSymbolIdentity, ImportantSymbol>,
): WorkspaceArchitectureFileGraphRenderer {
    val selectedScopes =
        report.sourceFiles
            .map { sourceFile -> sourceFile.graphSourceFileScope() }
            .toSet()
    val requiredScopes =
        report.workspaceIndex.symbols
            .map { symbol -> symbol.graphFileScope() }
            .toSet() +
            relationships.flatMapTo(mutableSetOf<GraphFileScopeRenderer>()) { relationship ->
                setOf(
                    requireNotNull(relationship.source).graphFileScope(),
                    relationship.target.graphFileScope(),
                    relationship.sourceEvidence.graphEvidenceFileScope(),
                )
            }
    val missingSourceScopes = requiredScopes - selectedScopes
    require(missingSourceScopes.isEmpty()) {
        "architecture source payload is missing selected files: " +
            missingSourceScopes.map { scope -> scope.id }.sorted().joinToString()
    }

    val analysisEvidence = graphAnalysisEvidence(report)
    val symbolsByFile =
        report.workspaceIndex.symbols
            .groupBy { symbol -> symbol.graphFileScope() }
    val importantByFile = importantByIdentity.values.groupBy { important -> important.symbol.graphFileScope() }
    val relationshipMetrics = graphFileRelationshipMetrics(relationships, selectedScopes)
    val findingsByProject =
        analysisEvidence.findings.groupBy { finding ->
            GraphProjectScopeRenderer(finding.build, finding.project)
        }
    val nodes =
        selectedScopes
            .map { scope ->
                graphFileNode(
                    scope = scope,
                    selectedSymbols = symbolsByFile[scope].orEmpty(),
                    importantSymbols = importantByFile[scope].orEmpty(),
                    relationshipMetrics = relationshipMetrics[scope] ?: GraphFileRelationshipMetricsRenderer(),
                    findings = findingsByProject[GraphProjectScopeRenderer(scope.build, scope.project)].orEmpty(),
                    importantByIdentity = importantByIdentity,
                )
            }.sortedBy { node -> node.id }
    val edges = graphFileEdges(relationships, selectedScopes)
    return WorkspaceArchitectureFileGraphRenderer(
        nodes = nodes,
        edges = edges,
        cycles = graphFileCycles(nodes, edges),
        analysisCycles = analysisEvidence.cycles,
        findings = analysisEvidence.findings,
        sourceFiles = graphSourceFiles(report, selectedScopes, relationships),
        omittedNodeCount = 0,
    )
}

private fun graphSourceFiles(
    report: WorkspaceReport,
    selectedScopes: Set<GraphFileScopeRenderer>,
    relationships: List<WorkspaceRelationship>,
): List<ArchitectureSourceFileRenderer> {
    val declarationLinesByFile =
        report.workspaceIndex.symbols
            .filter { symbol -> symbol.graphFileScope() in selectedScopes }
            .groupBy { symbol -> symbol.graphFileScope() }
            .mapValues { (_, symbols) ->
                symbols
                    .map { symbol -> symbol.declarationLine }
                    .filter { line -> line > 0 }
                    .distinct()
                    .sorted()
            }
    val relationshipLinesByFile =
        relationships
            .groupBy { relationship -> relationship.sourceEvidence.graphEvidenceFileScope() }
            .mapValues { (_, scopedRelationships) ->
                scopedRelationships
                    .map { relationship -> relationship.sourceEvidence.line }
                    .filter { line -> line > 0 }
                    .distinct()
                    .sorted()
            }
    return report.sourceFiles
        .asSequence()
        .map { sourceFile -> sourceFile to sourceFile.graphSourceFileScope() }
        .filter { (_, scope) -> scope in selectedScopes }
        .map { (sourceFile, scope) ->
            ArchitectureSourceFileRenderer(
                id = scope.id,
                build = sourceFile.build,
                project = sourceFile.project,
                sourceSet = sourceFile.sourceSet,
                path = sourceFile.projectRelativeFile,
                content = sourceFile.content,
                declarationLines = declarationLinesByFile[scope].orEmpty(),
                relationshipLines = relationshipLinesByFile[scope].orEmpty(),
            )
        }.sortedBy { sourceFile -> sourceFile.id }
        .toList()
}

private fun selectGraphFiles(
    selectedSymbols: List<WorkspaceSymbol>,
    prioritySymbols: List<WorkspaceSymbol>,
    relationships: List<WorkspaceRelationship>,
    findingFileCandidates: List<GraphFindingFileCandidateRenderer>,
): GraphFileSelectionRenderer {
    val connectedCandidates = connectedFileCandidates(relationships).sortedWith(FILE_CANDIDATE_COMPARATOR)
    val connectedScopes = connectedCandidates.map { it.scope }
    val connectedScopeSet = connectedScopes.toSet()
    val findingScopes = findingFileCandidates.map { it.scope }
    val priorityScopes = prioritySymbols.map { it.graphFileScope() }.distinct().sortedBy { it.id }
    val strictScopes = (findingScopes + priorityScopes).distinct()
    val seededScopes =
        selectedSymbols
            .map { it.graphFileScope() }
            .distinct()
    val buildSeededScopes = seededScopes.distinctBy { scope -> scope.build }
    val selectedIdentities = selectedSymbols.mapTo(mutableSetOf()) { symbol -> symbol.identity }
    val crossBuildBundles = crossBuildFileBundles(relationships, selectedIdentities)
    val fullStrictSelection = strictScopes.take(ARCHITECTURE_GRAPH_FILE_NODE_LIMIT)
    val strictScopeLimit =
        if (fullStrictSelection.containsCompleteFileBundle(crossBuildBundles) || crossBuildBundles.isEmpty()) {
            ARCHITECTURE_GRAPH_FILE_NODE_LIMIT
        } else {
            ARCHITECTURE_GRAPH_FILE_NODE_LIMIT - CROSS_BUILD_FILE_RESERVED_NODE_COUNT
        }
    val selected = linkedSetOf<GraphFileScopeRenderer>()
    buildSeededScopes.forEach { scope ->
        if (selected.size < ARCHITECTURE_GRAPH_FILE_NODE_LIMIT) selected += scope
    }
    strictScopes.forEach { scope ->
        if (selected.size < strictScopeLimit) selected += scope
    }
    reserveCrossBuildFilePair(selected, crossBuildBundles)
    strictScopes.forEach { scope ->
        if (selected.size < ARCHITECTURE_GRAPH_FILE_NODE_LIMIT) selected += scope
    }
    (seededScopes + connectedScopes).forEach { scope ->
        if (selected.size < ARCHITECTURE_GRAPH_FILE_NODE_LIMIT) selected += scope
    }
    return GraphFileSelectionRenderer(
        scopes = selected.toList(),
        candidateCount = (connectedScopeSet + findingScopes + priorityScopes + seededScopes).size,
    )
}

private fun reserveCrossBuildFilePair(
    selected: MutableSet<GraphFileScopeRenderer>,
    bundles: List<GraphCrossBuildFileBundleRenderer>,
) {
    if (bundles.any { bundle -> bundle.scopes.all { scope -> scope in selected } }) return
    val availableSlots = ARCHITECTURE_GRAPH_FILE_NODE_LIMIT - selected.size
    bundles
        .map { bundle -> bundle.withMissingScopes(selected) }
        .filter { candidate ->
            candidate.missingScopes.isNotEmpty() && candidate.missingScopes.size <= availableSlots
        }.sortedWith(CROSS_BUILD_FILE_BUNDLE_COMPARATOR)
        .firstOrNull()
        ?.missingScopes
        ?.sortedBy { scope -> scope.id }
        ?.forEach { scope -> selected += scope }
}

private fun List<GraphFileScopeRenderer>.containsCompleteFileBundle(
    bundles: List<GraphCrossBuildFileBundleRenderer>,
): Boolean {
    val scopes = toSet()
    return bundles.any { bundle -> bundle.scopes.all { scope -> scope in scopes } }
}

private fun crossBuildFileBundles(
    relationships: List<WorkspaceRelationship>,
    selectedIdentities: Set<WorkspaceSymbolIdentity>,
): List<GraphCrossBuildFileBundleRenderer> =
    relationships
        .filter { relationship ->
            requireNotNull(relationship.source).build != relationship.target.build
        }.groupBy { relationship ->
            val source = requireNotNull(relationship.source).graphFileScope()
            val target = relationship.target.graphFileScope()
            GraphCrossBuildFilePairKeyRenderer(
                first = minOf(source.id, target.id),
                second = maxOf(source.id, target.id),
            )
        }.entries
        .sortedWith(compareBy({ entry -> entry.key.first }, { entry -> entry.key.second }))
        .map { (key, groupedRelationships) ->
            val firstRelationship = groupedRelationships.first()
            val source = requireNotNull(firstRelationship.source).graphFileScope()
            val target = firstRelationship.target.graphFileScope()
            GraphCrossBuildFileBundleRenderer(
                key = key,
                scopes = listOf(source, target).distinct().sortedBy { scope -> scope.id },
                recordCount = groupedRelationships.size,
                selectedSymbolPair =
                    groupedRelationships.any { relationship ->
                        relationship.sourceIdentity in selectedIdentities &&
                            relationship.targetIdentity in selectedIdentities
                    },
            )
        }

private fun graphFindingFileCandidates(
    report: WorkspaceReport,
    findings: List<ArchitectureFindingRenderer>,
): List<GraphFindingFileCandidateRenderer> =
    report.sourceFiles
        .mapNotNull { sourceFile ->
            val matchingFindings =
                findings.filter { finding ->
                    finding.build == sourceFile.build &&
                        finding.project == sourceFile.project &&
                        finding.filePath == sourceFile.projectRelativeFile.normalizedGraphPath()
                }
            if (matchingFindings.isEmpty()) return@mapNotNull null
            GraphFindingFileCandidateRenderer(
                scope = sourceFile.graphSourceFileScope(),
                severityRank = matchingFindings.minOf { finding -> finding.severity.findingSeverityRank() },
                findingCount = matchingFindings.size,
            )
        }.distinctBy { candidate -> candidate.scope }
        .sortedWith(FINDING_FILE_CANDIDATE_COMPARATOR)

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

private fun graphFileRelationshipMetrics(
    relationships: List<WorkspaceRelationship>,
    selectedScopes: Set<GraphFileScopeRenderer>,
): Map<GraphFileScopeRenderer, GraphFileRelationshipMetricsRenderer> {
    val metrics = mutableMapOf<GraphFileScopeRenderer, MutableGraphFileRelationshipMetricsRenderer>()
    relationships.forEach { relationship ->
        val sourceSymbol = requireNotNull(relationship.source)
        val source = sourceSymbol.graphFileScope()
        val target = relationship.target.graphFileScope()
        val sourceMetrics = metrics.getOrPut(source, ::MutableGraphFileRelationshipMetricsRenderer)
        if (source == target) {
            sourceMetrics.totalInternal += 1
            sourceMetrics.internalDeclarations += sourceSymbol.identity.value
            sourceMetrics.internalDeclarations += relationship.target.identity.value
            if (source in selectedScopes) {
                sourceMetrics.shownInternal += 1
                sourceMetrics.internalOccurrences += graphFileOccurrence(relationship)
            }
        } else {
            val targetMetrics = metrics.getOrPut(target, ::MutableGraphFileRelationshipMetricsRenderer)
            sourceMetrics.totalOutgoing += 1
            sourceMetrics.outgoingTargetDeclarations += relationship.target.identity.value
            sourceMetrics.outgoingTargetFiles += target.id
            targetMetrics.totalIncoming += 1
            targetMetrics.incomingSourceDeclarations += sourceSymbol.identity.value
            targetMetrics.incomingSourceFiles += source.id
            if (source in selectedScopes && target in selectedScopes) {
                sourceMetrics.shownOutgoing += 1
                sourceMetrics.shownOutgoingTargetDeclarations += relationship.target.identity.value
                sourceMetrics.shownOutgoingTargetFiles += target.id
                targetMetrics.shownIncoming += 1
                targetMetrics.shownIncomingSourceDeclarations += sourceSymbol.identity.value
                targetMetrics.shownIncomingSourceFiles += source.id
            }
        }
    }
    return metrics.mapValues { (_, value) -> value.toRenderer() }
}

private fun graphAnalysisEvidence(report: WorkspaceReport): GraphAnalysisEvidenceRenderer {
    val scopes = graphAnalysisScopes(report)
    val scopedCycles =
        scopes
            .flatMap { scope ->
                val findingCycles = scope.analysis.findings.mapNotNull { finding -> finding.componentCycle }
                (scope.analysis.architecture.cycles + findingCycles)
                    .distinctBy { cycle -> cycle.componentIds }
                    .map { cycle -> GraphScopedAnalysisCycleRenderer(scope, cycle) }
            }.sortedWith(ANALYSIS_CYCLE_COMPARATOR)
    val cycleIds =
        scopedCycles
            .mapIndexed { index, cycle -> cycle.key to "analysis-cycle-${index + 1}" }
            .toMap()
    val scopedFindings = graphScopedFindings(scopes)
    val findingIds = workspaceArchitectureFindingIds(scopedFindings)
    val findings =
        scopedFindings.map { item ->
            val resolved =
                item.finding.componentIds.mapNotNull { componentId ->
                    graphComponentEvidence(report, item.scope, componentId, item.finding.filePath)
                }
            ArchitectureFindingRenderer(
                id = requireNotNull(findingIds[item.key]),
                build = item.scope.build,
                project = item.scope.project,
                filePath = item.finding.filePath?.normalizedGraphPath(),
                line = item.finding.line,
                severity = item.finding.severity.name,
                message = item.finding.message,
                suggestion = item.finding.suggestion,
                componentIds = item.finding.componentIds,
                componentSymbolIds = resolved.mapNotNull { evidence -> evidence.symbolId }.distinct(),
                componentFileIds = resolved.mapNotNull { evidence -> evidence.fileId }.distinct(),
                analysisCycleId = item.finding.componentCycle?.let { cycleIds[item.scope.cycleKey(it)] },
            )
        }
    val findingsByCycle = findings.filter { it.analysisCycleId != null }.groupBy { it.analysisCycleId }
    val cycles =
        scopedCycles.map { item ->
            val id = requireNotNull(cycleIds[item.key])
            ArchitectureAnalysisCycleRenderer(
                id = id,
                build = item.scope.build,
                project = item.scope.project,
                route =
                    item.cycle.componentIds.map { componentId ->
                        graphComponentEvidence(report, item.scope, componentId)
                    },
                findingIds = findingsByCycle[id].orEmpty().map { finding -> finding.id },
            )
        }
    return GraphAnalysisEvidenceRenderer(findings, cycles)
}

private fun graphAnalysisScopes(report: WorkspaceReport): List<GraphAnalysisScopeRenderer> =
    (
        report.rootProjects.mapNotNull { project ->
            project.analysis?.let { analysis ->
                GraphAnalysisScopeRenderer(report.name, project.projectPath.value, analysis)
            }
        } +
            report.includedBuilds.flatMap { build ->
                build.projects.mapNotNull { project ->
                    project.analysis?.let { analysis ->
                        GraphAnalysisScopeRenderer(build.name, project.projectPath.value, analysis)
                    }
                }
            }
    ).sortedWith(compareBy({ it.build }, { it.project }))

private fun graphScopedFindings(scopes: List<GraphAnalysisScopeRenderer>): List<GraphScopedFindingRenderer> =
    scopes
        .flatMap { scope ->
            scope.analysis.findings
                .distinct()
                .map { finding -> GraphScopedFindingRenderer(scope, finding) }
        }.sortedWith(SCOPED_FINDING_COMPARATOR)

private fun graphComponentEvidence(
    report: WorkspaceReport,
    scope: GraphAnalysisScopeRenderer,
    componentId: String,
    exactFilePath: String? = null,
): ArchitectureAnalysisCycleComponentRenderer {
    val component =
        scope.analysis.architecture.components
            .singleOrNull { it.id == componentId }
    val scopedSymbols = report.workspaceIndex.symbols.filter { it.build == scope.build && it.project == scope.project }
    val symbol = resolveGraphComponentSymbol(scopedSymbols, componentId, component, exactFilePath)
    val sourceFile =
        symbol?.let { selected ->
            report.sourceFiles.singleOrNull { source ->
                source.build == selected.build &&
                    source.project == selected.project &&
                    source.sourceSet == selected.sourceSet &&
                    source.projectRelativeFile == selected.projectRelativeFile
            }
        } ?: resolveGraphComponentSourceFile(report, scope, component, exactFilePath)
    val fileId = symbol?.graphFileScope()?.id ?: sourceFile?.graphSourceFileScope()?.id
    return ArchitectureAnalysisCycleComponentRenderer(
        componentId = componentId,
        name = component?.name ?: componentId.substringAfterLast('.'),
        symbolId = symbol?.identity?.value,
        fileId = fileId,
        filePath = symbol?.projectRelativeFile ?: sourceFile?.projectRelativeFile,
        sourceSet = symbol?.sourceSet ?: sourceFile?.sourceSet,
        line = symbol?.declarationLine ?: component?.line,
    )
}

private fun resolveGraphComponentSymbol(
    symbols: List<WorkspaceSymbol>,
    componentId: String,
    component: ArchitectureComponent?,
    exactFilePath: String?,
): WorkspaceSymbol? {
    val candidates = symbols.filter { symbol -> symbol.qualifiedName == componentId }
    val exactPathCandidates =
        exactFilePath
            ?.normalizedGraphPath()
            ?.let { path ->
                candidates.filter { symbol -> symbol.projectRelativeFile.normalizedGraphPath() == path }
            }.orEmpty()
    if (exactPathCandidates.size == 1) return exactPathCandidates.single()
    val componentPathCandidates =
        component
            ?.filePath
            ?.normalizedGraphPath()
            ?.let { path ->
                candidates.filter { symbol -> symbol.projectRelativeFile.normalizedGraphPath().endsWith(path) }
            }.orEmpty()
    if (componentPathCandidates.size == 1) return componentPathCandidates.single()
    val sourceSetCandidates =
        component
            ?.let { item ->
                candidates.filter { symbol -> symbol.sourceSet.isTestGraphSourceSet() == item.isTest }
            }.orEmpty()
    return sourceSetCandidates.singleOrNull() ?: candidates.singleOrNull()
}

private fun resolveGraphComponentSourceFile(
    report: WorkspaceReport,
    scope: GraphAnalysisScopeRenderer,
    component: ArchitectureComponent?,
    exactFilePath: String?,
): WorkspaceSourceFile? {
    val candidates = report.sourceFiles.filter { it.build == scope.build && it.project == scope.project }
    val exactCandidates =
        exactFilePath
            ?.normalizedGraphPath()
            ?.let { path ->
                candidates.filter { source -> source.projectRelativeFile.normalizedGraphPath() == path }
            }.orEmpty()
    if (exactCandidates.size == 1) return exactCandidates.single()
    val componentCandidates =
        component
            ?.filePath
            ?.normalizedGraphPath()
            ?.let { path ->
                candidates.filter { source -> source.projectRelativeFile.normalizedGraphPath().endsWith(path) }
            }.orEmpty()
    if (componentCandidates.size == 1) return componentCandidates.single()
    return componentCandidates
        .filter { source -> source.sourceSet.isTestGraphSourceSet() == component?.isTest }
        .singleOrNull()
}

internal fun String.isTestGraphSourceSet(): Boolean =
    contains("test", ignoreCase = true)

internal fun workspaceArchitectureFindingIds(
    report: WorkspaceReport,
): Map<WorkspaceArchitectureFindingKeyRenderer, String> =
    workspaceArchitectureFindingIds(graphScopedFindings(graphAnalysisScopes(report)))

internal fun workspaceArchitectureFindingEvidence(
    report: WorkspaceReport,
    graph: WorkspaceArchitectureGraphRenderer,
): WorkspaceArchitectureFindingEvidenceMapRenderer {
    val availableExactFindingIds = graph.availableFileNodes.flatMap { file -> file.findingIds }.toSet()
    val graphFindingsById = graph.findings.associateBy { finding -> finding.id }
    return workspaceArchitectureFindingIds(report).mapValues { (_, id) ->
        val graphFinding = requireNotNull(graphFindingsById[id]) { "architecture graph is missing finding $id" }
        WorkspaceArchitectureFindingEvidenceRenderer(
            id = id,
            linkKind = graphFinding.workspaceArchitectureLinkKind(availableExactFindingIds),
        )
    }
}

private fun ArchitectureFindingRenderer.workspaceArchitectureLinkKind(
    availableExactFindingIds: Set<String>,
): WorkspaceArchitectureFindingLinkKindRenderer? =
    when {
        analysisCycleId != null -> WorkspaceArchitectureFindingLinkKindRenderer.ANALYZER_CYCLE
        id in availableExactFindingIds -> WorkspaceArchitectureFindingLinkKindRenderer.EXACT_FILE
        componentIds.isNotEmpty() -> WorkspaceArchitectureFindingLinkKindRenderer.ANALYZER_COMPONENT
        else -> null
    }

private fun workspaceArchitectureFindingIds(
    findings: List<GraphScopedFindingRenderer>,
): Map<WorkspaceArchitectureFindingKeyRenderer, String> =
    findings.mapIndexed { index, finding -> finding.key to "finding-${index + 1}" }.toMap()

@Suppress("LongParameterList")
private fun graphFileNode(
    scope: GraphFileScopeRenderer,
    selectedSymbols: List<WorkspaceSymbol>,
    importantSymbols: List<ImportantSymbol>,
    relationshipMetrics: GraphFileRelationshipMetricsRenderer,
    findings: List<ArchitectureFindingRenderer>,
    importantByIdentity: Map<WorkspaceSymbolIdentity, ImportantSymbol>,
): ArchitectureFileNodeRenderer {
    val symbols =
        selectedSymbols
            .sortedBy { it.identity.value }
            .map { symbol -> graphFileSymbol(symbol, importantByIdentity[symbol.identity]) }
    val rankedImportant = importantSymbols.sortedWith(FILE_IMPORTANT_SYMBOL_COMPARATOR)
    val normalizedScopePath = scope.path.normalizedGraphPath()
    val exactFindingIds = findings.filter { it.filePath == normalizedScopePath }.map { it.id }
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
        totalIncomingRecordCount = relationshipMetrics.totalIncoming,
        shownIncomingRecordCount = relationshipMetrics.shownIncoming,
        totalOutgoingRecordCount = relationshipMetrics.totalOutgoing,
        shownOutgoingRecordCount = relationshipMetrics.shownOutgoing,
        totalInternalRecordCount = relationshipMetrics.totalInternal,
        shownInternalRecordCount = relationshipMetrics.shownInternal,
        incomingSourceDeclarationCount = relationshipMetrics.incomingSourceDeclarations,
        shownIncomingSourceDeclarationCount = relationshipMetrics.shownIncomingSourceDeclarations,
        incomingSourceFileCount = relationshipMetrics.incomingSourceFiles,
        shownIncomingSourceFileCount = relationshipMetrics.shownIncomingSourceFiles,
        outgoingTargetDeclarationCount = relationshipMetrics.outgoingTargetDeclarations,
        shownOutgoingTargetDeclarationCount = relationshipMetrics.shownOutgoingTargetDeclarations,
        outgoingTargetFileCount = relationshipMetrics.outgoingTargetFiles,
        shownOutgoingTargetFileCount = relationshipMetrics.shownOutgoingTargetFiles,
        internalDeclarationCount = relationshipMetrics.internalDeclarations,
        internalOccurrences = relationshipMetrics.internalOccurrences,
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
        declarationSemantic = symbol.declarationSemantic.name,
        declarationSemanticLabel = symbol.declarationSemantic.label,
        declarationSemanticDetail = symbol.declarationSemantic.detail,
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
        .map { members ->
            val memberSet = members.toSet()
            val componentEdges = edges.filter { it.source in memberSet && it.target in memberSet }
            ArchitectureFileCycleRenderer(
                id = graphFileCycleId(members),
                memberIds = members,
                edgeIds = componentEdges.map { it.id }.sorted(),
                routes = graphCycleRoutes(members, componentEdges),
            )
        }
}

private fun graphFileCycleId(members: List<String>): String {
    val digest =
        MessageDigest
            .getInstance("SHA-256")
            .digest(members.joinToString("\u0000").toByteArray())
            .take(GRAPH_CYCLE_ID_BYTE_COUNT)
            .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
    return "cycle-$digest"
}

private fun graphCycleRoutes(
    members: List<String>,
    edges: List<ArchitectureFileEdgeRenderer>,
): List<ArchitectureFileCycleRouteRenderer> {
    val memberSet = members.toSet()
    val adjacency =
        edges
            .groupBy { edge -> edge.source }
            .mapValues { (_, outgoing) -> outgoing.sortedWith(compareBy({ it.target }, { it.id })) }
    return edges
        .sortedWith(compareBy({ it.source }, { it.target }, { it.id }))
        .map { edge ->
            val returnPath = graphShortestPath(edge.target, edge.source, memberSet, adjacency)
            ArchitectureFileCycleRouteRenderer(
                nodeIds = listOf(edge.source) + returnPath.nodeIds,
                edgeIds = listOf(edge.id) + returnPath.edgeIds,
            ).canonicalCycleRoute()
        }.distinct()
        .sortedWith(compareBy({ it.nodeIds.joinToString("\u0000") }, { it.edgeIds.joinToString("\u0000") }))
}

private fun graphShortestPath(
    start: String,
    target: String,
    members: Set<String>,
    adjacency: Map<String, List<ArchitectureFileEdgeRenderer>>,
): GraphCyclePathRenderer {
    if (start == target) return GraphCyclePathRenderer(listOf(start), emptyList())
    val queue = ArrayDeque<String>()
    val visited = mutableSetOf(start)
    val predecessors = mutableMapOf<String, GraphCyclePredecessorRenderer>()
    queue.addLast(start)
    while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        adjacency[current]
            .orEmpty()
            .filter { edge -> edge.target in members }
            .filter { edge -> visited.add(edge.target) }
            .forEach { edge ->
                predecessors[edge.target] = GraphCyclePredecessorRenderer(current, edge.id)
                queue.addLast(edge.target)
            }
        if (target in predecessors) return graphPath(start, target, predecessors)
    }
    error("Strongly connected cycle edge has no return path: $start -> $target")
}

private data class GraphCyclePathRenderer(
    val nodeIds: List<String>,
    val edgeIds: List<String>,
)

private data class GraphCyclePredecessorRenderer(
    val nodeId: String,
    val edgeId: String,
)

private fun graphPath(
    start: String,
    target: String,
    predecessors: Map<String, GraphCyclePredecessorRenderer>,
): GraphCyclePathRenderer {
    val reversedNodes = mutableListOf(target)
    val reversedEdges = mutableListOf<String>()
    var current = target
    while (current != start) {
        val predecessor = requireNotNull(predecessors[current])
        reversedEdges += predecessor.edgeId
        current = predecessor.nodeId
        reversedNodes += current
    }
    return GraphCyclePathRenderer(reversedNodes.asReversed(), reversedEdges.asReversed())
}

private fun ArchitectureFileCycleRouteRenderer.canonicalCycleRoute(): ArchitectureFileCycleRouteRenderer {
    val openNodes = nodeIds.dropLast(1)
    return edgeIds.indices
        .map { offset ->
            val rotatedNodes = openNodes.drop(offset) + openNodes.take(offset)
            val rotatedEdges = edgeIds.drop(offset) + edgeIds.take(offset)
            ArchitectureFileCycleRouteRenderer(rotatedNodes + rotatedNodes.first(), rotatedEdges)
        }.minWith(compareBy({ it.nodeIds.joinToString("\u0000") }, { it.edgeIds.joinToString("\u0000") }))
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

private data class GraphCrossBuildFileBundleRenderer(
    val key: GraphCrossBuildFilePairKeyRenderer,
    val scopes: List<GraphFileScopeRenderer>,
    val recordCount: Int,
    val selectedSymbolPair: Boolean,
) {
    fun withMissingScopes(
        selectedScopes: Set<GraphFileScopeRenderer>,
    ): GraphSelectableCrossBuildFileBundleRenderer =
        GraphSelectableCrossBuildFileBundleRenderer(
            key = key,
            missingScopes = scopes.filterNot { scope -> scope in selectedScopes },
            recordCount = recordCount,
            selectedSymbolPair = selectedSymbolPair,
        )
}

private data class GraphSelectableCrossBuildFileBundleRenderer(
    val key: GraphCrossBuildFilePairKeyRenderer,
    val missingScopes: List<GraphFileScopeRenderer>,
    val recordCount: Int,
    val selectedSymbolPair: Boolean,
)

private data class GraphCrossBuildFilePairKeyRenderer(
    val first: String,
    val second: String,
)

private data class GraphConnectedFileCandidateRenderer(
    val scope: GraphFileScopeRenderer,
    val neighborCount: Int,
    val crossFileCount: Int,
    val relationshipCount: Int,
)

private data class GraphFindingFileCandidateRenderer(
    val scope: GraphFileScopeRenderer,
    val severityRank: Int,
    val findingCount: Int,
)

private class GraphFileRankAccumulatorRenderer {
    var crossFileCount: Int = 0
    var relationshipCount: Int = 0
    val neighbors: MutableSet<GraphFileScopeRenderer> = mutableSetOf()
}

private data class GraphFileRelationshipMetricsRenderer(
    val totalIncoming: Int = 0,
    val shownIncoming: Int = 0,
    val totalOutgoing: Int = 0,
    val shownOutgoing: Int = 0,
    val totalInternal: Int = 0,
    val shownInternal: Int = 0,
    val incomingSourceDeclarations: Int = 0,
    val shownIncomingSourceDeclarations: Int = 0,
    val incomingSourceFiles: Int = 0,
    val shownIncomingSourceFiles: Int = 0,
    val outgoingTargetDeclarations: Int = 0,
    val shownOutgoingTargetDeclarations: Int = 0,
    val outgoingTargetFiles: Int = 0,
    val shownOutgoingTargetFiles: Int = 0,
    val internalDeclarations: Int = 0,
    val internalOccurrences: List<ArchitectureFileOccurrenceRenderer> = emptyList(),
)

private class MutableGraphFileRelationshipMetricsRenderer {
    var totalIncoming: Int = 0
    var shownIncoming: Int = 0
    var totalOutgoing: Int = 0
    var shownOutgoing: Int = 0
    var totalInternal: Int = 0
    var shownInternal: Int = 0
    val incomingSourceDeclarations: MutableSet<String> = mutableSetOf()
    val shownIncomingSourceDeclarations: MutableSet<String> = mutableSetOf()
    val incomingSourceFiles: MutableSet<String> = mutableSetOf()
    val shownIncomingSourceFiles: MutableSet<String> = mutableSetOf()
    val outgoingTargetDeclarations: MutableSet<String> = mutableSetOf()
    val shownOutgoingTargetDeclarations: MutableSet<String> = mutableSetOf()
    val outgoingTargetFiles: MutableSet<String> = mutableSetOf()
    val shownOutgoingTargetFiles: MutableSet<String> = mutableSetOf()
    val internalDeclarations: MutableSet<String> = mutableSetOf()
    val internalOccurrences: MutableList<ArchitectureFileOccurrenceRenderer> = mutableListOf()

    fun toRenderer(): GraphFileRelationshipMetricsRenderer =
        GraphFileRelationshipMetricsRenderer(
            totalIncoming = totalIncoming,
            shownIncoming = shownIncoming,
            totalOutgoing = totalOutgoing,
            shownOutgoing = shownOutgoing,
            totalInternal = totalInternal,
            shownInternal = shownInternal,
            incomingSourceDeclarations = incomingSourceDeclarations.size,
            shownIncomingSourceDeclarations = shownIncomingSourceDeclarations.size,
            incomingSourceFiles = incomingSourceFiles.size,
            shownIncomingSourceFiles = shownIncomingSourceFiles.size,
            outgoingTargetDeclarations = outgoingTargetDeclarations.size,
            shownOutgoingTargetDeclarations = shownOutgoingTargetDeclarations.size,
            outgoingTargetFiles = outgoingTargetFiles.size,
            shownOutgoingTargetFiles = shownOutgoingTargetFiles.size,
            internalDeclarations = internalDeclarations.size,
            internalOccurrences = internalOccurrences.sortedWith(FILE_OCCURRENCE_COMPARATOR),
        )
}

private data class GraphProjectScopeRenderer(
    val build: String,
    val project: String,
)

private data class GraphAnalysisEvidenceRenderer(
    val findings: List<ArchitectureFindingRenderer>,
    val cycles: List<ArchitectureAnalysisCycleRenderer>,
)

private data class GraphAnalysisScopeRenderer(
    val build: String,
    val project: String,
    val analysis: AnalysisSummary,
) {
    fun cycleKey(cycle: ArchitectureComponentCycle): GraphAnalysisCycleKeyRenderer =
        GraphAnalysisCycleKeyRenderer(build, project, cycle.componentIds)
}

private data class GraphScopedAnalysisCycleRenderer(
    val scope: GraphAnalysisScopeRenderer,
    val cycle: ArchitectureComponentCycle,
) {
    val key: GraphAnalysisCycleKeyRenderer get() = scope.cycleKey(cycle)
}

private data class GraphAnalysisCycleKeyRenderer(
    val build: String,
    val project: String,
    val componentIds: List<String>,
)

internal data class WorkspaceArchitectureFindingKeyRenderer(
    val build: String,
    val project: String,
    val finding: Finding,
)

internal typealias WorkspaceArchitectureFindingEvidenceMapRenderer =
    Map<WorkspaceArchitectureFindingKeyRenderer, WorkspaceArchitectureFindingEvidenceRenderer>

internal data class WorkspaceArchitectureFindingEvidenceRenderer(
    val id: String,
    val linkKind: WorkspaceArchitectureFindingLinkKindRenderer?,
) {
    init {
        require(id.isNotBlank()) { "finding evidence ID must not be blank" }
    }
}

internal enum class WorkspaceArchitectureFindingLinkKindRenderer(
    val buttonLabel: String,
) {
    EXACT_FILE("Show exact finding in Problems map"),
    ANALYZER_COMPONENT("Open analyzer finding evidence"),
    ANALYZER_CYCLE("Open analyzer cycle evidence"),
}

internal fun Finding.architectureEvidenceUnavailableMessage(): String =
    if (filePath != null) {
        "An exact file path was supplied, but it does not match a source file in the typed Atlas catalog."
    } else {
        "Project-scoped review prompt; no exact file or declaration evidence was supplied."
    }

private data class GraphScopedFindingRenderer(
    val scope: GraphAnalysisScopeRenderer,
    val finding: Finding,
) {
    val key: WorkspaceArchitectureFindingKeyRenderer =
        WorkspaceArchitectureFindingKeyRenderer(scope.build, scope.project, finding)
}

private data class GraphFileEdgeKeyRenderer(
    val source: GraphFileScopeRenderer,
    val target: GraphFileScopeRenderer,
)

private fun WorkspaceSymbol.graphFileScope(): GraphFileScopeRenderer =
    GraphFileScopeRenderer(
        build = build,
        project = project,
        sourceSet = sourceSet,
        path = projectRelativeFile,
    )

private fun WorkspaceSourceFile.graphSourceFileScope(): GraphFileScopeRenderer =
    GraphFileScopeRenderer(
        build = build,
        project = project,
        sourceSet = sourceSet,
        path = projectRelativeFile,
    )

private fun WorkspaceReference.graphEvidenceFileScope(): GraphFileScopeRenderer =
    GraphFileScopeRenderer(
        build = build,
        project = project,
        sourceSet = sourceSet,
        path = projectRelativeFile,
    )

private fun String.fileName(): String =
    substringAfterLast('/').substringAfterLast('\\')

internal fun String.normalizedGraphPath(): String = replace('\\', '/')

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

private val FINDING_FILE_CANDIDATE_COMPARATOR =
    compareBy<GraphFindingFileCandidateRenderer> { it.severityRank }
        .thenByDescending { it.findingCount }
        .thenBy { it.scope.id }

private val CROSS_BUILD_FILE_BUNDLE_COMPARATOR =
    compareByDescending<GraphSelectableCrossBuildFileBundleRenderer> { it.selectedSymbolPair }
        .thenBy { it.missingScopes.size }
        .thenByDescending { it.recordCount }
        .thenBy { it.key.first }
        .thenBy { it.key.second }

private val ANALYSIS_CYCLE_COMPARATOR =
    compareBy<GraphScopedAnalysisCycleRenderer> { it.scope.build }
        .thenBy { it.scope.project }
        .thenBy { it.cycle.componentIds.joinToString("\u0000") }

private val SCOPED_FINDING_COMPARATOR =
    compareBy<GraphScopedFindingRenderer> { it.scope.build }
        .thenBy { it.scope.project }
        .thenBy { it.finding.severity.name }
        .thenBy { it.finding.filePath.orEmpty() }
        .thenBy { it.finding.line ?: 0 }
        .thenBy { it.finding.message }
        .thenBy { it.finding.suggestion }
        .thenBy { it.finding.componentIds.joinToString("\u0000") }
        .thenBy {
            it.finding.componentCycle
                ?.componentIds
                ?.joinToString("\u0000")
                .orEmpty()
        }

private fun String.findingSeverityRank(): Int =
    when (this) {
        "FORBIDDEN" -> 0
        "WARNING" -> 1
        else -> 2
    }

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

private val FILE_OCCURRENCE_COMPARATOR =
    compareBy<ArchitectureFileOccurrenceRenderer> { it.build }
        .thenBy { it.project }
        .thenBy { it.sourceSet }
        .thenBy { it.file }
        .thenBy { it.line }
        .thenBy { it.sourceSymbolId }
        .thenBy { it.targetSymbolId }
        .thenBy { it.kind }
        .thenBy { it.evidence }
        .thenBy { it.context }

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
