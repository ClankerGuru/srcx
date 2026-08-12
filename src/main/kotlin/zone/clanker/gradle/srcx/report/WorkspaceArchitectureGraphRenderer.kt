@file:Suppress("TooManyFunctions")

package zone.clanker.gradle.srcx.report

import zone.clanker.gradle.srcx.model.ImportantSymbol
import zone.clanker.gradle.srcx.model.ReferenceEvidence
import zone.clanker.gradle.srcx.model.SymbolDetailKind
import zone.clanker.gradle.srcx.model.WorkspaceRelationship
import zone.clanker.gradle.srcx.model.WorkspaceRelationshipKind
import zone.clanker.gradle.srcx.model.WorkspaceReport
import zone.clanker.gradle.srcx.model.WorkspaceSymbol
import zone.clanker.gradle.srcx.model.WorkspaceSymbolIdentity
import zone.clanker.gradle.srcx.model.WorkspaceSymbolUsage

/** Maximum number of source symbols emitted into the interactive architecture graph. */
internal const val ARCHITECTURE_GRAPH_NODE_LIMIT = 42
internal const val ARCHITECTURE_GRAPH_FILE_NODE_LIMIT = ARCHITECTURE_GRAPH_NODE_LIMIT
private const val ARCHITECTURE_GRAPH_IMPORTANT_SEED_LIMIT = 21

internal fun buildWorkspaceArchitectureGraph(report: WorkspaceReport): WorkspaceArchitectureGraphRenderer {
    require(report.importantSymbols.distinctBy { it.symbol.identity }.size == report.importantSymbols.size) {
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
    val selectedSymbols = selectGraphSymbols(importantByIdentity, resolvedRelationships)
    val selectedIdentities = selectedSymbols.mapTo(mutableSetOf()) { it.identity }
    val nodes =
        selectedSymbols
            .map { symbol -> graphNode(symbol, importantByIdentity[symbol.identity], relationships) }
            .sortedBy { it.id }
    val clusters = graphClusters(nodes)
    val edges = graphEdges(resolvedRelationships, selectedIdentities)
    val candidateCount = graphCandidateCount(importantByIdentity, resolvedRelationships)
    val fileGraph =
        buildWorkspaceArchitectureFileGraph(
            report,
            selectedSymbols,
            resolvedRelationships,
            importantByIdentity,
        )
    return WorkspaceArchitectureGraphRenderer(
        nodes = nodes,
        edges = edges,
        clusters = clusters,
        fileNodes = fileGraph.nodes,
        fileEdges = fileGraph.edges,
        cycles = fileGraph.cycles,
        findings = fileGraph.findings,
        omittedNodeCount = (candidateCount - nodes.size).coerceAtLeast(0),
        omittedFileNodeCount = fileGraph.omittedNodeCount,
    )
}

private fun selectGraphSymbols(
    importantByIdentity: Map<WorkspaceSymbolIdentity, ImportantSymbol>,
    relationships: List<WorkspaceRelationship>,
): List<WorkspaceSymbol> {
    val selected = linkedMapOf<WorkspaceSymbolIdentity, WorkspaceSymbol>()
    val rankedImportant = importantByIdentity.values.sortedWith(IMPORTANT_SYMBOL_COMPARATOR)
    rankedImportant
        .take(ARCHITECTURE_GRAPH_IMPORTANT_SEED_LIMIT)
        .forEach { important -> selected[important.symbol.identity] = important.symbol }
    if (importantByIdentity.isEmpty()) return selected.values.toList()

    neighborCandidates(importantByIdentity, relationships)
        .sortedWith(NEIGHBOR_COMPARATOR)
        .forEach { candidate ->
            if (selected.size < ARCHITECTURE_GRAPH_NODE_LIMIT) {
                selected.putIfAbsent(candidate.symbol.identity, candidate.symbol)
            }
        }
    rankedImportant
        .drop(ARCHITECTURE_GRAPH_IMPORTANT_SEED_LIMIT)
        .forEach { important ->
            if (selected.size < ARCHITECTURE_GRAPH_NODE_LIMIT) {
                selected.putIfAbsent(important.symbol.identity, important.symbol)
            }
        }
    return selected.values.toList()
}

private fun neighborCandidates(
    importantByIdentity: Map<WorkspaceSymbolIdentity, ImportantSymbol>,
    relationships: List<WorkspaceRelationship>,
): List<GraphNeighborCandidateRenderer> {
    val importantIdentities = importantByIdentity.keys
    return relationships
        .flatMap { relationship ->
            val source = requireNotNull(relationship.source)
            buildList {
                if (source.identity in importantIdentities) {
                    add(candidate(relationship.target, relationship, importantByIdentity))
                }
                if (relationship.target.identity in importantIdentities) {
                    add(candidate(source, relationship, importantByIdentity))
                }
            }
        }.groupBy { it.symbol.identity }
        .map { (_, candidates) ->
            candidates
                .sortedWith(NEIGHBOR_COMPARATOR)
                .first()
                .copy(
                    crossBuild = candidates.any { it.crossBuild },
                    adjacentImportance = candidates.maxOf { it.adjacentImportance },
                )
        }
}

private fun candidate(
    symbol: WorkspaceSymbol,
    relationship: WorkspaceRelationship,
    importantByIdentity: Map<WorkspaceSymbolIdentity, ImportantSymbol>,
): GraphNeighborCandidateRenderer {
    val source = requireNotNull(relationship.source)
    val adjacentIdentity = if (source.identity == symbol.identity) relationship.target.identity else source.identity
    return GraphNeighborCandidateRenderer(
        symbol = symbol,
        crossBuild = source.build != relationship.target.build,
        adjacentImportance = importantByIdentity[adjacentIdentity]?.score ?: 0,
    )
}

private fun graphCandidateCount(
    importantByIdentity: Map<WorkspaceSymbolIdentity, ImportantSymbol>,
    relationships: List<WorkspaceRelationship>,
): Int =
    (importantByIdentity.keys + neighborCandidates(importantByIdentity, relationships).map { it.symbol.identity })
        .distinct()
        .size

private fun graphNode(
    symbol: WorkspaceSymbol,
    importantSymbol: ImportantSymbol?,
    relationships: List<WorkspaceRelationship>,
): ArchitectureGraphNodeRenderer {
    val identity = symbol.identity
    val usage =
        WorkspaceSymbolUsage(
            symbol = symbol,
            incoming = relationships.filter { it.targetIdentity == identity },
            outgoing = relationships.filter { it.sourceIdentity == identity },
        )
    return ArchitectureGraphNodeRenderer(
        id = identity.value,
        name = symbol.name,
        qualifiedName = symbol.qualifiedName,
        build = symbol.build,
        project = symbol.project,
        sourceSet = symbol.sourceSet,
        clusterId = clusterId(symbol.build, symbol.project),
        kind = symbol.kind.label,
        file = symbol.projectRelativeFile,
        line = symbol.declarationLine,
        localInbound = usage.localInbound,
        workspaceInbound = usage.workspaceInbound,
        crossBuildInbound = usage.crossBuildInbound,
        outgoingCount = usage.outgoing.size,
        importanceScore = importantSymbol?.score,
        importanceReasons =
            importantSymbol
                ?.reasons
                .orEmpty()
                .sortedBy { it.ordinal }
                .map { it.label },
    )
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
    val nodes: List<ArchitectureGraphNodeRenderer>,
    val edges: List<ArchitectureGraphEdgeRenderer>,
    val clusters: List<ArchitectureGraphClusterRenderer>,
    val fileNodes: List<ArchitectureFileNodeRenderer>,
    val fileEdges: List<ArchitectureFileEdgeRenderer>,
    val cycles: List<ArchitectureFileCycleRenderer>,
    val findings: List<ArchitectureFindingRenderer>,
    val omittedNodeCount: Int,
    val omittedFileNodeCount: Int,
) {
    fun toJson(): String =
        jsonObject(
            "defaultView" to "files".jsonString(),
            "nodeLimit" to ARCHITECTURE_GRAPH_NODE_LIMIT.toString(),
            "omittedNodeCount" to omittedNodeCount.toString(),
            "fileNodeLimit" to ARCHITECTURE_GRAPH_FILE_NODE_LIMIT.toString(),
            "omittedFileNodeCount" to omittedFileNodeCount.toString(),
            "clusters" to clusters.joinToString(prefix = "[", postfix = "]") { it.toJson() },
            "nodes" to nodes.joinToString(prefix = "[", postfix = "]") { it.toJson() },
            "edges" to edges.joinToString(prefix = "[", postfix = "]") { it.toJson() },
            "fileNodes" to fileNodes.joinToString(prefix = "[", postfix = "]") { it.toJson() },
            "fileEdges" to fileEdges.joinToString(prefix = "[", postfix = "]") { it.toJson() },
            "cycles" to cycles.joinToString(prefix = "[", postfix = "]") { it.toJson() },
            "findings" to findings.joinToString(prefix = "[", postfix = "]") { it.toJson() },
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
    val incomingCount: Int,
    val outgoingCount: Int,
    val internalCount: Int,
    val findingIds: List<String>,
    val projectFindingIds: List<String>,
    val fileFindingCount: Int,
    val projectFindingCount: Int,
) {
    val important: Boolean get() = importance != null
    val relationshipCount: Int get() = incomingCount + outgoingCount + internalCount

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
            "relationshipCount" to relationshipCount.toString(),
            "incomingCount" to incomingCount.toString(),
            "outgoingCount" to outgoingCount.toString(),
            "internalCount" to internalCount.toString(),
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
            "line" to line.toString(),
            "important" to important.toString(),
            "importance" to (importance?.toString() ?: "null"),
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
    val count: Int get() = occurrences.size

    fun toJson(): String =
        jsonObject(
            "id" to id.jsonString(),
            "source" to source.jsonString(),
            "target" to target.jsonString(),
            "crossBuild" to crossBuild.toString(),
            "count" to count.toString(),
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
) {
    fun toJson(): String =
        jsonObject(
            "id" to id.jsonString(),
            "memberIds" to memberIds.jsonArray(),
            "edgeIds" to edgeIds.jsonArray(),
        )
}

internal data class ArchitectureFindingRenderer(
    val id: String,
    val build: String,
    val project: String,
    val filePath: String?,
    val severity: String,
    val message: String,
    val suggestion: String,
) {
    fun toJson(): String =
        jsonObject(
            "id" to id.jsonString(),
            "build" to build.jsonString(),
            "project" to project.jsonString(),
            "filePath" to (filePath?.jsonString() ?: "null"),
            "severity" to severity.jsonString(),
            "message" to message.jsonString(),
            "suggestion" to suggestion.jsonString(),
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
    val file: String,
    val line: Int,
    val localInbound: Int,
    val workspaceInbound: Int,
    val crossBuildInbound: Int,
    val outgoingCount: Int,
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
            "file" to file.jsonString(),
            "line" to line.toString(),
            "localInbound" to localInbound.toString(),
            "workspaceInbound" to workspaceInbound.toString(),
            "crossBuildInbound" to crossBuildInbound.toString(),
            "outgoingCount" to outgoingCount.toString(),
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
    val count: Int get() = occurrences.size
    val label: String get() = if (count == 1) kindLabel else "$kindLabel x$count"

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
            "count" to count.toString(),
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

private data class GraphNeighborCandidateRenderer(
    val symbol: WorkspaceSymbol,
    val crossBuild: Boolean,
    val adjacentImportance: Int,
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

private val NEIGHBOR_COMPARATOR =
    compareByDescending<GraphNeighborCandidateRenderer> { it.crossBuild }
        .thenBy { it.symbol.kind.graphRank() }
        .thenByDescending { it.adjacentImportance }
        .thenBy { it.symbol.identity.value }

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
