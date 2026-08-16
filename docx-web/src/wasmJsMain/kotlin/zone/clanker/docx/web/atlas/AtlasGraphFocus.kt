package zone.clanker.docx.web.atlas

import zone.clanker.report.model.ProjectGraphShard

internal interface AtlasGraphFocus {
    fun focusNode(
        project: ProjectGraphShard,
        symbolId: String,
    )

    fun focusFile(
        project: ProjectGraphShard,
        fileId: String,
    )

    fun focusCurrentLensNode(nodeId: String)

    fun beginFindingEvidence(findingId: String)

    fun focusFindingEvidence(
        findingId: String,
        nodeId: String,
    )

    fun findingEvidenceUnavailable(
        findingId: String,
        message: String,
    )

    fun focusEdge(
        project: ProjectGraphShard,
        relationshipId: String,
    )
}

internal class AtlasGraphFocusController(
    private val filters: AtlasGraphFilterController,
    private val selection: AtlasGraphSelectionState,
) : AtlasGraphFocus {
    override fun focusNode(
        project: ProjectGraphShard,
        symbolId: String,
    ) {
        if (project.symbols.none { symbol -> symbol.id == symbolId }) return
        focusNode(GraphLens.SYMBOLS, symbolId)
    }

    override fun focusFile(
        project: ProjectGraphShard,
        fileId: String,
    ) {
        if (project.files.none { file -> file.id == fileId }) return
        focusNode(GraphLens.FILES, fileId)
    }

    override fun focusCurrentLensNode(nodeId: String) {
        selection.selectNode(nodeId)
    }

    override fun beginFindingEvidence(findingId: String) {
        selection.beginEvidence(findingId)
    }

    override fun focusFindingEvidence(
        findingId: String,
        nodeId: String,
    ) {
        selection.focusEvidence(findingId, nodeId)
    }

    override fun findingEvidenceUnavailable(
        findingId: String,
        message: String,
    ) {
        selection.evidenceUnavailable(findingId, message)
    }

    override fun focusEdge(
        project: ProjectGraphShard,
        relationshipId: String,
    ) {
        val relationship = project.relationships.firstOrNull { edge -> edge.id == relationshipId } ?: return
        filters.focusScope(GraphLens.FILES.takeIf { relationship.sourceSymbolId == null })
        selection.selectEdge(relationshipId)
    }

    private fun focusNode(
        lens: GraphLens,
        nodeId: String,
    ) {
        filters.focusScope(lens)
        selection.selectNode(nodeId)
    }
}
