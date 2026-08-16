package zone.clanker.docx.web.atlas.dom

import zone.clanker.docx.web.atlas.dom.search.AtlasGlobalSearchActions
import zone.clanker.docx.web.evidence.WorkspaceEvidenceGatewayProvider

internal data class AtlasDomActions(
    val onBuildSelected: (String?) -> Unit,
    val onProjectSelected: (String?) -> Unit,
    val onSourceSetsSelected: (Set<String>) -> Unit,
    val onNavigateBack: () -> Unit,
    val onNavigateForward: () -> Unit,
    val onFindingEvidenceSelected: (String) -> Unit,
    val evidence: AtlasDomEvidenceActions,
    val search: AtlasGlobalSearchActions,
)

internal data class AtlasDomEvidenceActions(
    val onProjectRequested: (String) -> Unit,
    val onSourceRequested: (projectId: String, fileId: String) -> Unit,
    val onLocationRequested: (projectId: String, fileId: String) -> Unit,
    val gatewayProvider: WorkspaceEvidenceGatewayProvider,
)
