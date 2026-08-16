package zone.clanker.docx.web.atlas.dom.dashboard

import org.w3c.dom.HTMLDivElement
import zone.clanker.docx.web.atlas.dom.AtlasDomActions
import zone.clanker.docx.web.atlas.dom.requiredElement
import zone.clanker.report.model.AtlasFrame
import zone.clanker.report.model.WorkspaceDashboardShard
import zone.clanker.report.model.WorkspaceSummaryShard

internal class AtlasDomDashboardRenderer(
    private val buildsRenderer: AtlasDomBuildsRenderer = AtlasDomBuildsRenderer(),
    private val healthRenderer: AtlasDomHealthRenderer = AtlasDomHealthRenderer(),
    private val findingsRenderer: AtlasDomFindingsRenderer = AtlasDomFindingsRenderer(),
) {
    private var renderedSummary: WorkspaceSummaryShard? = null
    private var renderedDashboard: WorkspaceDashboardShard? = null

    fun render(
        root: HTMLDivElement,
        summary: WorkspaceSummaryShard,
        dashboard: WorkspaceDashboardShard?,
        frame: AtlasFrame?,
        actions: AtlasDomActions,
    ) {
        renderArchitectureEvidence(root, frame)
        if (dashboard == null) {
            renderLoadingState(root)
            return
        }
        if (summary === renderedSummary && dashboard === renderedDashboard) return
        renderMetrics(root, dashboard)
        buildsRenderer.render(root, summary, dashboard)
        healthRenderer.render(root, summary, dashboard)
        findingsRenderer.render(root, summary, dashboard, actions)
        root.setAttribute("data-docx-dashboard-state", "ready")
        renderedSummary = summary
        renderedDashboard = dashboard
    }

    private fun renderArchitectureEvidence(
        root: HTMLDivElement,
        frame: AtlasFrame?,
    ) {
        val summary = root.requiredElement("[data-docx-architecture-evidence-summary]")
        val copy = root.requiredElement("[data-docx-architecture-evidence-copy]")
        if (frame == null) {
            summary.textContent = "Map limits and evidence / loading relationship records / expand"
            copy.textContent = "The typed workspace Atlas frame is loading."
            return
        }
        summary.textContent =
            "Map limits and evidence / ${frame.totalRelationshipRecordCount} relationship records / expand"
        copy.textContent =
            "The current ${frame.scope.kind.name.lowercase()} projection includes ${frame.nodes.size} nodes and " +
            "${frame.edges.size} resolved links. ${frame.shownRelationshipRecordCount} of " +
            "${frame.totalRelationshipRecordCount} relationship records are visible in this typed frame."
    }

    private fun renderLoadingState(root: HTMLDivElement) {
        root.setAttribute("data-docx-dashboard-state", "loading")
        root.requiredElement("[data-srcx-finding-filter-status]").textContent = "Findings loading"
    }

    private fun renderMetrics(
        root: HTMLDivElement,
        dashboard: WorkspaceDashboardShard,
    ) {
        root.requiredElement("[data-docx-project-count]").textContent = dashboard.projectCount.toString()
        root.requiredElement("[data-docx-symbol-count]").textContent = dashboard.symbolCount.toString()
        root.requiredElement("[data-docx-relationship-count]").textContent = dashboard.dependencyCount.toString()
        root.requiredElement("[data-docx-finding-count]").textContent = dashboard.findings.size.toString()
    }
}
