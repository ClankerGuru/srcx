package zone.clanker.docx.web.atlas.session

import zone.clanker.report.model.WorkspaceGraphFilter
import zone.clanker.report.model.WorkspaceGraphNodeKind
import zone.clanker.report.model.WorkspaceGraphRelationKind

internal data class AtlasLayers(
    val enabled: List<AtlasLayer> = DEFAULT_LAYERS,
) {
    init {
        require(enabled == enabled.distinct().sortedBy { layer -> layer.ordinal }) {
            "Atlas layers must be distinct and ordered"
        }
    }

    companion object {
        val DEFAULT_LAYERS: List<AtlasLayer> =
            listOf(
                AtlasLayer.CONTAINMENT,
                AtlasLayer.FILES,
                AtlasLayer.RELATIONSHIPS,
                AtlasLayer.LABELS,
            ).sortedBy { layer -> layer.ordinal }
    }
}

internal enum class AtlasLayer {
    CONTAINMENT,
    FILES,
    SYMBOLS,
    PROBLEMS,
    CYCLES,
    TASKS,
    DEPENDENCIES,
    VARIANTS,
    UPGRADES,
    RELATIONSHIPS,
    LABELS,
}

internal data class AtlasFilters(
    val projectQuery: String = "",
    val search: AtlasSearchFilter = AtlasSearchFilter(),
    val declarations: List<AtlasDeclarationKind> = emptyList(),
    val nodeKinds: List<WorkspaceGraphNodeKind> = emptyList(),
    val relationships: AtlasRelationshipFilter = AtlasRelationshipFilter(),
) {
    init {
        require(projectQuery.length <= WorkspaceGraphFilter.MAX_QUERY_LENGTH) {
            "Atlas project query must not exceed ${WorkspaceGraphFilter.MAX_QUERY_LENGTH} characters"
        }
        require(declarations == declarations.distinct().sortedBy { declaration -> declaration.ordinal }) {
            "Atlas declaration filters must be distinct and ordered"
        }
        require(nodeKinds == nodeKinds.distinct().sortedBy { kind -> kind.name }) {
            "Atlas node-kind filters must be distinct and ordered"
        }
    }
}

internal data class AtlasSearchFilter(
    val query: String = "",
    val targets: List<AtlasSearchTarget> = emptyList(),
) {
    init {
        require(query.length <= WorkspaceGraphFilter.MAX_QUERY_LENGTH) {
            "Atlas search query must not exceed ${WorkspaceGraphFilter.MAX_QUERY_LENGTH} characters"
        }
        require(targets == targets.distinct().sortedBy { target -> target.ordinal }) {
            "Atlas search targets must be distinct and ordered"
        }
    }
}

internal enum class AtlasSearchTarget {
    APPLICATION_GROUP,
    BUILD,
    PROJECT,
    SOURCE_SET,
    PACKAGE,
    FILE,
    FILE_EXTENSION,
    CLASS,
    INTERFACE,
    OBJECT,
    ENUM,
    TYPE,
    METHOD,
    FUNCTION,
    PROPERTY,
    SYMBOL,
    TASK,
    DEPENDENCY,
    PROBLEM,
    CYCLE,
}

internal enum class AtlasDeclarationKind {
    CLASS,
    INTERFACE,
    OBJECT,
    ENUM,
    FUNCTION,
    METHOD,
    PROPERTY,
}

internal data class AtlasRelationshipFilter(
    val kinds: List<WorkspaceGraphRelationKind> = emptyList(),
    val direction: AtlasRelationshipDirection = AtlasRelationshipDirection.ANY,
    val minimumCountExclusive: Long = 0,
    val keepInverseContext: Boolean = true,
) {
    init {
        require(kinds == kinds.distinct().sortedBy { kind -> kind.name }) {
            "Atlas relationship-kind filters must be distinct and ordered"
        }
        require(minimumCountExclusive >= 0) { "Atlas relationship threshold must not be negative" }
    }
}

internal enum class AtlasRelationshipDirection {
    ANY,
    INCOMING,
    OUTGOING,
}

internal sealed interface AtlasOverlay {
    data object Closed : AtlasOverlay

    data object Search : AtlasOverlay

    data class Scope(
        val level: AtlasHierarchyLevel,
    ) : AtlasOverlay

    data object Layers : AtlasOverlay

    data object RelationshipSettings : AtlasOverlay
}

internal sealed interface AtlasGesture {
    data object Idle : AtlasGesture

    data class Pan(
        val start: AtlasPoint,
        val current: AtlasPoint,
    ) : AtlasGesture

    data class Zoom(
        val anchor: AtlasPoint,
    ) : AtlasGesture

    data class BoxSelect(
        val start: AtlasPoint,
        val current: AtlasPoint,
    ) : AtlasGesture

    data class NodeDrag(
        val nodeId: AtlasSemanticId,
        val start: AtlasPoint,
        val current: AtlasPoint,
    ) : AtlasGesture

    data class ContainerDrag(
        val containerId: AtlasSemanticId,
        val start: AtlasPoint,
        val current: AtlasPoint,
    ) : AtlasGesture

    data class Resize(
        val containerId: AtlasSemanticId,
        val start: AtlasPoint,
        val current: AtlasPoint,
    ) : AtlasGesture
}

internal data class AtlasLayoutOverride(
    val position: AtlasPoint? = null,
    val minimumSize: AtlasSize? = null,
    val pinned: Boolean = false,
    val locked: Boolean = false,
)

internal data class AtlasLayout(
    val calculated: Map<AtlasSemanticId, AtlasRect> = emptyMap(),
    val overrides: Map<AtlasSemanticId, AtlasLayoutOverride> = emptyMap(),
)
