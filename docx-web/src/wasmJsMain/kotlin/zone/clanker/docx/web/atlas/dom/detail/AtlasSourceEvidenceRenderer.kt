package zone.clanker.docx.web.atlas.dom.detail

import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import zone.clanker.docx.web.atlas.dom.htmlElement
import zone.clanker.docx.web.evidence.SourceRangeLineSegment
import zone.clanker.docx.web.evidence.sourceRangeLineSegments
import zone.clanker.report.model.ProjectGraphShard
import zone.clanker.report.model.SourceFileSnapshot
import zone.clanker.report.model.SourceRangeSnapshot
import zone.clanker.report.model.SymbolSnapshot
import kotlin.js.ExperimentalWasmJsInterop

internal data class SourceEvidenceSpec(
    val file: SourceFileSnapshot,
    val content: String? = file.content,
    val unavailableMessage: String = "Source text was not included for this bounded file.",
    val declarationLines: Set<Int> = emptySet(),
    val declarationRanges: List<SourceRangeSnapshot> = emptyList(),
    val activeLine: Int? = null,
    val activeRange: SourceRangeSnapshot? = null,
    val activeKind: SourceEvidenceKind? = null,
    val relationshipLineCounts: Map<Int, Int> = emptyMap(),
)

internal enum class SourceEvidenceKind {
    DECLARATION,
    RELATIONSHIP,
    FINDING,
}

internal fun appendSourceEvidence(
    parent: HTMLElement,
    spec: SourceEvidenceSpec,
) {
    val section = htmlElement("section", "srcx-dashboard__architecture-detail-section")
    section.classList.add("srcx-dashboard__architecture-source-section")
    section.appendChild(htmlElement("h4", text = "Complete source file"))
    section.appendChild(
        htmlElement(
            "p",
            "srcx-dashboard__architecture-source-identity",
            spec.file.projectRelativePath,
        ),
    )
    val content = spec.content
    if (content == null) {
        section.appendChild(
            htmlElement(
                "p",
                "srcx-dashboard__architecture-muted",
                spec.unavailableMessage,
            ),
        )
        parent.appendChild(section)
        return
    }
    section.appendChild(sourceEvidenceKey(spec))
    val viewer = sourceViewer(spec.file, content, spec)
    section.appendChild(viewer)
    parent.appendChild(section)
    centerActiveSourceLine(viewer)
}

private fun sourceEvidenceKey(spec: SourceEvidenceSpec): HTMLElement {
    val exact = if (spec.activeRange == null) "line" else "captured UTF-16 range beginning at line"
    val text =
        when (spec.activeKind) {
            SourceEvidenceKind.RELATIONSHIP ->
                "The selected relationship occurrence is highlighted at $exact ${spec.activeLine}."
            SourceEvidenceKind.DECLARATION ->
                "The selected declaration is highlighted across its $exact ${spec.activeLine}."
            SourceEvidenceKind.FINDING ->
                "The selected finding is highlighted at line ${spec.activeLine}."
            null -> "Declaration lines are marked in the complete serialized source file."
        }
    return htmlElement("p", "srcx-dashboard__architecture-source-key", text)
}

private fun sourceViewer(
    file: SourceFileSnapshot,
    content: String,
    spec: SourceEvidenceSpec,
): HTMLElement {
    val viewer = htmlElement("div", "srcx-dashboard__architecture-source-viewer")
    viewer.setAttribute("role", "region")
    viewer.setAttribute("tabindex", "0")
    viewer.setAttribute("aria-label", "Complete source for ${file.projectRelativePath}")
    val activeSegments = spec.activeRange?.let { range -> sourceRangeLineSegments(content, range) }.orEmpty()
    val declarationRangeLines =
        spec.declarationRanges
            .flatMap { range -> sourceRangeLineSegments(content, range) }
            .mapTo(mutableSetOf(), SourceRangeLineSegment::line)
    normalizedSourceLines(content).forEachIndexed { index, line ->
        viewer.appendChild(
            sourceLine(
                lineNumber = index + 1,
                line = line,
                spec = spec,
                activeSegment = activeSegments.singleOrNull { segment -> segment.line == index + 1 },
                declarationRangeLines = declarationRangeLines,
            ),
        )
    }
    return viewer
}

private fun sourceLine(
    lineNumber: Int,
    line: String,
    spec: SourceEvidenceSpec,
    activeSegment: SourceRangeLineSegment?,
    declarationRangeLines: Set<Int>,
): HTMLElement {
    val row = htmlElement("div", "srcx-dashboard__architecture-source-line")
    row.setAttribute("data-srcx-source-line", lineNumber.toString())
    row.classList.toggle(
        "is-declaration-line",
        lineNumber in spec.declarationLines || lineNumber in declarationRangeLines,
    )
    row.classList.toggle("is-relationship-line", lineNumber in spec.relationshipLineCounts)
    if (activeSegment != null || lineNumber == spec.activeLine) {
        row.classList.add("is-active-line")
        row.classList.add(spec.activeKind.activeClass())
        row.setAttribute("aria-current", "true")
    }
    row.appendChild(
        htmlElement(
            "span",
            "srcx-dashboard__architecture-source-line-number",
            lineNumber.toString(),
        ).also { number -> number.setAttribute("aria-hidden", "true") },
    )
    row.appendChild(sourceCode(line, activeSegment))
    spec.relationshipLineCounts[lineNumber]?.let { count ->
        row.appendChild(
            htmlElement(
                "span",
                "srcx-dashboard__architecture-source-record-count",
                "×$count",
            ).also { badge ->
                badge.setAttribute(
                    "aria-label",
                    "$count relationship ${if (count == 1) "record" else "records"} on line $lineNumber",
                )
            },
        )
    }
    return row
}

private fun sourceCode(
    line: String,
    activeSegment: SourceRangeLineSegment?,
): HTMLElement {
    val code = htmlElement("code")
    if (activeSegment == null || activeSegment.endColumnExclusive > line.length) {
        code.textContent = line
        return code
    }
    code.appendChild(document.createTextNode(line.substring(0, activeSegment.startColumn)))
    code.appendChild(
        htmlElement(
            "span",
            "srcx-dashboard__architecture-source-active-range",
            line.substring(activeSegment.startColumn, activeSegment.endColumnExclusive),
        ).also { range ->
            range.setAttribute("data-srcx-range-start", activeSegment.startColumn.toString())
            range.setAttribute("data-srcx-range-end", activeSegment.endColumnExclusive.toString())
        },
    )
    code.appendChild(document.createTextNode(line.substring(activeSegment.endColumnExclusive)))
    return code
}

private fun SourceEvidenceKind?.activeClass(): String =
    when (this) {
        SourceEvidenceKind.DECLARATION -> "is-active-declaration"
        SourceEvidenceKind.RELATIONSHIP -> "is-active-relationship"
        SourceEvidenceKind.FINDING -> "is-active-finding"
        null -> "is-active-line"
    }

private fun normalizedSourceLines(content: String): List<String> =
    content
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .split('\n')

@OptIn(ExperimentalWasmJsInterop::class)
@Suppress("UnusedParameter")
private fun centerActiveSourceLine(viewer: HTMLElement): Unit =
    js(
        """{
            requestAnimationFrame(() => {
                const active = viewer.querySelector('[aria-current="true"]');
                const drawer = viewer.closest('[data-srcx-detail]');
                if (!active || !drawer) return;
                const activeRect = active.getBoundingClientRect();
                const drawerRect = drawer.getBoundingClientRect();
                const centeredOffset = activeRect.top - drawerRect.top -
                    (drawer.clientHeight - activeRect.height) / 2;
                drawer.scrollTop = Math.max(0, drawer.scrollTop + centeredOffset);
            });
        }""",
    )

internal fun declarationLines(
    project: ProjectGraphShard,
    fileId: String,
): Set<Int> =
    project.symbols
        .asSequence()
        .filter { symbol -> symbol.fileId == fileId }
        .mapTo(mutableSetOf(), SymbolSnapshot::declarationLine)

internal fun declarationRanges(
    project: ProjectGraphShard,
    fileId: String,
): List<SourceRangeSnapshot> =
    project.symbols
        .asSequence()
        .filter { symbol -> symbol.fileId == fileId }
        .mapNotNull(SymbolSnapshot::declarationRange)
        .sortedBy(SourceRangeSnapshot::startOffset)
        .toList()
