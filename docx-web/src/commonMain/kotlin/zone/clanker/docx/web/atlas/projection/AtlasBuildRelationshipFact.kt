package zone.clanker.docx.web.atlas.projection

import zone.clanker.report.model.RelationshipSnapshot

internal data class AtlasBuildRelationshipFact(
    val relationship: RelationshipSnapshot,
    val sourceId: String,
    val targetId: String,
    val isAnalysisCycleEdge: Boolean,
    val isObservedCycleEdge: Boolean,
)
