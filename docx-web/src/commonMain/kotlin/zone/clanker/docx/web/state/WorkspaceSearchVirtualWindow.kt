package zone.clanker.docx.web.state

import zone.clanker.report.model.WorkspaceSearchEntry

internal data class WorkspaceSearchVirtualWindow(
    val firstIndex: Int,
    val entries: List<WorkspaceSearchEntry>,
) {
    val lastIndexExclusive: Int
        get() = firstIndex + entries.size
}

internal fun workspaceSearchVirtualWindow(
    entries: List<WorkspaceSearchEntry>,
    firstVisibleIndex: Int,
    visibleRows: Int = DEFAULT_VISIBLE_SEARCH_ROWS,
    overscanRows: Int = DEFAULT_SEARCH_OVERSCAN_ROWS,
): WorkspaceSearchVirtualWindow {
    require(visibleRows > 0) { "Visible search rows must be positive" }
    require(overscanRows >= 0) { "Search overscan rows must not be negative" }
    if (entries.isEmpty()) return WorkspaceSearchVirtualWindow(0, emptyList())
    val visibleStart = firstVisibleIndex.coerceIn(0, entries.lastIndex)
    val first = (visibleStart - overscanRows).coerceAtLeast(0)
    val last = (visibleStart + visibleRows + overscanRows).coerceAtMost(entries.size)
    return WorkspaceSearchVirtualWindow(first, entries.subList(first, last))
}

internal fun workspaceSearchVirtualWindowContaining(
    entries: List<WorkspaceSearchEntry>,
    activeResultKey: String?,
    visibleRows: Int = DEFAULT_VISIBLE_SEARCH_ROWS,
    overscanRows: Int = DEFAULT_SEARCH_OVERSCAN_ROWS,
): WorkspaceSearchVirtualWindow {
    val activeIndex = entries.indexOfFirst { entry -> entry.key == activeResultKey }.coerceAtLeast(0)
    val centeredStart = (activeIndex - visibleRows / 2).coerceAtLeast(0)
    return workspaceSearchVirtualWindow(entries, centeredStart, visibleRows, overscanRows)
}

internal const val DEFAULT_VISIBLE_SEARCH_ROWS: Int = 5
internal const val DEFAULT_SEARCH_OVERSCAN_ROWS: Int = 1
