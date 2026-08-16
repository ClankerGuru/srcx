package zone.clanker.docx.web.atlas.dom.detail

import org.w3c.dom.HTMLDivElement
import org.w3c.dom.HTMLElement
import zone.clanker.docx.web.atlas.dom.AtlasDomSurfaceModel
import zone.clanker.docx.web.atlas.dom.requiredElement
import zone.clanker.report.model.AtlasNode
import zone.clanker.report.model.WorkspaceGraphNode

internal fun renderOverviewNodeDetail(
    root: HTMLDivElement,
    fields: HTMLElement,
    model: AtlasDomSurfaceModel,
    nodeId: String,
) {
    val semanticNode =
        model.slice
            ?.content
            ?.nodes
            ?.firstOrNull { candidate -> candidate.id == nodeId }
    if (semanticNode != null) {
        renderSemanticNodeDetail(root, fields, semanticNode)
        return
    }
    val node = model.frame?.nodes?.firstOrNull { candidate -> candidate.id == nodeId }
    if (node == null) {
        if (renderSummaryNodeDetail(root, fields, model, nodeId)) return
        renderMissingOverviewDetail(root, fields)
        return
    }
    val typeLabel =
        node.type.name
            .lowercase()
            .replaceFirstChar { it.uppercase() }
    setOverviewHeading(root, typeLabel, node.name)
    fields.appendChild(legacyOverviewNodeList(node))
}

internal fun renderOverviewEdgeDetail(
    root: HTMLDivElement,
    fields: HTMLElement,
    model: AtlasDomSurfaceModel,
    edgeId: String,
) {
    val semanticRelation =
        model.slice
            ?.content
            ?.relations
            ?.firstOrNull { relation -> relation.id == edgeId }
    if (semanticRelation != null) {
        val nodesById =
            model.slice.content.nodes
                .associateBy(WorkspaceGraphNode::id)
        val source =
            nodesById[semanticRelation.endpoints.sourceNodeId]
                ?.presentation
                ?.label
                ?: semanticRelation.endpoints.sourceNodeId
        val target =
            nodesById[semanticRelation.endpoints.targetNodeId]
                ?.presentation
                ?.label
                ?: semanticRelation.endpoints.targetNodeId
        setOverviewHeading(root, "Semantic route", "$source → $target")
        fields.appendChild(
            detailList(
                "Direction" to "$source → $target",
                "Kind" to semanticRelation.kind.name.lowercase(),
                "Relationship facts" to semanticRelation.facts.factCount.toString(),
                "Evidence shown" to
                    semanticRelation.facts.sampleFactIds.size
                        .toString(),
                "Evidence omitted" to semanticRelation.facts.omittedFactCount.toString(),
            ),
        )
        return
    }
    val frame = model.frame
    val edge =
        frame
            ?.edges
            ?.firstOrNull { candidate -> candidate.id == edgeId || edgeId in candidate.relationshipIds }
    if (frame == null || edge == null) {
        renderMissingOverviewDetail(root, fields)
        return
    }
    val nodesById = frame.nodes.associateBy(AtlasNode::id)
    val source = nodesById[edge.sourceId]?.name ?: edge.sourceId
    val target = nodesById[edge.targetId]?.name ?: edge.targetId
    setOverviewHeading(root, "Workspace route", "$source → $target")
    fields.appendChild(
        detailList(
            "Direction" to "$source → $target",
            "Category" to edge.category.name.lowercase(),
            "Relationship records" to edge.recordCount.toString(),
            "Count meaning" to "serialized static-analysis records, not runtime calls or unique callers",
        ),
    )
}

private fun renderSemanticNodeDetail(
    root: HTMLDivElement,
    fields: HTMLElement,
    node: WorkspaceGraphNode,
) {
    val kindLabel =
        node.kind.name
            .lowercase()
            .replaceFirstChar { it.uppercase() }
    setOverviewHeading(
        root,
        kindLabel,
        node.presentation.label,
    )
    fields.appendChild(
        detailList(
            "Context" to (node.presentation.secondaryLabel ?: "Workspace hierarchy"),
            "Direct children" to node.hierarchy.directChildCount.toString(),
            "All descendants" to node.hierarchy.descendantCount.toString(),
            "Visible because" to node.roles.joinToString { role -> role.name.lowercase() },
        ),
    )
}

private fun legacyOverviewNodeList(node: AtlasNode): HTMLElement =
    detailList(
        "Build" to (node.buildName ?: "Workspace scope"),
        "Project" to (node.projectPath ?: "All projects"),
        "Path" to (node.path ?: "Catalog node"),
        "Relationship records" to node.relationshipRecordCount.toString(),
        "Findings" to node.findingCount.toString(),
    )

private fun renderSummaryNodeDetail(
    root: HTMLDivElement,
    fields: HTMLElement,
    model: AtlasDomSurfaceModel,
    nodeId: String,
): Boolean {
    val detail = model.summaryNodeDetail(nodeId) ?: return false
    setOverviewHeading(root, detail.kicker, detail.title)
    fields.appendChild(detailList(*detail.fields.toTypedArray()))
    return true
}

private data class SummaryNodeDetail(
    val kicker: String,
    val title: String,
    val fields: List<Pair<String, String>>,
)

private fun AtlasDomSurfaceModel.summaryNodeDetail(nodeId: String): SummaryNodeDetail? =
    workspaceSummaryDetail(nodeId)
        ?: buildSummaryDetail(nodeId)
        ?: projectSummaryDetail(nodeId)
        ?: sourceSetSummaryDetail(nodeId)

private fun AtlasDomSurfaceModel.workspaceSummaryDetail(nodeId: String): SummaryNodeDetail? =
    summary.workspace.takeIf { workspace -> workspace.id == nodeId }?.let { workspace ->
        SummaryNodeDetail(
            kicker = "Workspace",
            title = workspace.name,
            fields =
                listOf(
                    "Builds" to summary.builds.size.toString(),
                    "Projects" to summary.projects.size.toString(),
                    "Source sets" to summary.sourceSets.size.toString(),
                ),
        )
    }

private fun AtlasDomSurfaceModel.buildSummaryDetail(nodeId: String): SummaryNodeDetail? =
    summary.builds.firstOrNull { build -> build.id == nodeId }?.let { build ->
        SummaryNodeDetail(
            kicker = build.kind.label,
            title = build.name,
            fields =
                listOf(
                    "Path" to build.relativePath,
                    "Projects" to summary.projects.count { project -> project.buildId == build.id }.toString(),
                ),
        )
    }

private fun AtlasDomSurfaceModel.projectSummaryDetail(nodeId: String): SummaryNodeDetail? =
    summary.projects.firstOrNull { project -> project.id == nodeId }?.let { project ->
        SummaryNodeDetail(
            kicker = "Gradle project",
            title = project.path,
            fields =
                listOf(
                    "Build file" to project.buildFile,
                    "Source sets" to
                        summary.sourceSets
                            .count { sourceSet -> sourceSet.projectId == project.id }
                            .toString(),
                ),
        )
    }

private fun AtlasDomSurfaceModel.sourceSetSummaryDetail(nodeId: String): SummaryNodeDetail? =
    summary.sourceSets.firstOrNull { sourceSet -> sourceSet.id == nodeId }?.let { sourceSet ->
        SummaryNodeDetail(
            kicker = "Source set",
            title = sourceSet.name,
            fields =
                listOf(
                    "Project" to sourceSet.projectId,
                    "Files" to sourceSet.fileCount.toString(),
                ),
        )
    }

private fun renderMissingOverviewDetail(
    root: HTMLDivElement,
    fields: HTMLElement,
) {
    setOverviewHeading(root, "Selection", "Evidence unavailable")
    fields.appendChild(detailList("Status" to "The selected record is not in the bounded graph slice."))
}

private fun setOverviewHeading(
    root: HTMLDivElement,
    kicker: String,
    title: String,
) {
    root.requiredElement("[data-srcx-detail-kicker]").textContent = kicker
    root.requiredElement("[data-srcx-detail-title]").textContent = title
}
