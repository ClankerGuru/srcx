package zone.clanker.docx.web.atlas.dom.detail

import org.w3c.dom.HTMLElement
import zone.clanker.report.model.SourceFileSnapshot
import zone.clanker.report.model.SourceRangeSnapshot
import zone.clanker.report.model.WorkspaceDeclarationEvidencePage

/** Exact declaration renderer used when the generation-pinned evidence gateway has answered. */
internal data class AtlasDeclarationEvidenceRenderer(
    val page: WorkspaceDeclarationEvidencePage,
    val file: SourceFileSnapshot?,
    val content: String?,
    val fileDeclarationRanges: List<SourceRangeSnapshot>,
    val unavailableMessage: String = "The declaration's source text is unavailable.",
)

internal fun appendDeclarationEvidence(
    parent: HTMLElement,
    renderer: AtlasDeclarationEvidenceRenderer,
) {
    val page = renderer.page
    val declaration = page.declaration ?: return
    parent.appendChild(
        detailList(
            "Qualified name" to declaration.qualifiedName,
            "Kind" to declaration.kind.label,
            "Semantic" to declaration.semantic.label,
            "Owner symbol" to declaration.ownerSymbolId.orEmpty().ifBlank { "Top level" },
            "Signature" to declaration.signature.orEmpty().ifBlank { "Not captured" },
            "Source" to "${declaration.location.filePath}:${declaration.location.line}",
        ),
    )
    if (renderer.file != null) {
        appendSourceEvidence(
            parent,
            SourceEvidenceSpec(
                file = renderer.file,
                content = renderer.content,
                unavailableMessage = renderer.unavailableMessage,
                declarationRanges = renderer.fileDeclarationRanges,
                activeLine = declaration.location.line,
                activeRange = declaration.location.range,
                activeKind = SourceEvidenceKind.DECLARATION,
            ),
        )
    }
}
