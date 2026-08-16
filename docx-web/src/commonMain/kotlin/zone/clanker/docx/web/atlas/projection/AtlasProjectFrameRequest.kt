package zone.clanker.docx.web.atlas.projection

import zone.clanker.report.model.AtlasLens
import zone.clanker.report.model.RelationshipKind
import zone.clanker.report.model.SymbolKind

data class AtlasProjectFrameRequest(
    val lens: AtlasLens,
    val query: String = "",
    val searchTargets: Set<AtlasSearchTarget> = emptySet(),
    val sourceSetIds: Set<String> = emptySet(),
    val symbolKinds: Set<SymbolKind> = SymbolKind.entries.toSet(),
    val allowedKinds: Set<RelationshipKind> = RelationshipKind.entries.toSet(),
    val relationshipCountFilter: AtlasRelationshipCountFilter? = null,
    val selectedNodeIds: List<String> = emptyList(),
    val selectedRelationshipId: String? = null,
)
