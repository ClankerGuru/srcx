package zone.clanker.docx.web.atlas.dom.controls

import org.w3c.dom.HTMLDivElement
import zone.clanker.docx.web.atlas.AtlasController
import zone.clanker.docx.web.atlas.AtlasLayoutMode
import zone.clanker.docx.web.atlas.GraphLens
import zone.clanker.docx.web.atlas.dom.AtlasDomSurfaceModel
import zone.clanker.docx.web.atlas.dom.requiredButton
import zone.clanker.docx.web.atlas.dom.requiredElement
import zone.clanker.docx.web.atlas.session.AtlasLayer
import zone.clanker.docx.web.atlas.session.AtlasOverlay
import zone.clanker.docx.web.atlas.session.AtlasOverlayChanged
import zone.clanker.docx.web.atlas.toggleLayer
import zone.clanker.report.model.CycleSnapshot
import zone.clanker.report.model.FindingSnapshot
import zone.clanker.report.model.ProjectGraphShard

internal class AtlasLensControlsRenderer {
    fun render(
        root: HTMLDivElement,
        model: AtlasDomSurfaceModel,
        controller: AtlasController,
        enabled: Boolean,
    ) {
        val counts = model.lensCounts()
        GraphLens.entries.forEach { lens ->
            val button = root.requiredButton("[data-srcx-graph-view=\"${lens.attributeValue}\"]")
            val count = counts[lens]
            val layer = lens.atlasLayer
            val selected =
                if (model.preserveDetailedOverview) {
                    controller.graph.lens == lens
                } else {
                    layer in
                        controller.session
                            ?.layers
                            ?.enabled
                            .orEmpty()
                }
            button.disabled = !enabled
            button.setAttribute("aria-pressed", selected.toString())
            button.setAttribute("aria-label", lens.ariaLabel(count))
            root.requiredElement("[data-srcx-graph-view-count=\"${lens.attributeValue}\"]").textContent =
                count?.toString() ?: "?"
            button.onclick = {
                if (model.preserveDetailedOverview) {
                    controller.graph.updateLens(lens)
                } else {
                    controller.toggleLayer(layer)
                }
                if (controller.layoutMode == AtlasLayoutMode.COMPACT) {
                    controller.dispatch(AtlasOverlayChanged(AtlasOverlay.Closed))
                }
                null
            }
        }
        root.setAttribute("data-srcx-graph-lens", controller.graph.lens.attributeValue)
        root.setAttribute(
            "data-srcx-graph-layers",
            controller.session
                ?.layers
                ?.enabled
                .orEmpty()
                .joinToString(",") { layer -> layer.name.lowercase() },
        )
    }
}

private fun AtlasDomSurfaceModel.lensCounts(): Map<GraphLens, Int?> {
    val projects = loadedProjects()
    if (projects.isEmpty()) {
        return mapOf(
            GraphLens.FILES to dashboard?.projects?.sumOf { project -> project.fileCount },
            GraphLens.SYMBOLS to dashboard?.symbolCount,
            GraphLens.PROBLEMS to dashboard?.findings?.size,
            GraphLens.CYCLES to null,
        )
    }
    return mapOf(
        GraphLens.FILES to projects.flatMap(ProjectGraphShard::ownedFileIds).distinct().size,
        GraphLens.SYMBOLS to projects.flatMap(ProjectGraphShard::ownedSymbolIds).distinct().size,
        GraphLens.PROBLEMS to projects.problemCount(),
        GraphLens.CYCLES to projects.cycleCount(),
    )
}

private fun List<ProjectGraphShard>.problemCount(): Int =
    flatMap { project -> project.findings + project.analysis?.findings.orEmpty() }
        .distinctBy(FindingSnapshot::id)
        .size

private fun List<ProjectGraphShard>.cycleCount(): Int {
    val observed = flatMap(ProjectGraphShard::cycles).map(CycleSnapshot::id)
    val analyzed = flatMap { project -> project.analysis?.cycles.orEmpty() }.map { cycle -> cycle.id }
    return (observed + analyzed).distinct().size
}

private fun GraphLens.ariaLabel(count: Int?): String =
    if (count == null) {
        "$label; select a scope to calculate the available count"
    } else {
        "$label; $count available in this scope"
    }

private val GraphLens.atlasLayer: AtlasLayer
    get() =
        when (this) {
            GraphLens.FILES -> AtlasLayer.FILES
            GraphLens.SYMBOLS -> AtlasLayer.SYMBOLS
            GraphLens.PROBLEMS -> AtlasLayer.PROBLEMS
            GraphLens.CYCLES -> AtlasLayer.CYCLES
        }
