@file:Suppress("TooManyFunctions")

package zone.clanker.gradle.srcx.report

import zone.clanker.gradle.srcx.model.AnalysisSummary
import zone.clanker.gradle.srcx.model.ArchitectureComponent
import zone.clanker.gradle.srcx.model.Finding
import zone.clanker.gradle.srcx.model.ImportantSymbol
import zone.clanker.gradle.srcx.model.ProjectSummary
import zone.clanker.gradle.srcx.model.ReferenceEvidence
import zone.clanker.gradle.srcx.model.SymbolDetailKind
import zone.clanker.gradle.srcx.model.WorkspaceRelationship
import zone.clanker.gradle.srcx.model.WorkspaceRelationshipKind
import zone.clanker.gradle.srcx.model.WorkspaceReport
import zone.clanker.gradle.srcx.model.WorkspaceSymbol
import zone.clanker.gradle.srcx.model.WorkspaceSymbolIdentity
import zone.clanker.gradle.srcx.model.WorkspaceSymbolUsage
import java.lang.Math.floorMod

/** Maximum number of source symbols emitted into the interactive architecture graph. */
internal const val ARCHITECTURE_GRAPH_NODE_LIMIT = 42
internal const val ARCHITECTURE_GRAPH_FILE_NODE_LIMIT = ARCHITECTURE_GRAPH_NODE_LIMIT
private const val ARCHITECTURE_GRAPH_IMPORTANT_SEED_LIMIT = 21
private const val MINIMUM_CLOSED_ANALYSIS_CYCLE_SIZE = 3
private const val CROSS_BUILD_RESERVED_NODE_COUNT = 2

internal fun buildWorkspaceArchitectureGraph(report: WorkspaceReport): WorkspaceArchitectureGraphRenderer {
    require(
        report.workspaceIndex.symbols
            .distinctBy { it.identity }
            .size == report.workspaceIndex.symbols.size,
    ) {
        "workspaceIndex symbols must have distinct workspace identities"
    }
    require(
        report.importantSymbols
            .distinctBy { it.symbol.identity }
            .size == report.importantSymbols.size,
    ) {
        "importantSymbols must have distinct workspace identities"
    }
    val importantByIdentity =
        report.importantSymbols
            .sortedWith(IMPORTANT_SYMBOL_COMPARATOR)
            .associateBy { it.symbol.identity }
    val relationships =
        report.workspaceIndex.relationships
            .filterNot { it.kind == WorkspaceRelationshipKind.IMPORT }
            .sortedWith(RELATIONSHIP_COMPARATOR)
    val resolvedRelationships = relationships.filter { it.source != null }
    val prioritySymbols = graphPrioritySymbols(report)
    val buildRepresentatives =
        graphBuildRepresentatives(
            report = report,
            prioritySymbols = prioritySymbols,
            importantByIdentity = importantByIdentity,
            relationships = resolvedRelationships,
        )
    val selectedSymbols =
        selectGraphSymbols(
            buildRepresentatives = buildRepresentatives,
            prioritySymbols = prioritySymbols,
            importantByIdentity = importantByIdentity,
            relationships = resolvedRelationships,
        )
    val selection =
        GraphSymbolSelectionRenderer(
            prioritySymbols = prioritySymbols,
            buildRepresentatives = buildRepresentatives,
            selectedSymbols = selectedSymbols,
        )
    return renderWorkspaceArchitectureGraph(
        report = report,
        importantByIdentity = importantByIdentity,
        resolvedRelationships = resolvedRelationships,
        selection = selection,
    )
}

private fun renderWorkspaceArchitectureGraph(
    report: WorkspaceReport,
    importantByIdentity: Map<WorkspaceSymbolIdentity, ImportantSymbol>,
    resolvedRelationships: List<WorkspaceRelationship>,
    selection: GraphSymbolSelectionRenderer,
): WorkspaceArchitectureGraphRenderer {
    val selectedIdentities = selection.selectedSymbols.mapTo(mutableSetOf()) { it.identity }
    val selectedIdentityValues = selectedIdentities.mapTo(mutableSetOf()) { identity -> identity.value }
    val symbolUsages = graphSymbolUsages(report.workspaceIndex.symbols, resolvedRelationships)
    val availableNodes = graphNodes(report.workspaceIndex.symbols, importantByIdentity, symbolUsages)
    val availableEdges =
        graphEdges(
            relationships = resolvedRelationships,
            selectedIdentities =
                report.workspaceIndex.symbols.mapTo(mutableSetOf()) { symbol -> symbol.identity },
        )
    val availableFileGraph =
        buildWorkspaceArchitectureAvailableFileGraph(
            report = report,
            relationships = resolvedRelationships,
            importantByIdentity = importantByIdentity,
        )
    val nodes = graphNodes(selection.selectedSymbols, importantByIdentity, symbolUsages)
    val clusters = graphClusters(nodes)
    val edges =
        availableEdges.filter { edge ->
            edge.source in selectedIdentityValues && edge.target in selectedIdentityValues
        }
    val candidateCount =
        graphCandidateCount(
            prioritySymbols = selection.prioritySymbols,
            buildRepresentatives = selection.buildRepresentatives,
            importantByIdentity = importantByIdentity,
            relationships = resolvedRelationships,
        )
    val fileGraph =
        buildWorkspaceArchitectureFileGraph(
            report = report,
            selection = selection,
            relationships = resolvedRelationships,
            importantByIdentity = importantByIdentity,
            availableEdges = availableFileGraph.edges,
        )
    return WorkspaceArchitectureGraphRenderer(
        builds = graphBuilds(report, nodes, fileGraph.nodes),
        nodes = nodes,
        edges = edges,
        clusters = clusters,
        fileNodes = fileGraph.nodes,
        fileEdges = fileGraph.edges,
        cycles = fileGraph.cycles,
        analysisCycles = fileGraph.analysisCycles,
        findings = fileGraph.findings,
        sourceFiles = availableFileGraph.sourceFiles,
        availableNodes = availableNodes,
        availableEdges = availableEdges,
        availableFileNodes = availableFileGraph.nodes,
        availableFileEdges = availableFileGraph.edges,
        availableCycles = availableFileGraph.cycles,
        omittedNodeCount = (candidateCount - nodes.size).coerceAtLeast(0),
        omittedFileNodeCount = fileGraph.omittedNodeCount,
        totalRelationshipRecordCount = resolvedRelationships.size,
    )
}

private fun selectGraphSymbols(
    buildRepresentatives: List<WorkspaceSymbol>,
    prioritySymbols: List<WorkspaceSymbol>,
    importantByIdentity: Map<WorkspaceSymbolIdentity, ImportantSymbol>,
    relationships: List<WorkspaceRelationship>,
): List<WorkspaceSymbol> {
    val selected = linkedMapOf<WorkspaceSymbolIdentity, WorkspaceSymbol>()
    val rankedImportant = importantByIdentity.values.sortedWith(IMPORTANT_SYMBOL_COMPARATOR)
    val crossBuildBundles = crossBuildRelationshipBundles(importantByIdentity, relationships)
    val fullPrioritySelection = prioritySymbols.take(ARCHITECTURE_GRAPH_NODE_LIMIT)
    val strictPriorityLimit =
        if (fullPrioritySelection.containsCompleteBundle(crossBuildBundles) || crossBuildBundles.isEmpty()) {
            ARCHITECTURE_GRAPH_NODE_LIMIT
        } else {
            ARCHITECTURE_GRAPH_NODE_LIMIT - CROSS_BUILD_RESERVED_NODE_COUNT
        }
    // Keep every non-empty build visible, then spend the remaining bounded slots on findings, cycles, and evidence.
    buildRepresentatives.forEach { symbol ->
        if (selected.size < ARCHITECTURE_GRAPH_NODE_LIMIT) selected.putIfAbsent(symbol.identity, symbol)
    }
    prioritySymbols.forEach { symbol ->
        if (selected.size < strictPriorityLimit) selected.putIfAbsent(symbol.identity, symbol)
    }
    reserveCrossBuildSymbols(selected, crossBuildBundles)
    prioritySymbols.forEach { symbol ->
        if (selected.size < ARCHITECTURE_GRAPH_NODE_LIMIT) selected.putIfAbsent(symbol.identity, symbol)
    }
    val relationshipAnchors =
        prioritySymbols.map { symbol -> symbol.identity } +
            rankedImportant
                .take(ARCHITECTURE_GRAPH_IMPORTANT_SEED_LIMIT)
                .map { important -> important.symbol.identity }
    selectConnectedSymbols(
        selected = selected,
        anchorIdentities = relationshipAnchors.toSet(),
        importantByIdentity = importantByIdentity,
        relationships = relationships,
    )
    rankedImportant.forEach { important ->
        if (selected.size < ARCHITECTURE_GRAPH_NODE_LIMIT) {
            selected.putIfAbsent(important.symbol.identity, important.symbol)
        }
    }
    return selected.values.toList()
}

private fun graphBuildRepresentatives(
    report: WorkspaceReport,
    prioritySymbols: List<WorkspaceSymbol>,
    importantByIdentity: Map<WorkspaceSymbolIdentity, ImportantSymbol>,
    relationships: List<WorkspaceRelationship>,
): List<WorkspaceSymbol> {
    val candidates =
        (
            prioritySymbols +
                importantByIdentity.values.sortedWith(IMPORTANT_SYMBOL_COMPARATOR).map { it.symbol } +
                relationships.flatMap { relationship ->
                    listOfNotNull(relationship.source, relationship.target)
                } +
                report.workspaceIndex.symbols.sortedWith(CONNECTED_SYMBOL_COMPARATOR)
        ).distinctBy { symbol -> symbol.identity }
    val buildOrder = listOf(report.name) + report.includedBuilds.map { build -> build.name }.sorted()
    return buildOrder.mapNotNull { build -> candidates.firstOrNull { symbol -> symbol.build == build } }
}

private fun reserveCrossBuildSymbols(
    selected: MutableMap<WorkspaceSymbolIdentity, WorkspaceSymbol>,
    bundles: List<GraphRelationshipBundleRenderer>,
) {
    if (bundles.any { bundle -> bundle.symbols.all { symbol -> symbol.identity in selected } }) return
    val availableSlots = ARCHITECTURE_GRAPH_NODE_LIMIT - selected.size
    bundles
        .map { bundle -> bundle.withMissingSymbols(selected.keys) }
        .filter { candidate ->
            candidate.missingSymbols.isNotEmpty() && candidate.missingSymbols.size <= availableSlots
        }.sortedWith(CONNECTED_BUNDLE_COMPARATOR)
        .firstOrNull()
        ?.missingSymbols
        ?.sortedWith(CONNECTED_SYMBOL_COMPARATOR)
        ?.forEach { symbol -> selected.putIfAbsent(symbol.identity, symbol) }
}

private fun List<WorkspaceSymbol>.containsCompleteBundle(
    bundles: List<GraphRelationshipBundleRenderer>,
): Boolean {
    val identities = mapTo(mutableSetOf()) { symbol -> symbol.identity }
    return bundles.any { bundle -> bundle.symbols.all { symbol -> symbol.identity in identities } }
}

private fun selectConnectedSymbols(
    selected: MutableMap<WorkspaceSymbolIdentity, WorkspaceSymbol>,
    anchorIdentities: Set<WorkspaceSymbolIdentity>,
    importantByIdentity: Map<WorkspaceSymbolIdentity, ImportantSymbol>,
    relationships: List<WorkspaceRelationship>,
) {
    val bundles = relationshipBundles(anchorIdentities, importantByIdentity, relationships)
    while (selected.size < ARCHITECTURE_GRAPH_NODE_LIMIT) {
        val availableSlots = ARCHITECTURE_GRAPH_NODE_LIMIT - selected.size
        // Complete an already-visible pair before spending two slots on a new pair.
        val next =
            bundles
                .map { bundle -> bundle.withMissingSymbols(selected.keys) }
                .filter { candidate ->
                    candidate.missingSymbols.isNotEmpty() && candidate.missingSymbols.size <= availableSlots
                }.sortedWith(CONNECTED_BUNDLE_COMPARATOR)
                .firstOrNull()
                ?: break
        next.missingSymbols
            .sortedWith(CONNECTED_SYMBOL_COMPARATOR)
            .forEach { symbol -> selected.putIfAbsent(symbol.identity, symbol) }
    }
}

private fun relationshipBundles(
    anchorIdentities: Set<WorkspaceSymbolIdentity>,
    importantByIdentity: Map<WorkspaceSymbolIdentity, ImportantSymbol>,
    relationships: List<WorkspaceRelationship>,
): List<GraphRelationshipBundleRenderer> =
    aggregateRelationshipBundles(
        importantByIdentity = importantByIdentity,
        relationships =
            relationships.filter { relationship ->
                relationship.sourceIdentity in anchorIdentities || relationship.targetIdentity in anchorIdentities
            },
    )

private fun crossBuildRelationshipBundles(
    importantByIdentity: Map<WorkspaceSymbolIdentity, ImportantSymbol>,
    relationships: List<WorkspaceRelationship>,
): List<GraphRelationshipBundleRenderer> =
    aggregateRelationshipBundles(
        importantByIdentity = importantByIdentity,
        relationships =
            relationships.filter { relationship ->
                requireNotNull(relationship.source).build != relationship.target.build
            },
    )

private fun aggregateRelationshipBundles(
    importantByIdentity: Map<WorkspaceSymbolIdentity, ImportantSymbol>,
    relationships: List<WorkspaceRelationship>,
): List<GraphRelationshipBundleRenderer> =
    relationships
        .groupBy { relationship ->
            val sourceIdentity = requireNotNull(relationship.sourceIdentity)
            GraphSymbolPairKeyRenderer(
                first = minOf(sourceIdentity.value, relationship.targetIdentity.value),
                second = maxOf(sourceIdentity.value, relationship.targetIdentity.value),
            )
        }.entries
        .sortedWith(compareBy({ entry -> entry.key.first }, { entry -> entry.key.second }))
        .map { (key, groupedRelationships) ->
            val symbols =
                groupedRelationships
                    .flatMap { relationship -> listOf(requireNotNull(relationship.source), relationship.target) }
                    .distinctBy { symbol -> symbol.identity }
                    .sortedBy { symbol -> symbol.identity.value }
            GraphRelationshipBundleRenderer(
                key = key,
                symbols = symbols,
                recordCount = groupedRelationships.size,
                crossBuild =
                    groupedRelationships.any { relationship ->
                        requireNotNull(relationship.source).build != relationship.target.build
                    },
                importance = symbols.maxOf { symbol -> importantByIdentity[symbol.identity]?.score ?: 0 },
                bestKindRank = symbols.minOf { symbol -> symbol.kind.graphRank() },
            )
        }

private fun graphPrioritySymbols(report: WorkspaceReport): List<WorkspaceSymbol> {
    val requests =
        report.rootProjects.flatMap { project ->
            project.analysis
                ?.let { analysis ->
                    graphPriorityRequests(analysis, report.name, project.projectPath.value)
                }.orEmpty()
        } +
            report.includedBuilds.flatMap { build ->
                build.projects.flatMap { project ->
                    project.analysis
                        ?.let { analysis ->
                            graphPriorityRequests(analysis, build.name, project.projectPath.value)
                        }.orEmpty()
                }
            }
    val symbolsByScopeAndName =
        report.workspaceIndex.symbols.groupBy { symbol -> Triple(symbol.build, symbol.project, symbol.qualifiedName) }
    return requests
        .sortedWith(PRIORITY_SYMBOL_REQUEST_COMPARATOR)
        .mapNotNull { request ->
            val scope = Triple(request.build, request.project, request.componentId)
            val candidates = symbolsByScopeAndName[scope].orEmpty()
            val exactFile =
                request.exactFilePath?.normalizedGraphPath()?.let { path ->
                    candidates.filter { symbol -> symbol.projectRelativeFile.normalizedGraphPath() == path }
                }
            val componentFile =
                request.componentFilePath?.normalizedGraphPath()?.let { path ->
                    candidates.filter { symbol -> symbol.projectRelativeFile.normalizedGraphPath().endsWith(path) }
                }
            val sourceSet =
                request.isTest?.let { isTest ->
                    candidates.filter { symbol -> symbol.sourceSet.isTestGraphSourceSet() == isTest }
                }
            exactFile?.singleOrNull()
                ?: componentFile?.singleOrNull()
                ?: sourceSet?.singleOrNull()
                ?: candidates.singleOrNull()
        }.distinctBy { symbol -> symbol.identity }
}

private fun graphPriorityRequests(
    analysis: AnalysisSummary,
    build: String,
    project: String,
): List<GraphPrioritySymbolRequestRenderer> =
    analysis.findings.flatMap { finding ->
        graphFindingPriorityRequests(finding, build, project, analysis.architecture.components)
    } +
        analysis.architecture.cycles.flatMap { cycle ->
            cycle.componentIds.dropLast(1).map { componentId ->
                val component = analysis.architecture.components.singleOrNull { it.id == componentId }
                GraphPrioritySymbolRequestRenderer(
                    build = build,
                    project = project,
                    componentId = componentId,
                    exactFilePath = null,
                    componentFilePath = component?.filePath,
                    isTest = component?.isTest,
                    severity = "WARNING",
                    cycle = true,
                )
            }
        }

private fun graphFindingPriorityRequests(
    finding: Finding,
    build: String,
    project: String,
    components: List<ArchitectureComponent>,
): List<GraphPrioritySymbolRequestRenderer> =
    finding.componentIds.map { componentId ->
        val component = components.singleOrNull { it.id == componentId }
        GraphPrioritySymbolRequestRenderer(
            build = build,
            project = project,
            componentId = componentId,
            exactFilePath = finding.filePath,
            componentFilePath = component?.filePath,
            isTest = component?.isTest,
            severity = finding.severity.name,
            cycle = finding.componentCycle != null,
        )
    }

private fun graphCandidateCount(
    prioritySymbols: List<WorkspaceSymbol>,
    buildRepresentatives: List<WorkspaceSymbol>,
    importantByIdentity: Map<WorkspaceSymbolIdentity, ImportantSymbol>,
    relationships: List<WorkspaceRelationship>,
): Int {
    val anchorIdentities =
        (prioritySymbols + buildRepresentatives).map { symbol -> symbol.identity }.toSet() + importantByIdentity.keys
    val adjacentIdentities =
        relationships
            .filter { relationship ->
                relationship.sourceIdentity in anchorIdentities || relationship.targetIdentity in anchorIdentities
            }.flatMap { relationship ->
                listOf(requireNotNull(relationship.sourceIdentity), relationship.targetIdentity)
            }
    val crossBuildIdentities =
        relationships
            .filter { relationship ->
                requireNotNull(relationship.source).build != relationship.target.build
            }.flatMap { relationship ->
                listOf(requireNotNull(relationship.sourceIdentity), relationship.targetIdentity)
            }
    return (anchorIdentities + adjacentIdentities + crossBuildIdentities).size
}

private fun graphNode(
    symbol: WorkspaceSymbol,
    importantSymbol: ImportantSymbol?,
    usage: WorkspaceSymbolUsage,
): ArchitectureGraphNodeRenderer {
    val identity = symbol.identity
    return ArchitectureGraphNodeRenderer(
        id = identity.value,
        name = symbol.name,
        qualifiedName = symbol.qualifiedName,
        build = symbol.build,
        project = symbol.project,
        sourceSet = symbol.sourceSet,
        clusterId = clusterId(symbol.build, symbol.project),
        kind = symbol.kind.label,
        declarationCategory = graphDeclarationCategory(symbol),
        declarationSemantic = symbol.declarationSemantic.name,
        declarationSemanticLabel = symbol.declarationSemantic.label,
        declarationSemanticDetail = symbol.declarationSemantic.detail,
        file = symbol.projectRelativeFile,
        line = symbol.declarationLine,
        localInbound = usage.localInbound,
        workspaceInbound = usage.workspaceInbound,
        crossBuildInbound = usage.crossBuildInbound,
        outgoingRecordCount = usage.outgoing.size,
        importanceScore = importantSymbol?.score,
        importanceReasons =
            importantSymbol
                ?.reasons
                .orEmpty()
                .sortedBy { it.ordinal }
                .map { it.label },
    )
}

private fun graphNodes(
    symbols: List<WorkspaceSymbol>,
    importantByIdentity: Map<WorkspaceSymbolIdentity, ImportantSymbol>,
    usagesByIdentity: Map<WorkspaceSymbolIdentity, WorkspaceSymbolUsage>,
): List<ArchitectureGraphNodeRenderer> =
    symbols
        .map { symbol ->
            graphNode(
                symbol = symbol,
                importantSymbol = importantByIdentity[symbol.identity],
                usage = requireNotNull(usagesByIdentity[symbol.identity]),
            )
        }.sortedBy { node -> node.id }

private fun graphSymbolUsages(
    symbols: List<WorkspaceSymbol>,
    relationships: List<WorkspaceRelationship>,
): Map<WorkspaceSymbolIdentity, WorkspaceSymbolUsage> {
    val incomingByIdentity = relationships.groupBy { relationship -> relationship.targetIdentity }
    val outgoingByIdentity = relationships.groupBy { relationship -> requireNotNull(relationship.sourceIdentity) }
    return symbols.associate { symbol ->
        symbol.identity to
            WorkspaceSymbolUsage(
                symbol = symbol,
                incoming = incomingByIdentity[symbol.identity].orEmpty(),
                outgoing = outgoingByIdentity[symbol.identity].orEmpty(),
            )
    }
}

private fun graphDeclarationCategory(
    symbol: WorkspaceSymbol,
): String? =
    when (symbol.declarationSemantic) {
        zone.clanker.gradle.srcx.model.DeclarationSemantic.INTERFACE -> "INTERFACE"
        zone.clanker.gradle.srcx.model.DeclarationSemantic.ABSTRACT_CLASS -> "ABSTRACT"
        zone.clanker.gradle.srcx.model.DeclarationSemantic.CONCRETE_CLASS,
        zone.clanker.gradle.srcx.model.DeclarationSemantic.SINGLETON_OBJECT,
        -> "CONCRETE"
        zone.clanker.gradle.srcx.model.DeclarationSemantic.ENUM -> "ENUM"
        zone.clanker.gradle.srcx.model.DeclarationSemantic.OTHER -> null
    }

private fun graphClusters(nodes: List<ArchitectureGraphNodeRenderer>): List<ArchitectureGraphClusterRenderer> =
    nodes
        .groupBy { it.clusterId }
        .entries
        .sortedBy { it.key }
        .mapIndexed { index, (id, clusterNodes) ->
            val first = clusterNodes.first()
            ArchitectureGraphClusterRenderer(
                id = id,
                label = "${first.build} / ${first.project}",
                build = first.build,
                project = first.project,
                tone = CLUSTER_TONES[index % CLUSTER_TONES.size],
                nodeCount = clusterNodes.size,
            )
        }

private fun graphBuilds(
    report: WorkspaceReport,
    nodes: List<ArchitectureGraphNodeRenderer>,
    fileNodes: List<ArchitectureFileNodeRenderer>,
): List<ArchitectureBuildRenderer> {
    val includedByName = report.includedBuilds.associateBy { it.name }
    val declaredProjects =
        buildMap<String, List<ProjectSummary>> {
            put(report.name, report.rootProjects)
            report.includedBuilds.forEach { build ->
                put(build.name, build.projects)
            }
        }
    val representedBuilds = nodes.map { it.build } + fileNodes.map { it.build }
    val buildNames = (listOf(report.name) + report.includedBuilds.map { it.name } + representedBuilds).distinct()
    return buildNames
        .sortedWith(compareBy({ if (it == report.name) 0 else 1 }, { it }))
        .map { name ->
            val included = includedByName[name]
            ArchitectureBuildRenderer(
                name = name,
                context =
                    when {
                        name == report.name -> "Root build"
                        included != null -> "Included build"
                        else -> "Indexed build"
                    },
                relativePath = included?.relativePath,
                color = workspaceBuildColor(name = name),
                fileNodeCount = fileNodes.count { it.build == name },
                symbolNodeCount = nodes.count { it.build == name },
                indexedFileCount = report.sourceFiles.count { source -> source.build == name },
                indexedSymbolCount = report.workspaceIndex.symbols.count { symbol -> symbol.build == name },
                projects = graphProjects(report, name, declaredProjects[name].orEmpty(), nodes, fileNodes),
            )
        }
}

private fun graphProjects(
    report: WorkspaceReport,
    buildName: String,
    declaredProjects: List<ProjectSummary>,
    nodes: List<ArchitectureGraphNodeRenderer>,
    fileNodes: List<ArchitectureFileNodeRenderer>,
): List<ArchitectureProjectRenderer> {
    val projectNames =
        (
            declaredProjects.map { project -> project.projectPath.value } +
                nodes.filter { node -> node.build == buildName }.map { node -> node.project } +
                fileNodes.filter { node -> node.build == buildName }.map { node -> node.project }
        ).distinct().sorted()
    return projectNames.map { projectName ->
        val declaredSourceSets =
            declaredProjects
                .filter { project -> project.projectPath.value == projectName }
                .flatMap { project -> project.sourceSets }
                .map { sourceSet -> sourceSet.name.value }
        val indexedSourceSets =
            report.sourceFiles
                .filter { source -> source.build == buildName && source.project == projectName }
                .map { source -> source.sourceSet } +
                report.workspaceIndex.symbols
                    .filter { symbol -> symbol.build == buildName && symbol.project == projectName }
                    .map { symbol -> symbol.sourceSet }
        ArchitectureProjectRenderer(
            name = projectName,
            fileNodeCount = fileNodes.count { it.build == buildName && it.project == projectName },
            symbolNodeCount = nodes.count { it.build == buildName && it.project == projectName },
            indexedFileCount =
                report.sourceFiles.count { source ->
                    source.build == buildName && source.project == projectName
                },
            indexedSymbolCount =
                report.workspaceIndex.symbols.count { symbol ->
                    symbol.build == buildName && symbol.project == projectName
                },
            sourceSets = (declaredSourceSets + indexedSourceSets).distinct().sorted(),
        )
    }
}

internal fun workspaceBuildColor(name: String): String {
    val hue =
        floorMod(
            name.hashCode().toLong() * BUILD_COLOR_HUE_STEP,
            BUILD_COLOR_HUE_COUNT.toLong(),
        ).toInt()
    return "hsl($hue 58% 66%)"
}

private fun graphEdges(
    relationships: List<WorkspaceRelationship>,
    selectedIdentities: Set<WorkspaceSymbolIdentity>,
): List<ArchitectureGraphEdgeRenderer> =
    relationships
        .filter { relationship ->
            relationship.sourceIdentity in selectedIdentities && relationship.targetIdentity in selectedIdentities
        }.groupBy { relationship ->
            GraphEdgeKeyRenderer(
                source = requireNotNull(relationship.sourceIdentity).value,
                target = relationship.targetIdentity.value,
                kind = relationship.kind,
                evidence = relationship.evidence,
            )
        }.entries
        .sortedWith(compareBy({ it.key.source }, { it.key.target }, { it.key.kind.name }, { it.key.evidence.name }))
        .mapIndexed { index, (key, groupedRelationships) ->
            val source = requireNotNull(groupedRelationships.first().source)
            ArchitectureGraphEdgeRenderer(
                id = "edge-${index + 1}",
                source = key.source,
                target = key.target,
                kind = key.kind.name,
                kindLabel = key.kind.label,
                evidence = key.evidence.name,
                crossBuild = source.build != groupedRelationships.first().target.build,
                occurrences = groupedRelationships.sortedWith(RELATIONSHIP_COMPARATOR).map(::graphOccurrence),
            )
        }

private fun graphOccurrence(relationship: WorkspaceRelationship): ArchitectureGraphOccurrenceRenderer {
    val evidence = relationship.sourceEvidence
    return ArchitectureGraphOccurrenceRenderer(
        build = evidence.build,
        project = evidence.project,
        sourceSet = evidence.sourceSet,
        file = evidence.projectRelativeFile,
        line = evidence.line,
        context = evidence.context,
    )
}

internal data class WorkspaceArchitectureGraphRenderer(
    val builds: List<ArchitectureBuildRenderer>,
    val nodes: List<ArchitectureGraphNodeRenderer>,
    val edges: List<ArchitectureGraphEdgeRenderer>,
    val clusters: List<ArchitectureGraphClusterRenderer>,
    val fileNodes: List<ArchitectureFileNodeRenderer>,
    val fileEdges: List<ArchitectureFileEdgeRenderer>,
    val cycles: List<ArchitectureFileCycleRenderer>,
    val analysisCycles: List<ArchitectureAnalysisCycleRenderer>,
    val findings: List<ArchitectureFindingRenderer>,
    val sourceFiles: List<ArchitectureSourceFileRenderer>,
    val availableNodes: List<ArchitectureGraphNodeRenderer>,
    val availableEdges: List<ArchitectureGraphEdgeRenderer>,
    val availableFileNodes: List<ArchitectureFileNodeRenderer>,
    val availableFileEdges: List<ArchitectureFileEdgeRenderer>,
    val availableCycles: List<ArchitectureFileCycleRenderer>,
    val omittedNodeCount: Int,
    val omittedFileNodeCount: Int,
    val totalRelationshipRecordCount: Int,
) {
    val shownRelationshipRecordCount: Int
        get() = fileEdges.sumOf { it.recordCount } + fileNodes.sumOf { it.shownInternalRecordCount }

    val shownSymbolRelationshipRecordCount: Int
        get() = edges.sumOf { it.recordCount }

    fun toJson(): String =
        jsonObject(
            "defaultView" to "files".jsonString(),
            "nodeLimit" to ARCHITECTURE_GRAPH_NODE_LIMIT.toString(),
            "omittedNodeCount" to omittedNodeCount.toString(),
            "fileNodeLimit" to ARCHITECTURE_GRAPH_FILE_NODE_LIMIT.toString(),
            "omittedFileNodeCount" to omittedFileNodeCount.toString(),
            "totalRelationshipRecordCount" to totalRelationshipRecordCount.toString(),
            "shownRelationshipRecordCount" to shownRelationshipRecordCount.toString(),
            "shownSymbolRelationshipRecordCount" to shownSymbolRelationshipRecordCount.toString(),
            "builds" to builds.joinToString(prefix = "[", postfix = "]") { it.toJson() },
            "clusters" to clusters.joinToString(prefix = "[", postfix = "]") { it.toJson() },
            "nodes" to nodes.joinToString(prefix = "[", postfix = "]") { it.toJson() },
            "edges" to edges.joinToString(prefix = "[", postfix = "]") { it.toJson() },
            "fileNodes" to fileNodes.joinToString(prefix = "[", postfix = "]") { it.toJson() },
            "fileEdges" to fileEdges.joinToString(prefix = "[", postfix = "]") { it.toJson() },
            "cycles" to cycles.joinToString(prefix = "[", postfix = "]") { it.toJson() },
            "analysisCycles" to analysisCycles.joinToString(prefix = "[", postfix = "]") { it.toJson() },
            "findings" to findings.joinToString(prefix = "[", postfix = "]") { it.toJson() },
            "sourceFiles" to sourceFiles.joinToString(prefix = "[", postfix = "]") { it.toJson() },
            "availableNodes" to availableNodes.joinToString(prefix = "[", postfix = "]") { it.toJson() },
            "availableEdges" to availableEdges.joinToString(prefix = "[", postfix = "]") { it.toJson() },
            "availableFileNodes" to
                availableFileNodes.joinToString(prefix = "[", postfix = "]") { it.toJson() },
            "availableFileEdges" to
                availableFileEdges.joinToString(prefix = "[", postfix = "]") { it.toJson() },
            "availableCycles" to availableCycles.joinToString(prefix = "[", postfix = "]") { it.toJson() },
        )
}

internal data class ArchitectureBuildRenderer(
    val name: String,
    val context: String,
    val relativePath: String?,
    val color: String,
    val fileNodeCount: Int,
    val symbolNodeCount: Int,
    val indexedFileCount: Int,
    val indexedSymbolCount: Int,
    val projects: List<ArchitectureProjectRenderer>,
) {
    val projectCount: Int get() = projects.size
    val sourceSets: List<String> get() = projects.flatMap { it.sourceSets }.distinct().sorted()

    fun toJson(): String =
        jsonObject(
            "name" to name.jsonString(),
            "context" to context.jsonString(),
            "relativePath" to (relativePath?.jsonString() ?: "null"),
            "color" to color.jsonString(),
            "projectCount" to projectCount.toString(),
            "fileNodeCount" to fileNodeCount.toString(),
            "symbolNodeCount" to symbolNodeCount.toString(),
            "indexedFileCount" to indexedFileCount.toString(),
            "indexedSymbolCount" to indexedSymbolCount.toString(),
            "sourceSets" to sourceSets.joinToString(prefix = "[", postfix = "]") { it.jsonString() },
            "projects" to projects.joinToString(prefix = "[", postfix = "]") { it.toJson() },
        )
}

internal data class ArchitectureProjectRenderer(
    val name: String,
    val fileNodeCount: Int,
    val symbolNodeCount: Int,
    val indexedFileCount: Int,
    val indexedSymbolCount: Int,
    val sourceSets: List<String>,
) {
    fun toJson(): String =
        jsonObject(
            "name" to name.jsonString(),
            "fileNodeCount" to fileNodeCount.toString(),
            "symbolNodeCount" to symbolNodeCount.toString(),
            "indexedFileCount" to indexedFileCount.toString(),
            "indexedSymbolCount" to indexedSymbolCount.toString(),
            "sourceSets" to sourceSets.joinToString(prefix = "[", postfix = "]") { it.jsonString() },
        )
}

internal data class ArchitectureFileNodeRenderer(
    val id: String,
    val name: String,
    val path: String,
    val scope: String,
    val build: String,
    val project: String,
    val sourceSet: String,
    val selectedSymbolIds: List<String>,
    val symbols: List<ArchitectureFileSymbolRenderer>,
    val importance: Int?,
    val importanceReasons: List<String>,
    val importantSymbolCount: Int,
    val totalIncomingRecordCount: Int,
    val shownIncomingRecordCount: Int,
    val totalOutgoingRecordCount: Int,
    val shownOutgoingRecordCount: Int,
    val totalInternalRecordCount: Int,
    val shownInternalRecordCount: Int,
    val incomingSourceDeclarationCount: Int,
    val shownIncomingSourceDeclarationCount: Int,
    val incomingSourceFileCount: Int,
    val shownIncomingSourceFileCount: Int,
    val outgoingTargetDeclarationCount: Int,
    val shownOutgoingTargetDeclarationCount: Int,
    val outgoingTargetFileCount: Int,
    val shownOutgoingTargetFileCount: Int,
    val internalDeclarationCount: Int,
    val internalOccurrences: List<ArchitectureFileOccurrenceRenderer>,
    val findingIds: List<String>,
    val projectFindingIds: List<String>,
    val fileFindingCount: Int,
    val projectFindingCount: Int,
) {
    val important: Boolean get() = importance != null
    val relationshipRecordCount: Int
        get() = totalIncomingRecordCount + totalOutgoingRecordCount + totalInternalRecordCount

    val shownRelationshipRecordCount: Int
        get() = shownIncomingRecordCount + shownOutgoingRecordCount + shownInternalRecordCount

    fun toJson(): String =
        jsonObject(
            "id" to id.jsonString(),
            "name" to name.jsonString(),
            "path" to path.jsonString(),
            "scope" to scope.jsonString(),
            "build" to build.jsonString(),
            "project" to project.jsonString(),
            "sourceSet" to sourceSet.jsonString(),
            "selectedSymbolIds" to selectedSymbolIds.jsonArray(),
            "symbols" to symbols.joinToString(prefix = "[", postfix = "]") { it.toJson() },
            "important" to important.toString(),
            "importance" to (importance?.toString() ?: "null"),
            "importanceReasons" to importanceReasons.jsonArray(),
            "importantSymbolCount" to importantSymbolCount.toString(),
            "relationshipRecordCount" to relationshipRecordCount.toString(),
            "shownRelationshipRecordCount" to shownRelationshipRecordCount.toString(),
            "totalIncomingRecordCount" to totalIncomingRecordCount.toString(),
            "shownIncomingRecordCount" to shownIncomingRecordCount.toString(),
            "totalOutgoingRecordCount" to totalOutgoingRecordCount.toString(),
            "shownOutgoingRecordCount" to shownOutgoingRecordCount.toString(),
            "totalInternalRecordCount" to totalInternalRecordCount.toString(),
            "shownInternalRecordCount" to shownInternalRecordCount.toString(),
            "incomingSourceDeclarationCount" to incomingSourceDeclarationCount.toString(),
            "shownIncomingSourceDeclarationCount" to shownIncomingSourceDeclarationCount.toString(),
            "incomingSourceFileCount" to incomingSourceFileCount.toString(),
            "shownIncomingSourceFileCount" to shownIncomingSourceFileCount.toString(),
            "outgoingTargetDeclarationCount" to outgoingTargetDeclarationCount.toString(),
            "shownOutgoingTargetDeclarationCount" to shownOutgoingTargetDeclarationCount.toString(),
            "outgoingTargetFileCount" to outgoingTargetFileCount.toString(),
            "shownOutgoingTargetFileCount" to shownOutgoingTargetFileCount.toString(),
            "internalDeclarationCount" to internalDeclarationCount.toString(),
            "internalOccurrences" to
                internalOccurrences.joinToString(prefix = "[", postfix = "]") { it.toJson() },
            "findingIds" to findingIds.jsonArray(),
            "projectFindingIds" to projectFindingIds.jsonArray(),
            "fileFindingCount" to fileFindingCount.toString(),
            "projectFindingCount" to projectFindingCount.toString(),
        )
}

internal data class ArchitectureFileSymbolRenderer(
    val id: String,
    val name: String,
    val qualifiedName: String,
    val kind: String,
    val declarationSemantic: String,
    val declarationSemanticLabel: String,
    val declarationSemanticDetail: String,
    val line: Int,
    val importance: Int?,
) {
    val important: Boolean get() = importance != null

    fun toJson(): String =
        jsonObject(
            "id" to id.jsonString(),
            "name" to name.jsonString(),
            "qualifiedName" to qualifiedName.jsonString(),
            "kind" to kind.jsonString(),
            "declarationSemantic" to declarationSemantic.jsonString(),
            "declarationSemanticLabel" to declarationSemanticLabel.jsonString(),
            "declarationSemanticDetail" to declarationSemanticDetail.jsonString(),
            "line" to line.toString(),
            "important" to important.toString(),
            "importance" to (importance?.toString() ?: "null"),
        )
}

internal data class ArchitectureSourceFileRenderer(
    val id: String,
    val build: String,
    val project: String,
    val sourceSet: String,
    val path: String,
    val content: String,
    val declarationLines: List<Int>,
    val relationshipLines: List<Int>,
) {
    fun toJson(): String =
        jsonObject(
            "id" to id.jsonString(),
            "build" to build.jsonString(),
            "project" to project.jsonString(),
            "sourceSet" to sourceSet.jsonString(),
            "path" to path.jsonString(),
            "content" to content.jsonString(),
            "declarationLines" to declarationLines.jsonIntArray(),
            "relationshipLines" to relationshipLines.jsonIntArray(),
        )
}

internal data class ArchitectureFileEdgeRenderer(
    val id: String,
    val source: String,
    val target: String,
    val crossBuild: Boolean,
    val kindCounts: List<ArchitectureFileKindCountRenderer>,
    val evidenceCounts: List<ArchitectureFileEvidenceCountRenderer>,
    val occurrences: List<ArchitectureFileOccurrenceRenderer>,
) {
    val recordCount: Int get() = occurrences.size

    fun toJson(): String =
        jsonObject(
            "id" to id.jsonString(),
            "source" to source.jsonString(),
            "target" to target.jsonString(),
            "crossBuild" to crossBuild.toString(),
            "recordCount" to recordCount.toString(),
            "kindCounts" to kindCounts.joinToString(prefix = "[", postfix = "]") { it.toJson() },
            "evidenceCounts" to evidenceCounts.joinToString(prefix = "[", postfix = "]") { it.toJson() },
            "occurrences" to occurrences.joinToString(prefix = "[", postfix = "]") { it.toJson() },
        )
}

internal data class ArchitectureFileKindCountRenderer(
    val kind: String,
    val label: String,
    val count: Int,
) {
    fun toJson(): String =
        jsonObject(
            "kind" to kind.jsonString(),
            "label" to label.jsonString(),
            "count" to count.toString(),
        )
}

internal data class ArchitectureFileEvidenceCountRenderer(
    val evidence: String,
    val label: String,
    val count: Int,
) {
    fun toJson(): String =
        jsonObject(
            "evidence" to evidence.jsonString(),
            "label" to label.jsonString(),
            "count" to count.toString(),
        )
}

internal data class ArchitectureFileOccurrenceRenderer(
    val sourceSymbolId: String,
    val targetSymbolId: String,
    val kind: String,
    val kindLabel: String,
    val evidence: String,
    val build: String,
    val project: String,
    val sourceSet: String,
    val file: String,
    val line: Int,
    val context: String,
) {
    fun toJson(): String =
        jsonObject(
            "sourceSymbolId" to sourceSymbolId.jsonString(),
            "targetSymbolId" to targetSymbolId.jsonString(),
            "kind" to kind.jsonString(),
            "kindLabel" to kindLabel.jsonString(),
            "evidence" to evidence.jsonString(),
            "build" to build.jsonString(),
            "project" to project.jsonString(),
            "sourceSet" to sourceSet.jsonString(),
            "file" to file.jsonString(),
            "line" to line.toString(),
            "context" to context.jsonString(),
        )
}

internal data class ArchitectureFileCycleRenderer(
    val id: String,
    val memberIds: List<String>,
    val edgeIds: List<String>,
    val routes: List<ArchitectureFileCycleRouteRenderer>,
) {
    init {
        require(routes.isNotEmpty()) { "cycle must contain at least one directed route" }
        require(routes.flatMap { it.nodeIds }.containsAll(memberIds)) {
            "cycle routes must cover every cycle member"
        }
        require(routes.flatMap { it.edgeIds }.all { it in edgeIds }) {
            "cycle routes must use only cycle edges"
        }
        require(routes.flatMap { it.edgeIds }.toSet() == edgeIds.toSet()) {
            "cycle routes must cover every cycle edge"
        }
    }

    fun toJson(): String =
        jsonObject(
            "id" to id.jsonString(),
            "memberIds" to memberIds.jsonArray(),
            "edgeIds" to edgeIds.jsonArray(),
            "routes" to routes.joinToString(prefix = "[", postfix = "]") { it.toJson() },
        )
}

internal data class ArchitectureFileCycleRouteRenderer(
    val nodeIds: List<String>,
    val edgeIds: List<String>,
) {
    init {
        require(nodeIds.size == edgeIds.size + 1) { "cycle route nodes must align with edges" }
        require(nodeIds.firstOrNull() == nodeIds.lastOrNull()) { "cycle route must return to its first node" }
    }

    fun toJson(): String =
        jsonObject(
            "nodeIds" to nodeIds.jsonArray(),
            "edgeIds" to edgeIds.jsonArray(),
        )
}

internal data class ArchitectureAnalysisCycleRenderer(
    val id: String,
    val build: String,
    val project: String,
    val route: List<ArchitectureAnalysisCycleComponentRenderer>,
    val findingIds: List<String>,
) {
    init {
        require(route.size >= MINIMUM_CLOSED_ANALYSIS_CYCLE_SIZE) { "analysis cycle must contain a closed route" }
        require(route.first().componentId == route.last().componentId) { "analysis cycle route must be closed" }
    }

    val componentIds: List<String> get() = route.map { it.componentId }
    val memberSymbolIds: List<String> get() = route.dropLast(1).mapNotNull { it.symbolId }.distinct()
    val memberFileIds: List<String> get() = route.dropLast(1).mapNotNull { it.fileId }.distinct()

    fun toJson(): String =
        jsonObject(
            "id" to id.jsonString(),
            "build" to build.jsonString(),
            "project" to project.jsonString(),
            "componentIds" to componentIds.jsonArray(),
            "memberSymbolIds" to memberSymbolIds.jsonArray(),
            "memberFileIds" to memberFileIds.jsonArray(),
            "route" to route.joinToString(prefix = "[", postfix = "]") { it.toJson() },
            "findingIds" to findingIds.jsonArray(),
            "evidence" to "ANALYZER_INFERRED".jsonString(),
        )
}

internal data class ArchitectureAnalysisCycleComponentRenderer(
    val componentId: String,
    val name: String,
    val symbolId: String?,
    val fileId: String?,
    val filePath: String?,
    val sourceSet: String?,
    val line: Int?,
) {
    fun toJson(): String =
        jsonObject(
            "componentId" to componentId.jsonString(),
            "name" to name.jsonString(),
            "symbolId" to (symbolId?.jsonString() ?: "null"),
            "fileId" to (fileId?.jsonString() ?: "null"),
            "filePath" to (filePath?.jsonString() ?: "null"),
            "sourceSet" to (sourceSet?.jsonString() ?: "null"),
            "line" to (line?.toString() ?: "null"),
        )
}

internal data class ArchitectureFindingRenderer(
    val id: String,
    val build: String,
    val project: String,
    val filePath: String?,
    val line: Int?,
    val severity: String,
    val message: String,
    val suggestion: String,
    val componentIds: List<String>,
    val componentSymbolIds: List<String>,
    val componentFileIds: List<String>,
    val analysisCycleId: String?,
) {
    fun toJson(): String =
        jsonObject(
            "id" to id.jsonString(),
            "build" to build.jsonString(),
            "project" to project.jsonString(),
            "filePath" to (filePath?.jsonString() ?: "null"),
            "line" to (line?.toString() ?: "null"),
            "severity" to severity.jsonString(),
            "message" to message.jsonString(),
            "suggestion" to suggestion.jsonString(),
            "componentIds" to componentIds.jsonArray(),
            "componentSymbolIds" to componentSymbolIds.jsonArray(),
            "componentFileIds" to componentFileIds.jsonArray(),
            "analysisCycleId" to (analysisCycleId?.jsonString() ?: "null"),
        )
}

internal data class ArchitectureGraphNodeRenderer(
    val id: String,
    val name: String,
    val qualifiedName: String,
    val build: String,
    val project: String,
    val sourceSet: String,
    val clusterId: String,
    val kind: String,
    val declarationCategory: String?,
    val declarationSemantic: String,
    val declarationSemanticLabel: String,
    val declarationSemanticDetail: String,
    val file: String,
    val line: Int,
    val localInbound: Int,
    val workspaceInbound: Int,
    val crossBuildInbound: Int,
    val outgoingRecordCount: Int,
    val importanceScore: Int?,
    val importanceReasons: List<String>,
) {
    val isImportant: Boolean get() = importanceScore != null

    fun toJson(): String =
        jsonObject(
            "id" to id.jsonString(),
            "name" to name.jsonString(),
            "qualifiedName" to qualifiedName.jsonString(),
            "build" to build.jsonString(),
            "project" to project.jsonString(),
            "sourceSet" to sourceSet.jsonString(),
            "clusterId" to clusterId.jsonString(),
            "kind" to kind.jsonString(),
            "declarationCategory" to (declarationCategory?.jsonString() ?: "null"),
            "declarationSemantic" to declarationSemantic.jsonString(),
            "declarationSemanticLabel" to declarationSemanticLabel.jsonString(),
            "declarationSemanticDetail" to declarationSemanticDetail.jsonString(),
            "file" to file.jsonString(),
            "line" to line.toString(),
            "localInbound" to localInbound.toString(),
            "workspaceInbound" to workspaceInbound.toString(),
            "crossBuildInbound" to crossBuildInbound.toString(),
            "outgoingRecordCount" to outgoingRecordCount.toString(),
            "important" to isImportant.toString(),
            "importanceScore" to (importanceScore?.toString() ?: "null"),
            "importanceReasons" to importanceReasons.jsonArray(),
        )
}

internal data class ArchitectureGraphClusterRenderer(
    val id: String,
    val label: String,
    val build: String,
    val project: String,
    val tone: String,
    val nodeCount: Int,
) {
    fun toJson(): String =
        jsonObject(
            "id" to id.jsonString(),
            "label" to label.jsonString(),
            "build" to build.jsonString(),
            "project" to project.jsonString(),
            "tone" to tone.jsonString(),
            "nodeCount" to nodeCount.toString(),
        )
}

internal data class ArchitectureGraphEdgeRenderer(
    val id: String,
    val source: String,
    val target: String,
    val kind: String,
    val kindLabel: String,
    val evidence: String,
    val crossBuild: Boolean,
    val occurrences: List<ArchitectureGraphOccurrenceRenderer>,
) {
    val recordCount: Int get() = occurrences.size
    val label: String get() = if (recordCount == 1) kindLabel else "$kindLabel x$recordCount"

    fun toJson(): String =
        jsonObject(
            "id" to id.jsonString(),
            "source" to source.jsonString(),
            "target" to target.jsonString(),
            "kind" to kind.jsonString(),
            "kindLabel" to kindLabel.jsonString(),
            "label" to label.jsonString(),
            "evidence" to evidence.jsonString(),
            "crossBuild" to crossBuild.toString(),
            "recordCount" to recordCount.toString(),
            "occurrences" to occurrences.joinToString(prefix = "[", postfix = "]") { it.toJson() },
        )
}

internal data class ArchitectureGraphOccurrenceRenderer(
    val build: String,
    val project: String,
    val sourceSet: String,
    val file: String,
    val line: Int,
    val context: String,
) {
    fun toJson(): String =
        jsonObject(
            "build" to build.jsonString(),
            "project" to project.jsonString(),
            "sourceSet" to sourceSet.jsonString(),
            "file" to file.jsonString(),
            "line" to line.toString(),
            "context" to context.jsonString(),
        )
}

internal data class GraphSymbolSelectionRenderer(
    val prioritySymbols: List<WorkspaceSymbol>,
    val buildRepresentatives: List<WorkspaceSymbol>,
    val selectedSymbols: List<WorkspaceSymbol>,
)

private data class GraphRelationshipBundleRenderer(
    val key: GraphSymbolPairKeyRenderer,
    val symbols: List<WorkspaceSymbol>,
    val recordCount: Int,
    val crossBuild: Boolean,
    val importance: Int,
    val bestKindRank: Int,
) {
    fun withMissingSymbols(
        selectedIdentities: Set<WorkspaceSymbolIdentity>,
    ): GraphSelectableRelationshipBundleRenderer =
        GraphSelectableRelationshipBundleRenderer(
            key = key,
            missingSymbols = symbols.filterNot { symbol -> symbol.identity in selectedIdentities },
            recordCount = recordCount,
            crossBuild = crossBuild,
            importance = importance,
            bestKindRank = bestKindRank,
        )
}

private data class GraphSelectableRelationshipBundleRenderer(
    val key: GraphSymbolPairKeyRenderer,
    val missingSymbols: List<WorkspaceSymbol>,
    val recordCount: Int,
    val crossBuild: Boolean,
    val importance: Int,
    val bestKindRank: Int,
)

private data class GraphSymbolPairKeyRenderer(
    val first: String,
    val second: String,
)

private data class GraphPrioritySymbolRequestRenderer(
    val build: String,
    val project: String,
    val componentId: String,
    val exactFilePath: String?,
    val componentFilePath: String?,
    val isTest: Boolean?,
    val severity: String,
    val cycle: Boolean,
)

private data class GraphEdgeKeyRenderer(
    val source: String,
    val target: String,
    val kind: WorkspaceRelationshipKind,
    val evidence: ReferenceEvidence,
)

private fun clusterId(
    build: String,
    project: String,
): String = "$build::$project"

private fun SymbolDetailKind.graphRank(): Int =
    GRAPH_KIND_ORDER.indexOf(this)

private fun jsonObject(vararg fields: Pair<String, String>): String =
    fields.joinToString(prefix = "{", postfix = "}") { (name, value) -> "${name.jsonString()}:$value" }

private fun List<String>.jsonArray(): String = joinToString(prefix = "[", postfix = "]") { it.jsonString() }

private fun List<Int>.jsonIntArray(): String = joinToString(prefix = "[", postfix = "]")

private fun String.jsonString(): String =
    buildString(length + 2) {
        append('"')
        for (character in this@jsonString) {
            val escaped = JSON_CHARACTER_ESCAPES[character]
            when {
                escaped != null -> append(escaped)
                character < ' ' -> append("\\u%04x".format(character.code))
                else -> append(character)
            }
        }
        append('"')
    }

private val IMPORTANT_SYMBOL_COMPARATOR =
    compareByDescending<ImportantSymbol> { it.score }
        .thenBy { it.symbol.kind.graphRank() }
        .thenBy { it.symbol.identity.value }

private val CONNECTED_BUNDLE_COMPARATOR =
    compareBy<GraphSelectableRelationshipBundleRenderer> { it.missingSymbols.size }
        .thenByDescending { it.recordCount }
        .thenByDescending { it.crossBuild }
        .thenByDescending { it.importance }
        .thenBy { it.bestKindRank }
        .thenBy { it.key.first }
        .thenBy { it.key.second }

private val CONNECTED_SYMBOL_COMPARATOR =
    compareBy<WorkspaceSymbol> { it.kind.graphRank() }
        .thenBy { it.identity.value }

private val PRIORITY_SYMBOL_REQUEST_COMPARATOR =
    compareBy<GraphPrioritySymbolRequestRenderer> { request ->
        when (request.severity) {
            "FORBIDDEN" -> 0
            "WARNING" -> 1
            else -> 2
        }
    }.thenByDescending { it.cycle }
        .thenBy { it.build }
        .thenBy { it.project }
        .thenBy { it.componentId }
        .thenBy { it.exactFilePath.orEmpty() }
        .thenBy { it.componentFilePath.orEmpty() }
        .thenBy { it.isTest }

private val RELATIONSHIP_COMPARATOR =
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

private val CLUSTER_TONES = listOf("primary", "secondary", "accent", "tertiary", "error")

private val GRAPH_KIND_ORDER =
    listOf(
        SymbolDetailKind.INTERFACE,
        SymbolDetailKind.CLASS,
        SymbolDetailKind.DATA_CLASS,
        SymbolDetailKind.ENUM,
        SymbolDetailKind.OBJECT,
        SymbolDetailKind.FUNCTION,
        SymbolDetailKind.PROPERTY,
    )

private val JSON_CHARACTER_ESCAPES =
    mapOf(
        '"' to "\\\"",
        '\\' to "\\\\",
        '\b' to "\\b",
        '\u000c' to "\\f",
        '\n' to "\\n",
        '\r' to "\\r",
        '\t' to "\\t",
        '<' to "\\u003c",
        '>' to "\\u003e",
        '&' to "\\u0026",
        '{' to "\\u007b",
        '}' to "\\u007d",
        '\u2028' to "\\u2028",
        '\u2029' to "\\u2029",
    )

private const val BUILD_COLOR_HUE_COUNT = 360
private const val BUILD_COLOR_HUE_STEP = 137L
