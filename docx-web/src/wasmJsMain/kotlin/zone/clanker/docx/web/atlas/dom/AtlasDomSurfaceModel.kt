package zone.clanker.docx.web.atlas.dom

import zone.clanker.docx.web.atlas.AtlasReportSection
import zone.clanker.docx.web.atlas.AtlasSemanticMapDelivery
import zone.clanker.docx.web.state.EvidenceProjectState
import zone.clanker.docx.web.state.WorkspaceGlobalSearchState
import zone.clanker.report.model.AtlasFrame
import zone.clanker.report.model.ProjectGraphShard
import zone.clanker.report.model.ProjectShardReference
import zone.clanker.report.model.ProjectSnapshot
import zone.clanker.report.model.WorkspaceDashboardShard
import zone.clanker.report.model.WorkspaceGraphSlice
import zone.clanker.report.model.WorkspaceSummaryShard

internal data class AtlasDomSurfaceModel(
    val summary: WorkspaceSummaryShard,
    val dashboard: WorkspaceDashboardShard?,
    val generationId: String,
    val projectShardReferences: List<ProjectShardReference>,
    val selectedProject: ProjectSnapshot?,
    val selectedBuildId: String?,
    val selectedBuildIds: Set<String>,
    val selectedProjectIds: Set<String>,
    val activeSection: AtlasReportSection,
    val requestedSection: AtlasReportSection,
    val sectionNavigationRevision: Int,
    val project: ProjectGraphShard?,
    val projectLoading: Boolean,
    val projectError: String?,
    val buildProjects: List<ProjectGraphShard>,
    val buildLoading: Boolean,
    val buildError: String?,
    val evidence: EvidenceProjectState,
    val globalSearch: WorkspaceGlobalSearchState,
    val frame: AtlasFrame?,
    val frameJson: String?,
    val slice: WorkspaceGraphSlice?,
    val semanticMapDelivery: AtlasSemanticMapDelivery?,
    val preserveDetailedOverview: Boolean,
    val requestedFrameRevision: Int,
    val bridgeEnabled: Boolean,
)
