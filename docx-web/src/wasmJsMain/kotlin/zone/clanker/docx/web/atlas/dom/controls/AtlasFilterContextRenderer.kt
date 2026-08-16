package zone.clanker.docx.web.atlas.dom.controls

import org.w3c.dom.HTMLDivElement
import zone.clanker.docx.web.atlas.AtlasGraphController
import zone.clanker.docx.web.atlas.dom.AtlasDomSurfaceModel
import zone.clanker.docx.web.atlas.dom.requiredElement
import zone.clanker.docx.web.atlas.dom.requiredHtmlElement
import zone.clanker.report.model.WorkspaceSourceSetSummary

internal class AtlasFilterContextRenderer {
    fun render(
        root: HTMLDivElement,
        model: AtlasDomSurfaceModel,
        controller: AtlasGraphController,
    ) {
        val builds = model.summary.builds.filter { build -> build.id in model.selectedBuildIds }
        val projects = model.summary.projects.filter { project -> project.id in model.selectedProjectIds }
        val sourceSets =
            model.summary.sourceSets
                .filter { sourceSet -> sourceSet.id in controller.sourceSetIds }
        val buildLabel =
            when (builds.size) {
                0 -> "All builds"
                1 -> "${builds.single().kind.label} ${builds.single().name}"
                else -> "${builds.size} builds"
            }
        val projectLabel =
            when (projects.size) {
                0 -> "all projects"
                1 -> "project ${projects.single().path}"
                else -> "${projects.size} projects"
            }
        val sourceSetNames = sourceSets.map(WorkspaceSourceSetSummary::name).distinct().sorted()
        val sourceSetLabel = sourceSetLabel(sourceSetNames)
        root.requiredElement("[data-srcx-filter-context]").textContent =
            "$buildLabel / $projectLabel / $sourceSetLabel"
        root.requiredHtmlElement("[data-srcx-architecture-graph]").also { graph ->
            graph.setAttribute("data-srcx-graph-build", builds.joinToString(",") { it.name }.ifEmpty { "all" })
            graph.setAttribute("data-srcx-graph-project", projects.joinToString(",") { it.path }.ifEmpty { "all" })
            graph.setAttribute(
                "data-srcx-graph-source-set",
                sourceSetNames.joinToString(",").ifEmpty { "all" },
            )
        }
    }

    private fun sourceSetLabel(names: List<String>): String =
        when (names.size) {
            0 -> "all source sets"
            1 -> "source set ${names.single()}"
            else -> "${names.size} source-set names"
        }
}
