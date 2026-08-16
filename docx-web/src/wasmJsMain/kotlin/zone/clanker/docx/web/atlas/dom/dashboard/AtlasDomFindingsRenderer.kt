package zone.clanker.docx.web.atlas.dom.dashboard

import org.w3c.dom.HTMLDivElement
import zone.clanker.docx.web.atlas.dom.AtlasDomActions
import zone.clanker.docx.web.atlas.dom.requiredButton
import zone.clanker.docx.web.atlas.dom.requiredElement
import zone.clanker.report.model.FindingSeverity
import zone.clanker.report.model.WorkspaceDashboardShard
import zone.clanker.report.model.WorkspaceSummaryShard

internal class AtlasDomFindingsRenderer {
    private val selection = FindingSelection()
    private val railRenderer = FindingFilterRailRenderer()
    private val rowsRenderer = FindingRowsRenderer()

    fun render(
        root: HTMLDivElement,
        summary: WorkspaceSummaryShard,
        dashboard: WorkspaceDashboardShard,
        actions: AtlasDomActions,
    ) {
        val context = FindingRenderContext(root, summary, dashboard, actions)
        normalizeSelectedProject(dashboard)
        renderBuildFilter(context)
        renderProjectFilter(context)
        renderSeverityFilter(context)
        renderReset(context)
        renderList(context)
    }

    private fun normalizeSelectedProject(dashboard: WorkspaceDashboardShard) {
        val projectId = selection.projectId
        if (projectId != null && dashboard.findings.none { item -> item.projectId == projectId }) {
            selection.projectId = null
        }
    }

    private fun renderBuildFilter(context: FindingRenderContext) {
        railRenderer.render(
            context.root,
            FindingFilterRail(
                kind = "build",
                allLabel = "All builds",
                items = buildFilterItems(context),
                selectedId = selection.buildId,
            ) { buildId -> selectBuild(context, buildId) },
        )
    }

    private fun buildFilterItems(context: FindingRenderContext): List<FindingFilterItem> =
        context.dashboard.builds.mapNotNull { build ->
            val count = context.dashboard.findings.count { finding -> finding.buildId == build.buildId }
            if (count == 0) {
                null
            } else {
                FindingFilterItem(
                    id = build.buildId,
                    label = requireNotNull(context.buildsById[build.buildId]).name,
                    detail = null,
                    count = count,
                )
            }
        }

    private fun selectBuild(
        context: FindingRenderContext,
        buildId: String?,
    ) {
        selection.buildId = buildId
        val projectId = selection.projectId
        val selectedProjectIsOutsideBuild =
            projectId != null &&
                context.dashboard.findings.none { finding ->
                    finding.buildId == buildId && finding.projectId == projectId
                }
        if (selectedProjectIsOutsideBuild) {
            selection.projectId = null
        }
        renderContext(context)
    }

    private fun renderProjectFilter(context: FindingRenderContext) {
        railRenderer.render(
            context.root,
            FindingFilterRail(
                kind = "project",
                allLabel = "All projects",
                items = projectFilterItems(context),
                selectedId = selection.projectId,
            ) { projectId ->
                selection.projectId = projectId
                renderContext(context)
            },
        )
    }

    private fun projectFilterItems(context: FindingRenderContext): List<FindingFilterItem> =
        context.dashboard.projects.mapNotNull { project ->
            val count = context.dashboard.findings.count { finding -> finding.projectId == project.projectId }
            val snapshot = requireNotNull(context.projectsById[project.projectId])
            val build = requireNotNull(context.buildsById[project.buildId])
            val outsideSelectedBuild = selection.buildId != null && project.buildId != selection.buildId
            if (count == 0 || outsideSelectedBuild) {
                null
            } else {
                FindingFilterItem(
                    id = project.projectId,
                    label = snapshot.path.dashboardRootProjectLabel(),
                    detail = build.name,
                    count = count,
                )
            }
        }

    private fun renderSeverityFilter(context: FindingRenderContext) {
        railRenderer.render(
            context.root,
            FindingFilterRail(
                kind = "severity",
                allLabel = "All severities",
                items = severityFilterItems(context.dashboard),
                selectedId = selection.severity?.name,
            ) { severity ->
                selection.severity = severity?.let { value -> FindingSeverity.valueOf(value) }
                renderContext(context)
            },
        )
    }

    private fun severityFilterItems(dashboard: WorkspaceDashboardShard): List<FindingFilterItem> =
        FindingSeverity.entries.map { severity ->
            FindingFilterItem(
                id = severity.name,
                label = severity.label,
                detail = null,
                count = dashboard.findings.count { item -> item.finding.severity == severity },
            )
        }

    private fun renderReset(context: FindingRenderContext) {
        val reset = context.root.requiredButton("[data-srcx-finding-filter-reset]")
        reset.disabled = !selection.hasSelection
        reset.onclick = {
            selection.reset()
            renderContext(context)
            null
        }
    }

    private fun renderList(context: FindingRenderContext) {
        val visible = context.dashboard.findings.filter(selection::matches)
        context.root.requiredElement("[data-srcx-finding-filter-status]").textContent =
            "${visible.size} ${visible.size.dashboardPlural("finding")} shown"
        rowsRenderer.render(context, visible)
    }

    private fun renderContext(context: FindingRenderContext) {
        render(context.root, context.summary, context.dashboard, context.actions)
    }
}
