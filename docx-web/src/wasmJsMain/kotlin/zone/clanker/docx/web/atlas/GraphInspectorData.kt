package zone.clanker.docx.web.atlas

import zone.clanker.report.model.ProjectGraphShard

internal fun inspectorContent(
    project: ProjectGraphShard?,
    controller: AtlasGraphController,
): String = inspectorContent(listOfNotNull(project), controller)

internal fun inspectorContent(
    projects: List<ProjectGraphShard>,
    controller: AtlasGraphController,
): String {
    val nodeId = controller.selectedNodeId
    val edgeId = controller.selectedEdgeId
    return when {
        projects.isEmpty() -> "No project loaded"
        nodeId != null ->
            projects.firstNotNullOfOrNull { project -> project.nodeInspectorContent(nodeId) }
                ?: "No graph selection|lens=${controller.lens.attributeValue}"

        edgeId != null ->
            projects.firstNotNullOfOrNull { project -> project.edgeInspectorContent(edgeId) }
                ?: "No graph selection|lens=${controller.lens.attributeValue}"
        else -> "No graph selection|lens=${controller.lens.attributeValue}"
    }
}

private fun ProjectGraphShard.nodeInspectorContent(nodeId: String): String? {
    val symbol = symbols.firstOrNull { item -> item.id == nodeId }
    val file = files.firstOrNull { item -> item.id == nodeId }
    return when {
        symbol != null -> "symbol|${symbol.id}|${symbol.qualifiedName}|${symbol.declarationSemantic.label}"
        file != null -> "file|${file.id}|${file.projectRelativePath}|${file.language.label}"
        else -> null
    }
}

private fun ProjectGraphShard.edgeInspectorContent(edgeId: String): String? =
    relationships
        .firstOrNull { item -> item.id == edgeId }
        ?.let { edge -> "edge|${edge.id}|${edge.kind.label}|${edge.resolutionEvidence.label}" }
