package zone.clanker.docx.web.atlas.dom.dashboard

import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import zone.clanker.docx.web.atlas.dom.buttonElement
import zone.clanker.docx.web.atlas.dom.clearContent
import zone.clanker.docx.web.atlas.dom.htmlElement
import zone.clanker.docx.web.atlas.dom.requiredHtmlElement
import zone.clanker.report.model.BuildSnapshot
import zone.clanker.report.model.WorkspaceDashboardFinding
import zone.clanker.report.model.WorkspaceProjectDashboard

internal class FindingRowsRenderer {
    fun render(
        context: FindingRenderContext,
        visible: List<WorkspaceDashboardFinding>,
    ) {
        val list = context.root.requiredHtmlElement("[data-docx-finding-list]")
        list.clearContent()
        visible
            .groupBy { item -> item.buildId to item.projectId }
            .forEach { (scope, findings) ->
                val build = requireNotNull(context.buildsById[scope.first])
                val project = requireNotNull(context.projectsById[scope.second])
                val projectDashboard =
                    requireNotNull(
                        context.dashboard.projects.singleOrNull { item -> item.projectId == scope.second },
                    )
                list.appendChild(
                    findingScope(
                        context,
                        FindingScope(
                            build,
                            project.path,
                            projectDashboard,
                            findings.sortedWith(findingDisplayOrder),
                        ),
                    ),
                )
            }
        if (visible.isEmpty()) {
            list.appendChild(
                dashboardEmptyState(
                    "No findings match these filters.",
                    "Reset filters to review all scopes.",
                ),
            )
        }
    }

    private fun findingScope(
        context: FindingRenderContext,
        model: FindingScope,
    ): HTMLElement =
        htmlElement("details", "srcx-disclosure srcx-dashboard__finding-scope").also { scope ->
            scope.setAttribute("data-srcx-finding-scope", "")
            scope.setAttribute("data-srcx-finding-build", model.build.name)
            scope.setAttribute("data-srcx-finding-project", model.projectPath)
            scope.appendChild(scopeHead(model))
            scope.appendChild(scopeRows(context, model))
        }

    private fun scopeHead(model: FindingScope): HTMLElement =
        htmlElement("summary", "srcx-dashboard__scope-head").also { summary ->
            summary.appendChild(
                htmlElement("span", "srcx-dashboard__scope-title").also { title ->
                    title.appendChild(htmlElement("small", text = model.build.name))
                    title.appendChild(
                        htmlElement("strong", text = model.projectPath.dashboardRootProjectLabel()),
                    )
                },
            )
            summary.appendChild(
                htmlElement("span", "srcx-dashboard__scope-meta").also { meta ->
                    meta.appendChild(
                        htmlElement(
                            "strong",
                            text = "${model.findings.size} ${model.findings.size.dashboardPlural("finding")}",
                        ),
                    )
                    meta.appendChild(htmlElement("small", text = model.project.sourceSetNames.joinToString()))
                },
            )
        }

    private fun scopeRows(
        context: FindingRenderContext,
        model: FindingScope,
    ): HTMLElement =
        htmlElement("div", "srcx-disclosure__body srcx-dashboard__finding-rows").also { rows ->
            model.findings.forEach { finding ->
                rows.appendChild(findingRow(context, model, finding))
            }
        }

    private fun findingRow(
        context: FindingRenderContext,
        model: FindingScope,
        item: WorkspaceDashboardFinding,
    ): HTMLElement =
        htmlElement(
            "article",
            "srcx-dashboard__finding-row ${item.finding.severity.dashboardToneClass}",
        ).also { row ->
            row.setAttribute("data-srcx-finding-row", "")
            row.setAttribute("data-srcx-finding-severity", item.finding.severity.name)
            row.setAttribute("data-srcx-finding-build", model.build.name)
            row.setAttribute("data-srcx-finding-project", model.projectPath)
            row.setAttribute("data-srcx-finding-id", item.id)
            row.appendChild(
                htmlElement(
                    "span",
                    "srcx-dashboard__finding-severity",
                    item.finding.severity.label,
                ),
            )
            row.appendChild(findingCopy(context, row, item))
        }

    private fun findingCopy(
        context: FindingRenderContext,
        row: HTMLElement,
        item: WorkspaceDashboardFinding,
    ): HTMLElement =
        htmlElement("div").also { copy ->
            copy.appendChild(htmlElement("strong", text = item.finding.message))
            copy.appendChild(htmlElement("p", text = item.finding.suggestion))
            item.finding.filePath?.let { path ->
                copy.appendChild(
                    htmlElement("code", text = dashboardSourceLocation(path, item.finding.line)),
                )
            }
            copy.appendChild(evidenceButton(context, row, item))
        }

    private fun evidenceButton(
        context: FindingRenderContext,
        row: HTMLElement,
        item: WorkspaceDashboardFinding,
    ): HTMLButtonElement =
        buttonElement("Open analyzer evidence", pressed = false) {
            row.setAttribute("data-docx-finding-open", "true")
            context.actions.onFindingEvidenceSelected(item.id)
        }.also { button ->
            button.removeAttribute("aria-pressed")
            button.setAttribute("data-srcx-open-finding", "")
            button.setAttribute("data-srcx-finding-id", item.id)
        }
}

private data class FindingScope(
    val build: BuildSnapshot,
    val projectPath: String,
    val project: WorkspaceProjectDashboard,
    val findings: List<WorkspaceDashboardFinding>,
)

private val findingDisplayOrder =
    compareBy<WorkspaceDashboardFinding>(
        { item -> item.finding.severity.ordinal },
        { item -> item.finding.message },
        { item -> item.finding.suggestion },
    )
