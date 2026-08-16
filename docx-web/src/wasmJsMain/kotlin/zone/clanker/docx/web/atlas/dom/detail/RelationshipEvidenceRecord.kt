package zone.clanker.docx.web.atlas.dom.detail

import zone.clanker.report.model.ReferenceSnapshot
import zone.clanker.report.model.RelationshipSnapshot
import zone.clanker.report.model.SourceFileSnapshot
import zone.clanker.report.model.SourceRangeSnapshot
import zone.clanker.report.model.SymbolSnapshot

internal data class RelationshipEvidenceRecord(
    val projectId: String,
    val relationship: RelationshipSnapshot,
    val reference: ReferenceSnapshot,
    val sourceFile: SourceFileSnapshot,
    val target: SymbolSnapshot,
    val targetFile: SourceFileSnapshot,
    val declarationLines: Set<Int>,
    val declarationRanges: List<SourceRangeSnapshot>,
)
