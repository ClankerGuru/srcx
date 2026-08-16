package zone.clanker.docx.web.atlas.dom

import org.w3c.dom.HTMLDivElement
import zone.clanker.docx.web.state.EvidenceSourceContentState

internal object AtlasDomStatusRenderer {
    fun render(
        root: HTMLDivElement,
        model: AtlasDomSurfaceModel,
    ) {
        renderGraph(root, model)
        renderEvidence(root, model)
    }

    private fun renderGraph(
        root: HTMLDivElement,
        model: AtlasDomSurfaceModel,
    ) {
        root.setAttribute("data-docx-atlas-load-state", model.atlasLoadState())
        root.setAttribute("data-docx-atlas-selected-build-ids", model.selectedBuildIds.sorted().joinToString("|"))
        root.setAttribute("data-docx-atlas-selected-project-ids", model.selectedProjectIds.sorted().joinToString("|"))
        root.optionalAttribute("data-docx-atlas-load-error", model.buildError ?: model.projectError)
        val slice = model.slice
        if (slice != null) {
            val primaryCount = slice.counts.matchingPrimaryNodeCount
            val relationCount = slice.counts.matchingRelationCount
            root.requiredElement("[data-srcx-graph-status]").textContent =
                "${slice.target.facet.name.displayName()} / " +
                "${slice.content.nodes.size} of $primaryCount matching nodes / " +
                "${slice.content.relations.size} of $relationCount routes"
            root.frameAttributes(slice.target.generationId, "semantic", slice.content.nodes.size, relationCount)
            return
        }
        val frame = model.frame
        if (frame == null) {
            root.clearFrameAttributes()
            return
        }
        root.requiredElement("[data-srcx-graph-status]").textContent =
            "${frame.lens.name.displayName()} / ${frame.nodes.size} of ${frame.matchingNodeCount} matching nodes / " +
            "${frame.edges.size} routes / ${frame.shownRelationshipRecordCount} displayed records"
        root.frameAttributes(
            frame.frameId,
            frame.scope.kind.name
                .lowercase(),
            frame.nodes.size,
            frame.shownRelationshipRecordCount.toLong(),
        )
    }

    private fun renderEvidence(
        root: HTMLDivElement,
        model: AtlasDomSurfaceModel,
    ) {
        val evidence = model.evidence
        root.setAttribute(
            "data-docx-atlas-evidence-state",
            when {
                evidence.loading -> "loading"
                evidence.error != null -> "error"
                evidence.project != null -> "ready"
                else -> "idle"
            },
        )
        root.optionalAttribute("data-docx-atlas-evidence-project-id", evidence.requestedProjectId)
        root.optionalAttribute("data-docx-atlas-evidence-error", evidence.error)
        renderSource(root, evidence.source)
    }

    private fun renderSource(
        root: HTMLDivElement,
        source: EvidenceSourceContentState,
    ) {
        root.setAttribute(
            "data-docx-atlas-source-state",
            when {
                source.loading -> "loading"
                source.error != null -> "error"
                source.content != null -> "ready"
                else -> "idle"
            },
        )
        root.optionalAttribute("data-docx-atlas-source-project-id", source.requestedProjectId)
        root.optionalAttribute("data-docx-atlas-source-file-id", source.requestedFileId)
        root.optionalAttribute("data-docx-atlas-source-content-hash", source.contentHash)
        root.optionalAttribute("data-docx-atlas-source-error", source.error)
    }
}

private fun HTMLDivElement.frameAttributes(
    frameId: String,
    scope: String,
    nodeCount: Int,
    recordCount: Long,
) {
    setAttribute("data-docx-atlas-frame-id", frameId)
    setAttribute("data-docx-atlas-scope", scope)
    setAttribute("data-docx-atlas-frame-nodes", nodeCount.toString())
    setAttribute("data-docx-atlas-frame-records", recordCount.toString())
}

private fun HTMLDivElement.clearFrameAttributes() {
    removeAttribute("data-docx-atlas-frame-id")
    removeAttribute("data-docx-atlas-scope")
    removeAttribute("data-docx-atlas-frame-nodes")
    removeAttribute("data-docx-atlas-frame-records")
}

private fun HTMLDivElement.optionalAttribute(
    name: String,
    value: String?,
) {
    if (value == null) removeAttribute(name) else setAttribute(name, value)
}

private fun String.displayName(): String = lowercase().replaceFirstChar { character -> character.uppercase() }

private fun AtlasDomSurfaceModel.atlasLoadState(): String =
    when {
        buildLoading -> "loading-build"
        buildError != null -> "build-error"
        projectLoading -> "loading-project"
        projectError != null -> "project-error"
        slice != null || frame != null -> "ready"
        else -> "unavailable"
    }
