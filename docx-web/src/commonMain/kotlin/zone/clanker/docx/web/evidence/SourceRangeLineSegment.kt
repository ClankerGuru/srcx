package zone.clanker.docx.web.evidence

import zone.clanker.report.model.SourceRangeSnapshot

internal data class SourceRangeLineSegment(
    val line: Int,
    val startColumn: Int,
    val endColumnExclusive: Int,
) {
    init {
        require(line > 0) { "Source-range segment line must be positive" }
        require(startColumn >= 0) { "Source-range segment start column must not be negative" }
        require(endColumnExclusive > startColumn) { "Source-range segment end column must follow its start" }
    }
}

/** Projects captured UTF-16 offsets without normalizing line endings or changing string indexing. */
internal fun sourceRangeLineSegments(
    content: String,
    range: SourceRangeSnapshot,
): List<SourceRangeLineSegment> {
    if (range.endOffsetExclusive > content.length) return emptyList()
    val segments = mutableListOf<SourceRangeLineSegment>()
    var line = 1
    var lineStart = 0
    while (lineStart <= content.length) {
        val lineEnd = content.lineEndAfter(lineStart)
        val start = maxOf(range.startOffset, lineStart)
        val end = minOf(range.endOffsetExclusive, lineEnd)
        if (start < end) {
            segments += SourceRangeLineSegment(line, start - lineStart, end - lineStart)
        }
        if (lineEnd == content.length) break
        lineStart = content.nextLineStart(lineEnd)
        line += 1
    }
    return segments
}

private fun String.lineEndAfter(start: Int): Int {
    var index = start
    while (index < length && this[index] != '\n' && this[index] != '\r') index += 1
    return index
}

private fun String.nextLineStart(lineEnd: Int): Int =
    when {
        this[lineEnd] == '\r' && getOrNull(lineEnd + 1) == '\n' -> lineEnd + 2
        else -> lineEnd + 1
    }
