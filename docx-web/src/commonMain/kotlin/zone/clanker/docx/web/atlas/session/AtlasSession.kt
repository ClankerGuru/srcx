package zone.clanker.docx.web.atlas.session

internal data class AtlasSession(
    val generation: AtlasGeneration,
    val hierarchy: AtlasHierarchyIndex = AtlasHierarchyIndex(),
    val camera: AtlasCamera = AtlasCamera(),
    val focus: AtlasFocus = AtlasFocus(),
    val scope: AtlasScope = AtlasScope.ALL,
    val inspection: AtlasInspection = AtlasInspection(),
    val layers: AtlasLayers = AtlasLayers(),
    val filters: AtlasFilters = AtlasFilters(),
    val overlay: AtlasOverlay = AtlasOverlay.Closed,
    val gesture: AtlasGesture = AtlasGesture.Idle,
    val history: AtlasHistory = AtlasHistory(),
    val layout: AtlasLayout = AtlasLayout(),
    val request: AtlasRequestState = AtlasRequestState.Idle(),
)

internal data class AtlasHistory(
    val backward: List<AtlasNavigationSnapshot> = emptyList(),
    val forward: List<AtlasNavigationSnapshot> = emptyList(),
) {
    val canNavigateBack: Boolean
        get() = backward.isNotEmpty()

    val canNavigateForward: Boolean
        get() = forward.isNotEmpty()
}

internal data class AtlasNavigationSnapshot(
    val camera: AtlasCamera,
    val focus: AtlasFocus,
    val scope: AtlasScope,
    val inspection: AtlasInspection,
    val layers: AtlasLayers,
    val filters: AtlasFilters,
    val layoutOverrides: Map<AtlasSemanticId, AtlasLayoutOverride>,
)

internal fun AtlasSession.navigationSnapshot(): AtlasNavigationSnapshot =
    AtlasNavigationSnapshot(
        camera = camera,
        focus = focus,
        scope = scope,
        inspection = inspection.withoutTransientState(),
        layers = layers,
        filters = filters,
        layoutOverrides = layout.overrides,
    )

internal fun AtlasSession.restoring(snapshot: AtlasNavigationSnapshot): AtlasSession =
    copy(
        camera = snapshot.camera,
        focus = snapshot.focus,
        scope = snapshot.scope,
        inspection = snapshot.inspection,
        layers = snapshot.layers,
        filters = snapshot.filters,
        layout = layout.copy(overrides = snapshot.layoutOverrides),
        overlay = AtlasOverlay.Closed,
        gesture = AtlasGesture.Idle,
    )

internal const val ATLAS_HISTORY_LIMIT: Int = 100
