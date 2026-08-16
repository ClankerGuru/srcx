package zone.clanker.docx.web.atlas.session

internal data class AtlasPoint(
    val x: Double,
    val y: Double,
) {
    init {
        require(x.isFinite() && y.isFinite()) { "Atlas points must be finite" }
    }

    companion object {
        val ORIGIN: AtlasPoint = AtlasPoint(0.0, 0.0)
    }
}

internal data class AtlasSize(
    val width: Double,
    val height: Double,
) {
    init {
        require(width.isFinite() && height.isFinite()) { "Atlas sizes must be finite" }
        require(width >= 0.0 && height >= 0.0) { "Atlas sizes must not be negative" }
    }

    companion object {
        val EMPTY: AtlasSize = AtlasSize(0.0, 0.0)
    }
}

internal data class AtlasRect(
    val origin: AtlasPoint,
    val size: AtlasSize,
)

internal data class AtlasCamera(
    val position: AtlasPoint = AtlasPoint.ORIGIN,
    val scale: Double = 1.0,
    val viewport: AtlasSize = AtlasSize.EMPTY,
    val transition: AtlasCameraTransition = AtlasCameraTransition.Idle,
    val coordinateSpace: AtlasCameraCoordinateSpace = AtlasCameraCoordinateSpace.UNINITIALIZED,
) {
    init {
        require(scale.isFinite() && scale > 0.0) { "Atlas camera scale must be finite and positive" }
    }
}

internal enum class AtlasCameraCoordinateSpace {
    UNINITIALIZED,
    DETAILED_OVERVIEW,
    SEMANTIC_MAP,
}

internal sealed interface AtlasCameraTransition {
    data object Idle : AtlasCameraTransition

    data class Moving(
        val reason: AtlasCameraMotion,
        val targetId: AtlasSemanticId? = null,
    ) : AtlasCameraTransition
}

internal enum class AtlasCameraMotion {
    ZOOM,
    PAN,
    FIT,
    FLY_TO,
    DRILL,
    RESET,
    HISTORY,
}

internal data class AtlasFocus(
    val path: List<AtlasSemanticId> = emptyList(),
    val expandedIds: List<AtlasSemanticId> = emptyList(),
    val automaticExpandedIds: List<AtlasSemanticId> = emptyList(),
    val requestTargetId: AtlasSemanticId? = null,
) {
    init {
        require(path.size == path.distinct().size) { "Atlas focus paths must not contain repeated IDs" }
        requireCanonicalIds(expandedIds, "Atlas expanded IDs")
        requireCanonicalIds(automaticExpandedIds, "Atlas automatic expanded IDs")
    }

    val targetId: AtlasSemanticId?
        get() = path.lastOrNull()

    val effectiveExpandedIds: List<AtlasSemanticId>
        get() = canonicalIds(expandedIds + automaticExpandedIds)
}

internal data class AtlasScope(
    val levels: List<AtlasScopeLevelSelection> = emptyList(),
) {
    init {
        require(
            levels.map(AtlasScopeLevelSelection::level).let { selectedLevels ->
                selectedLevels == selectedLevels.distinct().sortedBy { level -> level.ordinal }
            },
        ) {
            "Atlas scope levels must be distinct and ordered"
        }
        require(levels.none { selection -> selection.ids.isEmpty() }) {
            "Atlas scope represents All by omitting a level, never with an empty level entry"
        }
    }

    fun selectedIds(level: AtlasHierarchyLevel): List<AtlasSemanticId> =
        levels.firstOrNull { selection -> selection.level == level }?.ids.orEmpty()

    fun deepestSelectedIds(): List<AtlasSemanticId> = levels.lastOrNull()?.ids.orEmpty()

    fun replacing(
        level: AtlasHierarchyLevel,
        ids: List<AtlasSemanticId>,
    ): AtlasScope {
        val next = levels.filterNot { selection -> selection.level == level }
        val replacement =
            ids
                .takeIf { selectedIds -> selectedIds.isNotEmpty() }
                ?.let { selectedIds -> AtlasScopeLevelSelection(level, selectedIds) }
        return AtlasScope((next + listOfNotNull(replacement)).sortedBy { selection -> selection.level.ordinal })
    }

    companion object {
        val ALL: AtlasScope = AtlasScope()
    }
}

internal data class AtlasScopeLevelSelection(
    val level: AtlasHierarchyLevel,
    val ids: List<AtlasSemanticId>,
) {
    init {
        require(ids.isNotEmpty()) { "Atlas scope level selections must not be empty" }
        requireCanonicalIds(ids, "Atlas scope IDs")
    }
}

internal data class AtlasInspectionTarget(
    val kind: AtlasInspectionKind,
    val id: AtlasSemanticId,
)

internal enum class AtlasInspectionKind {
    NODE,
    RELATION,
    FACT,
}

internal data class AtlasEvidenceSelection(
    val target: AtlasInspectionTarget,
    val occurrenceId: AtlasSemanticId? = null,
    val message: String? = null,
) {
    init {
        require(message == null || message.isNotBlank()) { "Atlas evidence messages must not be blank" }
    }
}

internal data class AtlasInspection(
    val primary: AtlasInspectionTarget? = null,
    val secondary: List<AtlasInspectionTarget> = emptyList(),
    val hovered: AtlasInspectionTarget? = null,
    val evidence: AtlasEvidenceSelection? = null,
) {
    init {
        require(secondary == secondary.distinct().sortedWith(INSPECTION_TARGET_ORDER)) {
            "Atlas secondary inspections must be distinct and ordered"
        }
        require(primary !in secondary) { "Atlas primary inspection must not also be secondary" }
    }

    fun withoutTransientState(): AtlasInspection = copy(hovered = null)
}

internal fun canonicalIds(ids: Iterable<AtlasSemanticId>): List<AtlasSemanticId> =
    ids.distinct().sortedBy(AtlasSemanticId::value)

internal fun canonicalInspectionTargets(targets: Iterable<AtlasInspectionTarget>): List<AtlasInspectionTarget> =
    targets.distinct().sortedWith(INSPECTION_TARGET_ORDER)

private fun requireCanonicalIds(
    ids: List<AtlasSemanticId>,
    label: String,
) {
    require(ids == canonicalIds(ids)) { "$label must be distinct and ordered" }
}

private val INSPECTION_TARGET_ORDER =
    compareBy<AtlasInspectionTarget>(AtlasInspectionTarget::kind).thenBy { target -> target.id.value }
