package zone.clanker.docx.web.atlas

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import zone.clanker.docx.web.atlas.projection.AtlasScopeSelection
import zone.clanker.docx.web.atlas.session.AtlasEvent
import zone.clanker.docx.web.atlas.session.AtlasFocus
import zone.clanker.docx.web.atlas.session.AtlasFocusChanged
import zone.clanker.docx.web.atlas.session.AtlasGeneration
import zone.clanker.docx.web.atlas.session.AtlasGenerationChanged
import zone.clanker.docx.web.atlas.session.AtlasHierarchyLevel
import zone.clanker.docx.web.atlas.session.AtlasInspectionCleared
import zone.clanker.docx.web.atlas.session.AtlasInspectionKind
import zone.clanker.docx.web.atlas.session.AtlasInspectionTarget
import zone.clanker.docx.web.atlas.session.AtlasNavigateBack
import zone.clanker.docx.web.atlas.session.AtlasNavigateForward
import zone.clanker.docx.web.atlas.session.AtlasOverlay
import zone.clanker.docx.web.atlas.session.AtlasOverlayChanged
import zone.clanker.docx.web.atlas.session.AtlasPrimaryInspectionChanged
import zone.clanker.docx.web.atlas.session.AtlasScopeSelectionReplaced
import zone.clanker.docx.web.atlas.session.AtlasSemanticExpansionChanged
import zone.clanker.docx.web.atlas.session.AtlasSemanticId
import zone.clanker.docx.web.atlas.session.AtlasSession
import zone.clanker.docx.web.atlas.session.reduceAtlasSession
import zone.clanker.docx.web.atlas.session.workspaceAtlasHierarchy
import zone.clanker.report.model.WorkspaceSummaryShard

@Stable
internal class AtlasController {
    var session by mutableStateOf<AtlasSession?>(null)
        private set

    val projectFilter: String
        get() = session?.filters?.projectQuery.orEmpty()

    var layoutMode by mutableStateOf(AtlasLayoutMode.WIDE)
        internal set

    var completedCommandRevision by mutableStateOf(0)
        private set

    var commandError by mutableStateOf<String?>(null)
        private set

    var focusMap by mutableStateOf(false)
        internal set

    val selectedBuildIds: Set<String>
        get() = selectedIds(AtlasHierarchyLevel.BUILD)

    val selectedProjectIds: Set<String>
        get() = selectedIds(AtlasHierarchyLevel.PROJECT)

    val selectedSourceSetIds: Set<String>
        get() = selectedIds(AtlasHierarchyLevel.SOURCE_SET)

    val selectedBuildId: String?
        get() = selectedBuildIds.singleOrNull()

    val selectedProjectId: String?
        get() = selectedProjectIds.singleOrNull()

    val scopeSelection: AtlasScopeSelection
        get() = AtlasScopeSelection(selectedBuildIds, selectedProjectIds)

    var activeSection by mutableStateOf(AtlasReportSection.ARCHITECTURE)
        internal set

    var requestedSection by mutableStateOf(AtlasReportSection.ARCHITECTURE)
        internal set

    var sectionNavigationRevision by mutableStateOf(0)
        internal set

    val graph = AtlasGraphController(session = { session }, dispatch = ::dispatch)

    fun attachGeneration(
        generationId: String,
        summary: WorkspaceSummaryShard,
    ) {
        val generation = AtlasGeneration(AtlasSemanticId(summary.workspace.id), generationId)
        val hierarchy = workspaceAtlasHierarchy(summary)
        val current = session
        val updated =
            if (current == null) {
                AtlasSession(
                    generation = generation,
                    hierarchy = hierarchy,
                    filters = atlasGraphDefaultFilters(),
                )
            } else {
                reduceAtlasSession(current, AtlasGenerationChanged(generation, hierarchy))
            }
        commitSession(updated)
    }

    fun dispatch(event: AtlasEvent) {
        val current = session ?: return
        commitSession(reduceAtlasSession(current, event))
    }

    fun completeCommand(
        revision: Int,
        error: String? = null,
    ) {
        commandError = error
        completedCommandRevision = revision
    }

    fun navigateBack() {
        dispatch(AtlasNavigateBack)
    }

    fun navigateForward() {
        dispatch(AtlasNavigateForward)
    }

    fun openSemanticNode(id: String) {
        val current = session ?: return
        val semanticId = AtlasSemanticId(id)
        val path = current.hierarchy.pathTo(semanticId)
        if (path.isEmpty()) return
        dispatch(
            AtlasFocusChanged(
                AtlasFocus(
                    path = path,
                    expandedIds = (current.focus.expandedIds + semanticId).distinct().sortedBy(AtlasSemanticId::value),
                    automaticExpandedIds = current.focus.automaticExpandedIds,
                ),
            ),
        )
    }

    fun updateSemanticExpansion(
        id: String,
        expanded: Boolean,
    ) {
        dispatch(AtlasSemanticExpansionChanged(AtlasSemanticId(id), expanded))
    }

    fun inspectNode(id: String) {
        dispatch(
            AtlasPrimaryInspectionChanged(
                AtlasInspectionTarget(AtlasInspectionKind.NODE, AtlasSemanticId(id)),
            ),
        )
    }

    fun inspectRelation(id: String) {
        dispatch(
            AtlasPrimaryInspectionChanged(
                AtlasInspectionTarget(AtlasInspectionKind.RELATION, AtlasSemanticId(id)),
            ),
        )
    }

    fun clearInspection() {
        dispatch(AtlasInspectionCleared)
    }

    fun toggleFocusMap() {
        focusMap = !focusMap
        if (focusMap) dispatch(AtlasOverlayChanged(AtlasOverlay.Closed))
    }

    private fun selectedIds(level: AtlasHierarchyLevel): Set<String> =
        session
            ?.scope
            ?.selectedIds(level)
            .orEmpty()
            .mapTo(mutableSetOf(), AtlasSemanticId::value)

    internal fun replaceScope(
        level: AtlasHierarchyLevel,
        ids: Set<String>,
    ) {
        dispatch(
            AtlasScopeSelectionReplaced(
                level = level,
                selectedIds = ids.sorted().map(::AtlasSemanticId),
            ),
        )
    }

    private fun commitSession(updated: AtlasSession) {
        val previous = session
        if (updated == previous) return
        session = updated
        graph.onSessionChanged(previous, updated)
    }
}
