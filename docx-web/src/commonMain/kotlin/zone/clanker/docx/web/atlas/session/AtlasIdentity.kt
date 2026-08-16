package zone.clanker.docx.web.atlas.session

import kotlin.jvm.JvmInline

@JvmInline
internal value class AtlasSemanticId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "Atlas semantic ID must not be blank" }
        require(!value.trim().equals(ALL_SENTINEL, ignoreCase = true)) {
            "Atlas uses an empty selection for All, never a synthetic all ID"
        }
    }
}

internal data class AtlasGeneration(
    val workspaceId: AtlasSemanticId,
    val generationId: String,
) {
    init {
        require(generationId.isNotBlank()) { "Atlas generation ID must not be blank" }
    }
}

internal enum class AtlasHierarchyLevel {
    APPLICATION_GROUP,
    BUILD,
    PROJECT,
    SOURCE_SET,
    PACKAGE,
    FILE,
    TYPE,
    MEMBER,
}

internal data class AtlasHierarchyNode(
    val id: AtlasSemanticId,
    val level: AtlasHierarchyLevel,
    val parentId: AtlasSemanticId? = null,
)

internal data class AtlasHierarchyIndex(
    val nodes: Map<AtlasSemanticId, AtlasHierarchyNode> = emptyMap(),
) {
    init {
        nodes.forEach { (id, node) ->
            require(id == node.id) { "Atlas hierarchy keys must match their node IDs" }
            requireRootOrKnownParent(node)
        }
    }

    fun containsAt(
        id: AtlasSemanticId,
        level: AtlasHierarchyLevel,
    ): Boolean = nodes[id]?.level == level

    fun contains(id: AtlasSemanticId): Boolean = id in nodes

    fun isDescendantOf(
        id: AtlasSemanticId,
        ancestorId: AtlasSemanticId,
    ): Boolean = ancestorsOf(id).any { ancestor -> ancestor == ancestorId }

    fun pathTo(id: AtlasSemanticId): List<AtlasSemanticId> {
        if (id !in nodes) return emptyList()
        return generateSequence(id) { current -> nodes.getValue(current).parentId }
            .toList()
            .asReversed()
    }

    fun containsPath(path: List<AtlasSemanticId>): Boolean =
        path.isEmpty() || pathTo(path.last()) == path

    fun extending(additions: Iterable<AtlasHierarchyNode>): AtlasHierarchyIndex {
        val merged = nodes.toMutableMap()
        additions.forEach { addition ->
            val previous = merged[addition.id]
            require(previous == null || previous == addition) {
                "Atlas hierarchy extensions cannot redefine existing nodes"
            }
            merged[addition.id] = addition
        }
        return of(merged.values)
    }

    private fun ancestorsOf(id: AtlasSemanticId): Sequence<AtlasSemanticId> =
        generateSequence(nodes[id]?.parentId) { current -> nodes.getValue(current).parentId }

    private fun requireRootOrKnownParent(node: AtlasHierarchyNode) {
        val parentId = node.parentId
        if (parentId == null) {
            require(node.level == AtlasHierarchyLevel.APPLICATION_GROUP || node.level == AtlasHierarchyLevel.BUILD) {
                "Only application groups and builds may be Atlas hierarchy roots"
            }
            return
        }
        val parent = requireNotNull(nodes[parentId]) { "Atlas hierarchy parents must be present in the index" }
        val nestedPackage = parent.level == AtlasHierarchyLevel.PACKAGE && node.level == AtlasHierarchyLevel.PACKAGE
        require(parent.level.ordinal < node.level.ordinal || nestedPackage) {
            "Atlas hierarchy parents must be shallower than their children"
        }
    }

    companion object {
        fun of(nodes: Iterable<AtlasHierarchyNode>): AtlasHierarchyIndex {
            val entries = nodes.toList()
            require(entries.map(AtlasHierarchyNode::id).distinct().size == entries.size) {
                "Atlas hierarchy node IDs must be unique"
            }
            return AtlasHierarchyIndex(entries.associateBy(AtlasHierarchyNode::id))
        }
    }
}

private const val ALL_SENTINEL: String = "all"
