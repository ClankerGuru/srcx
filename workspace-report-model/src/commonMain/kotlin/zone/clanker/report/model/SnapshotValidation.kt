package zone.clanker.report.model

internal fun requireValidId(
    id: String,
    label: String,
) {
    require(id.isNotBlank()) { "$label ID must not be blank" }
}

internal fun requireUniqueAndSorted(
    ids: List<String>,
    label: String,
) {
    require(ids.distinct().size == ids.size) { "$label must use unique IDs" }
    require(ids == ids.sorted()) { "$label must use deterministic ID order" }
}

internal fun requireDistinctAndSorted(
    values: List<String>,
    label: String,
) {
    require(values == values.distinct().sorted()) { "$label must be distinct and deterministic" }
}

internal fun requireNormalizedRelativePath(
    path: String,
    label: String,
    allowRoot: Boolean = false,
) {
    require(path.isNotBlank()) { "$label must not be blank" }
    if (allowRoot && path == ".") return
    require(!path.startsWith('/') && '\\' !in path) { "$label must be relative and normalized" }
    require(path.split('/').none { it.isEmpty() || it == "." || it == ".." }) {
        "$label must not contain empty, current, or parent segments"
    }
}

internal fun requireNormalizedBuildPath(path: String) {
    require(path.isNotBlank()) { "Build relative path must not be blank" }
    if (path == ".") return
    require(!path.startsWith('/') && '\\' !in path) { "Build path must be relative and normalized" }
    require(path.split('/').none { it.isEmpty() || it == "." }) {
        "Build path must not contain empty or current segments"
    }
}

internal fun requireGradleProjectPath(
    path: String,
    label: String,
) {
    require(path.startsWith(':')) { "$label must start with ':'" }
    require("::" !in path && path.none(Char::isWhitespace)) { "$label must be a normalized Gradle project path" }
}

internal fun requireOptionalLine(
    line: Int?,
    label: String,
) {
    require(line == null || line > 0) { "$label must be positive when present" }
}

internal fun requireLineWithinContent(
    line: Int,
    content: String?,
    label: String,
) {
    require(line > 0) { "$label must be positive" }
    if (content != null) {
        require(line <= content.sourceLineCount()) { "$label must identify a line in its source file" }
    }
}

internal fun requireRangeWithinContent(
    range: SourceRangeSnapshot,
    line: Int,
    content: String?,
    label: String,
) {
    if (content == null) return
    require(range.endOffsetExclusive <= content.length) { "$label must fit inside its source file" }
    val startLine = content.sourceLineAt(range.startOffset)
    val endLine = content.sourceLineAt(range.endOffsetExclusive - 1)
    require(line in startLine..endLine) {
        "$label must contain its compatibility line"
    }
}

private fun String.sourceLineAt(offset: Int): Int {
    require(offset in indices) { "Source offset must identify retained content" }
    return take(offset).count { character -> character == '\n' } + 1
}

internal fun String.sourceLineCount(): Int {
    if (isEmpty()) return 0
    val terminalSeparator = endsWith('\n') || endsWith('\r')
    return lineSequence().count() - if (terminalSeparator) 1 else 0
}

internal fun requireClosedRoute(
    values: List<String>,
    label: String,
) {
    require(values.size >= MINIMUM_CLOSED_ROUTE_SIZE && values.first() == values.last()) {
        "$label must contain at least two members and return to its first member"
    }
    require(values.dropLast(1).all(String::isNotBlank)) { "$label must not contain blank members" }
    require(values.dropLast(1).distinct().size == values.size - 1) {
        "$label must not repeat a member before closing"
    }
}

private const val MINIMUM_CLOSED_ROUTE_SIZE = 3
