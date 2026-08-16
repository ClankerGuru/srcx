package zone.clanker.docx.web.atlas.dom.dashboard

import org.w3c.dom.HTMLDivElement
import zone.clanker.docx.web.atlas.dom.AtlasDomSurfaceModel
import zone.clanker.docx.web.atlas.dom.atlasBuildColor
import zone.clanker.docx.web.atlas.dom.clearContent
import zone.clanker.docx.web.atlas.dom.htmlElement
import zone.clanker.docx.web.atlas.dom.requiredElement
import zone.clanker.docx.web.atlas.dom.requiredHtmlElement
import zone.clanker.docx.web.catalog.orderedBuilds
import zone.clanker.report.model.AtlasNodeType
import zone.clanker.report.model.WorkspaceSummaryShard

internal class AtlasDomHeaderRenderer {
    private var renderedBuildGuide: WorkspaceSummaryShard? = null

    fun render(
        root: HTMLDivElement,
        model: AtlasDomSurfaceModel,
    ) {
        renderIdentity(root, model)
        renderGraphState(root, model)
        renderBuildGuide(root, model)
    }

    private fun renderIdentity(
        root: HTMLDivElement,
        model: AtlasDomSurfaceModel,
    ) {
        renderWorkspaceIdentity(root, model)
        renderReportMetrics(root, model)
        root.requiredElement("[data-docx-workspace-status-label]").textContent =
            model.loadingStatus() ?: "Scan complete"
    }

    private fun renderWorkspaceIdentity(
        root: HTMLDivElement,
        model: AtlasDomSurfaceModel,
    ) {
        val selectedBuild = model.selectedBuild()
        root.requiredElement("[data-docx-workspace-name]").textContent = model.summary.workspace.name
        root.requiredElement("[data-docx-workspace-tag]").textContent = model.summary.workspace.name
        root.requiredElement("[data-docx-build-count-tag]").textContent = "${model.summary.builds.size} builds"
        root.requiredElement("[data-docx-generation-label]").textContent = "Generation ${model.generationId}"
        root.requiredElement("[data-docx-selected-project]").textContent =
            when {
                model.selectedProjectIds.size == 1 -> model.selectedProject?.path
                model.selectedProjectIds.isNotEmpty() -> "${model.selectedProjectIds.size} selected projects"
                model.selectedBuildIds.size == 1 -> "All projects in ${selectedBuild?.name}"
                model.selectedBuildIds.isNotEmpty() -> "All projects in ${model.selectedBuildIds.size} builds"
                else -> "All workspace"
            }
        root.requiredElement("[data-docx-selected-build-file]").textContent =
            when {
                model.selectedProjectIds.size == 1 -> model.selectedProject?.buildFile
                model.selectedBuildIds.size == 1 -> selectedBuild?.let { "${it.kind.label} / ${it.relativePath}" }
                model.selectedBuildIds.isNotEmpty() -> "${model.selectedBuildIds.size} selected build roots"
                else -> "Bounded multi-build overview"
            }
    }

    private fun renderReportMetrics(
        root: HTMLDivElement,
        model: AtlasDomSurfaceModel,
    ) {
        root.requiredElement("[data-docx-project-count]").textContent =
            (model.dashboard?.projectCount ?: model.summary.projects.size).toString()
        root.requiredElement("[data-docx-symbol-count]").textContent =
            model.dashboard?.symbolCount?.toString() ?: "—"
        root.requiredElement("[data-docx-relationship-count]").textContent =
            model.dashboard?.dependencyCount?.toString() ?: "—"
        root.requiredElement("[data-docx-finding-count]").textContent =
            model.dashboard
                ?.findings
                ?.size
                ?.toString() ?: "—"
        root.requiredElement("[data-docx-files-shown]").textContent =
            model.frame
                ?.nodes
                ?.count { node -> node.type == AtlasNodeType.FILE }
                ?.toString() ?: "0"
        root.requiredElement("[data-docx-links-shown]").textContent = model.frame
            ?.edges
            ?.size
            ?.toString() ?: "0"
        root.requiredElement("[data-docx-symbols-shown]").textContent =
            model.frame
                ?.nodes
                ?.count { node -> node.type == AtlasNodeType.SYMBOL }
                ?.toString() ?: "0"
        root.requiredElement("[data-docx-findings-shown]").textContent =
            model.frame
                ?.nodes
                ?.sumOf { node -> node.findingCount }
                ?.toString() ?: "0"
    }

    private fun renderGraphState(
        root: HTMLDivElement,
        model: AtlasDomSurfaceModel,
    ) {
        val fallback = root.requiredHtmlElement("[data-srcx-graph-fallback]")
        val svg = root.requiredElement("[data-srcx-graph-svg]")
        val status = root.requiredElement("[data-srcx-graph-status]")
        val message = model.graphUnavailableMessage()
        fallback.hidden = message == null
        if (message == null) {
            svg.removeAttribute("hidden")
            root.setAttribute("data-srcx-empty", "false")
            status.textContent =
                if (model.projectLoading || model.buildLoading) {
                    model.loadingStatus()
                } else if (model.selectedProject == null) {
                    when {
                        model.selectedProjectIds.isNotEmpty() ->
                            "Complete ${model.selectedProjectIds.size}-project Atlas frame"
                        model.selectedBuildIds.size == 1 ->
                            "Complete ${model.selectedBuild()?.name} build Atlas frame"
                        model.selectedBuildIds.isNotEmpty() ->
                            "Complete ${model.selectedBuildIds.size}-build Atlas frame"
                        else -> "Bounded All-workspace Atlas overview"
                    }
                } else {
                    "Preparing the ${model.selectedProject.path} Atlas frame…"
                }
        } else {
            svg.setAttribute("hidden", "")
            root.setAttribute("data-srcx-empty", "true")
            fallback.textContent = message
            status.textContent = message
        }
    }

    private fun renderBuildGuide(
        root: HTMLDivElement,
        model: AtlasDomSurfaceModel,
    ) {
        if (renderedBuildGuide === model.summary) return
        val guide = root.requiredHtmlElement("[data-docx-build-guide]")
        guide.clearContent()
        orderedBuilds(model.summary).forEach { build ->
            val item = htmlElement("li")
            item.style.setProperty("--srcx-build-color", atlasBuildColor(build.name))
            item.appendChild(htmlElement("i").also { marker -> marker.setAttribute("aria-hidden", "true") })
            item.appendChild(htmlElement("span", text = "${build.name} — ${build.kind.label}"))
            guide.appendChild(item)
        }
        renderedBuildGuide = model.summary
    }
}

private fun AtlasDomSurfaceModel.graphUnavailableMessage(): String? =
    when {
        frameJson != null -> null
        buildLoading -> "Loading every typed project shard in the selected scope…"
        buildError != null -> "Build scope failed: $buildError"
        projectLoading -> "Loading the typed project shard for ${selectedProject?.path ?: "project"}…"
        projectError != null -> "Project shard failed: $projectError"
        selectedBuildIds.isNotEmpty() || selectedProjectIds.isNotEmpty() ->
            "No graph frame is available for the selected build/project scope."
        selectedProject == null -> "The workspace Atlas overview is unavailable. Select a build or project."
        else -> "No graph frame is available for ${selectedProject.path}."
    }

private fun AtlasDomSurfaceModel.selectedBuild() =
    selectedBuildId?.let { buildId -> summary.builds.firstOrNull { build -> build.id == buildId } }

private fun AtlasDomSurfaceModel.loadingStatus(): String? =
    when {
        projectLoading -> "Loading ${selectedProject?.path ?: "project"}"
        buildLoading -> "Loading selected project shards"
        else -> null
    }
