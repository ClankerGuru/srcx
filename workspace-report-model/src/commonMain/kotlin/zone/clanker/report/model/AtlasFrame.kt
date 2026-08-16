@file:Suppress("LongParameterList")

package zone.clanker.report.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A renderer-ready graph envelope shared by the static generator and the Wasm application. */
@Serializable
data class AtlasFrame(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val frameId: String,
    val scope: AtlasScope,
    val lens: AtlasLens,
    val builds: List<AtlasBuild>,
    val nodes: List<AtlasNode>,
    val edges: List<AtlasEdge>,
    val totalNodeCount: Int,
    val matchingNodeCount: Int,
    val pageIndex: Int,
    val pageCount: Int,
    val totalRelationshipRecordCount: Int,
    val shownRelationshipRecordCount: Int,
    val selectedNodeIds: List<String> = emptyList(),
    val selectedEdgeId: String? = null,
) {
    init {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) { "Unsupported Atlas frame schema: $schemaVersion" }
        requireValidId(frameId, "Atlas frame")
        requireUniqueAndSorted(builds.map(AtlasBuild::id), "Atlas builds")
        requireUniqueAndSorted(nodes.map(AtlasNode::id), "Atlas nodes")
        requireUniqueAndSorted(edges.map(AtlasEdge::id), "Atlas edges")
        val nodeIds = nodes.mapTo(mutableSetOf(), AtlasNode::id)
        val buildIds = builds.mapTo(mutableSetOf(), AtlasBuild::id)
        require(nodes.all { node -> node.buildId == null || node.buildId in buildIds }) {
            "Atlas nodes must reference serialized builds"
        }
        require(edges.all { edge -> edge.sourceId in nodeIds && edge.targetId in nodeIds }) {
            "Atlas edges must have complete endpoint closure"
        }
        require(edges.flatMap(AtlasEdge::relationshipIds).distinct().size == edges.sumOf { it.relationshipIds.size }) {
            "Underlying Atlas relationship IDs must belong to one edge"
        }
        require(totalNodeCount >= 0 && matchingNodeCount in 0..totalNodeCount) {
            "Atlas node totals must be non-negative and internally consistent"
        }
        require(pageIndex >= 0 && pageCount >= 0 && (pageCount == 0 || pageIndex < pageCount)) {
            "Atlas page position must be valid"
        }
        require(totalRelationshipRecordCount >= shownRelationshipRecordCount) {
            "Shown Atlas relationship records cannot exceed the total"
        }
        require(
            shownRelationshipRecordCount ==
                edges.sumOf(AtlasEdge::recordCount) + nodes.sumOf(AtlasNode::internalRecordCount),
        ) {
            "Shown Atlas relationship records must equal edge and node-internal aggregates"
        }
        requireDistinctAndSorted(selectedNodeIds, "Selected Atlas nodes")
        require(selectedNodeIds.all(nodeIds::contains)) { "Selected Atlas nodes must be visible" }
        require(
            selectedEdgeId == null ||
                edges.any { edge -> selectedEdgeId == edge.id || selectedEdgeId in edge.relationshipIds },
        ) { "The selected Atlas edge must be visible" }
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1

        /** Deliberate workspace-overview projection bound; selected project/source frames are complete. */
        const val MAX_VISIBLE_NODES: Int = 42
    }
}

@Serializable
data class AtlasScope(
    val kind: AtlasScopeKind,
    val workspaceId: String,
    val workspaceName: String,
    val buildId: String? = null,
    val projectId: String? = null,
) {
    init {
        requireValidId(workspaceId, "Atlas workspace")
        require(workspaceName.isNotBlank()) { "Atlas workspace name must not be blank" }
        buildId?.let { requireValidId(it, "Atlas scope build") }
        projectId?.let { requireValidId(it, "Atlas scope project") }
        require(
            when (kind) {
                AtlasScopeKind.WORKSPACE -> buildId == null && projectId == null
                AtlasScopeKind.BUILD -> buildId != null && projectId == null
                AtlasScopeKind.PROJECT -> buildId != null && projectId != null
            },
        ) { "Atlas scope IDs must agree with its kind" }
    }
}

@Serializable
enum class AtlasScopeKind {
    @SerialName("workspace")
    WORKSPACE,

    @SerialName("build")
    BUILD,

    @SerialName("project")
    PROJECT,
}

@Serializable
enum class AtlasLens {
    @SerialName("files")
    FILES,

    @SerialName("symbols")
    SYMBOLS,

    @SerialName("problems")
    PROBLEMS,

    @SerialName("cycles")
    CYCLES,
}

@Serializable
data class AtlasBuild(
    val id: String,
    val name: String,
    val context: String,
    val color: String,
) {
    init {
        requireValidId(id, "Atlas build")
        require(name.isNotBlank()) { "Atlas build name must not be blank" }
        require(context.isNotBlank()) { "Atlas build context must not be blank" }
        require(color.isNotBlank()) { "Atlas build color must not be blank" }
    }
}

@Serializable
data class AtlasNode(
    val id: String,
    val type: AtlasNodeType,
    val name: String,
    val qualifiedName: String? = null,
    val path: String? = null,
    val buildId: String? = null,
    val buildName: String? = null,
    val projectId: String? = null,
    val projectPath: String? = null,
    val sourceSet: String? = null,
    val sourceFileId: String? = null,
    val language: String? = null,
    val kind: String? = null,
    val semantic: DeclarationSemantic? = null,
    val primary: Boolean = true,
    val important: Boolean = false,
    val importanceScore: Int? = null,
    val findingIds: List<String> = emptyList(),
    val findingCount: Int = findingIds.size,
    val sourceFileFindingCount: Int = findingCount,
    val hasObservedCycle: Boolean = false,
    val hasAnalysisCycle: Boolean = false,
    val hasAnalyzerFinding: Boolean = findingCount > 0 || sourceFileFindingCount > 0,
    val relationshipRecordCount: Int = 0,
    val internalRecordCount: Int = 0,
) {
    init {
        requireValidId(id, "Atlas node")
        require(name.isNotBlank()) { "Atlas node name must not be blank" }
        requireDistinctAndSorted(findingIds, "Atlas node finding IDs")
        require(findingCount == findingIds.size) { "Atlas finding count must match its record IDs" }
        require(sourceFileFindingCount >= findingCount) { "Atlas source-file findings cannot omit direct findings" }
        require(importanceScore == null || importanceScore >= 0) { "Atlas importance must not be negative" }
        require(relationshipRecordCount >= 0 && internalRecordCount in 0..relationshipRecordCount) {
            "Atlas relationship counts must be non-negative and internally consistent"
        }
        require(hasAnalyzerFinding == (findingCount > 0 || sourceFileFindingCount > 0)) {
            "Atlas analyzer-finding state must agree with its counts"
        }
        requireNodeIdentity()
    }

    private fun requireNodeIdentity() {
        require(
            when (type) {
                AtlasNodeType.BUILD -> hasBuildNodeIdentity()
                AtlasNodeType.PROJECT -> hasProjectNodeIdentity()
                AtlasNodeType.FILE -> hasFileNodeIdentity()
                AtlasNodeType.SYMBOL -> hasSymbolNodeIdentity()
            },
        ) { "Atlas node ownership and source identity must agree with its type" }
    }

    private fun hasBuildNodeIdentity(): Boolean =
        hasBuildIdentity() && !hasProjectIdentity() && sourceFileId == null

    private fun hasProjectNodeIdentity(): Boolean =
        hasBuildIdentity() && hasProjectIdentity() && sourceFileId == null

    private fun hasFileNodeIdentity(): Boolean =
        hasBuildIdentity() && hasProjectIdentity() && sourceFileId == id && path != null

    private fun hasSymbolNodeIdentity(): Boolean =
        hasBuildIdentity() && hasProjectIdentity() && sourceFileId != null && path != null && semantic != null

    private fun hasBuildIdentity(): Boolean = buildId != null && buildName != null

    private fun hasProjectIdentity(): Boolean = projectId != null && projectPath != null
}

@Serializable
enum class AtlasNodeType {
    @SerialName("build")
    BUILD,

    @SerialName("project")
    PROJECT,

    @SerialName("file")
    FILE,

    @SerialName("symbol")
    SYMBOL,
}

@Serializable
data class AtlasEdge(
    val id: String,
    val sourceId: String,
    val targetId: String,
    val category: AtlasEdgeCategory,
    val relationshipIds: List<String> = emptyList(),
    val referenceIds: List<String> = emptyList(),
    val recordCount: Int,
    val kindCounts: List<AtlasCount>,
    val evidenceCounts: List<AtlasCount>,
    val crossBuild: Boolean = false,
    val hasHeuristic: Boolean = false,
    val isObservedCycleEdge: Boolean = false,
    val isAnalysisCycleEdge: Boolean = false,
) {
    init {
        requireValidId(id, "Atlas edge")
        requireValidId(sourceId, "Atlas edge source")
        requireValidId(targetId, "Atlas edge target")
        requireDistinctAndSorted(relationshipIds, "Atlas relationship IDs")
        requireDistinctAndSorted(referenceIds, "Atlas reference IDs")
        require(relationshipIds.isEmpty() || id == relationshipIds.first()) {
            "An Atlas edge ID must be its first underlying relationship ID"
        }
        require(recordCount > 0) { "Atlas edge record count must be positive" }
        requireCounts(kindCounts, "kind")
        requireCounts(evidenceCounts, "evidence")
        require(kindCounts.sumOf(AtlasCount::count) == recordCount) { "Atlas kind counts must cover every record" }
        require(evidenceCounts.sumOf(AtlasCount::count) == recordCount) {
            "Atlas evidence counts must cover every record"
        }
        require(hasHeuristic == evidenceCounts.any { it.key == "HEURISTIC" && it.count > 0 }) {
            "Atlas heuristic state must agree with its evidence counts"
        }
    }

    private fun requireCounts(
        counts: List<AtlasCount>,
        label: String,
    ) {
        require(counts.map(AtlasCount::key) == counts.map(AtlasCount::key).distinct().sorted()) {
            "Atlas $label counts must use distinct deterministic keys"
        }
    }
}

@Serializable
enum class AtlasEdgeCategory {
    @SerialName("structural")
    STRUCTURAL,

    @SerialName("imports")
    IMPORTS,

    @SerialName("inheritance")
    INHERITANCE,

    @SerialName("calls")
    CALLS,

    @SerialName("references")
    REFERENCES,

    @SerialName("mixed")
    MIXED,
}

@Serializable
data class AtlasCount(
    val key: String,
    val label: String,
    val count: Int,
) {
    init {
        require(key.isNotBlank()) { "Atlas count key must not be blank" }
        require(label.isNotBlank()) { "Atlas count label must not be blank" }
        require(count > 0) { "Atlas count must be positive" }
    }
}
