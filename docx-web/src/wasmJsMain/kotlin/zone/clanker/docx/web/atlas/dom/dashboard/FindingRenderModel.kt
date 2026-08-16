package zone.clanker.docx.web.atlas.dom.dashboard

import org.w3c.dom.HTMLDivElement
import zone.clanker.docx.web.atlas.dom.AtlasDomActions
import zone.clanker.report.model.BuildSnapshot
import zone.clanker.report.model.FindingSeverity
import zone.clanker.report.model.ProjectSnapshot
import zone.clanker.report.model.WorkspaceDashboardFinding
import zone.clanker.report.model.WorkspaceDashboardShard
import zone.clanker.report.model.WorkspaceSummaryShard

internal class FindingSelection(
    var buildId: String? = null,
    var projectId: String? = null,
    var severity: FindingSeverity? = null,
) {
    val hasSelection: Boolean
        get() = buildId != null || projectId != null || severity != null

    fun matches(item: WorkspaceDashboardFinding): Boolean =
        (buildId == null || item.buildId == buildId) &&
            (projectId == null || item.projectId == projectId) &&
            (severity == null || item.finding.severity == severity)

    fun reset() {
        buildId = null
        projectId = null
        severity = null
    }
}

internal data class FindingRenderContext(
    val root: HTMLDivElement,
    val summary: WorkspaceSummaryShard,
    val dashboard: WorkspaceDashboardShard,
    val actions: AtlasDomActions,
) {
    val buildsById: Map<String, BuildSnapshot> = summary.builds.associateBy(BuildSnapshot::id)
    val projectsById: Map<String, ProjectSnapshot> = summary.projects.associateBy(ProjectSnapshot::id)
}

internal data class FindingFilterRail(
    val kind: String,
    val allLabel: String,
    val items: List<FindingFilterItem>,
    val selectedId: String?,
    val onSelected: (String?) -> Unit,
)

internal data class FindingFilterItem(
    val id: String?,
    val label: String,
    val detail: String?,
    val count: Int,
)
