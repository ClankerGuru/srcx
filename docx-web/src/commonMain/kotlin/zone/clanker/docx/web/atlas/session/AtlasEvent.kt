package zone.clanker.docx.web.atlas.session

import zone.clanker.report.model.WorkspaceGraphRequest
import zone.clanker.report.model.WorkspaceGraphSlice

internal sealed interface AtlasEvent

internal data class AtlasGenerationChanged(
    val generation: AtlasGeneration,
    val hierarchy: AtlasHierarchyIndex,
) : AtlasEvent

internal data class AtlasHierarchyExtended(
    val nodes: List<AtlasHierarchyNode>,
) : AtlasEvent

internal sealed interface AtlasNavigationEvent : AtlasEvent

internal data class AtlasCameraChanged(
    val camera: AtlasCamera,
    val recordHistory: Boolean = false,
) : AtlasNavigationEvent

internal data class AtlasFocusChanged(
    val focus: AtlasFocus,
    val recordHistory: Boolean = true,
) : AtlasNavigationEvent

internal data class AtlasScopeSelectionReplaced(
    val level: AtlasHierarchyLevel,
    val selectedIds: List<AtlasSemanticId>,
) : AtlasNavigationEvent

internal data class AtlasPrimaryInspectionChanged(
    val target: AtlasInspectionTarget?,
    val recordHistory: Boolean = true,
) : AtlasNavigationEvent

internal data class AtlasSecondaryInspectionToggled(
    val target: AtlasInspectionTarget,
    val recordHistory: Boolean = true,
) : AtlasNavigationEvent

internal data class AtlasEvidenceChanged(
    val evidence: AtlasEvidenceSelection?,
) : AtlasNavigationEvent

internal data object AtlasInspectionCleared : AtlasNavigationEvent

internal data class AtlasLayersChanged(
    val enabled: List<AtlasLayer>,
    val recordHistory: Boolean = true,
) : AtlasNavigationEvent

internal data class AtlasFiltersChanged(
    val filters: AtlasFilters,
    val recordHistory: Boolean = true,
) : AtlasNavigationEvent

internal data class AtlasLayoutOverrideChanged(
    val id: AtlasSemanticId,
    val override: AtlasLayoutOverride?,
    val recordHistory: Boolean = true,
) : AtlasNavigationEvent

internal data class AtlasFlyToRequested(
    val target: AtlasInspectionTarget,
    val revealAncestorId: AtlasSemanticId? = null,
) : AtlasNavigationEvent

internal data object AtlasNavigationCheckpoint : AtlasNavigationEvent

internal data object AtlasNavigateBack : AtlasNavigationEvent

internal data object AtlasNavigateForward : AtlasNavigationEvent

internal sealed interface AtlasInteractionEvent : AtlasEvent

internal data class AtlasOverlayChanged(
    val overlay: AtlasOverlay,
) : AtlasInteractionEvent

internal data class AtlasGestureChanged(
    val gesture: AtlasGesture,
) : AtlasInteractionEvent

internal data class AtlasHoverChanged(
    val target: AtlasInspectionTarget?,
) : AtlasInteractionEvent

internal data class AtlasCalculatedLayoutChanged(
    val calculated: Map<AtlasSemanticId, AtlasRect>,
) : AtlasInteractionEvent

internal data class AtlasSemanticExpansionChanged(
    val id: AtlasSemanticId,
    val expanded: Boolean,
) : AtlasInteractionEvent

internal sealed interface AtlasRequestEvent : AtlasEvent

internal data class AtlasRequestStarted(
    val request: WorkspaceGraphRequest,
) : AtlasRequestEvent

internal data class AtlasRequestSucceeded(
    val token: AtlasRequestToken,
    val slice: WorkspaceGraphSlice,
) : AtlasRequestEvent

internal data class AtlasRequestFailed(
    val token: AtlasRequestToken,
    val message: String,
) : AtlasRequestEvent {
    init {
        require(message.isNotBlank()) { "Atlas request failure must explain the error" }
    }
}

internal data class AtlasRequestCancelled(
    val token: AtlasRequestToken,
) : AtlasRequestEvent
