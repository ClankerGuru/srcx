package zone.clanker.docx.web.atlas.dom.detail

internal data class AtlasPagedEvidenceActions(
    val onPrevious: () -> Unit,
    val onNext: () -> Unit,
    val onSelect: (Int) -> Unit,
)
