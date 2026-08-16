package zone.clanker.docx.web.state

import zone.clanker.report.model.WorkspaceSearchEntry
import zone.clanker.report.model.WorkspaceSearchKind
import zone.clanker.report.model.workspaceSearchTerms
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkspaceSearchVirtualWindowTest {
    @Test
    fun mountsFiveVisibleRowsWithAtMostOneOverscanRowOnEachSide() {
        val entries = (0 until 10_000).map(::entry)

        val first = workspaceSearchVirtualWindow(entries, firstVisibleIndex = 0)
        val middle = workspaceSearchVirtualWindow(entries, firstVisibleIndex = 5_000)
        val last = workspaceSearchVirtualWindow(entries, firstVisibleIndex = entries.lastIndex)

        assertEquals(6, first.entries.size)
        assertEquals(7, middle.entries.size)
        assertEquals(2, last.entries.size)
        assertTrue(listOf(first, middle, last).all { window -> window.entries.size <= 7 })
        assertEquals(4_999, middle.firstIndex)
        assertEquals(5_006, middle.lastIndexExclusive)
    }

    @Test
    fun keyboardActivationMovesTheWindowWithoutMountingTheCompleteResultSet() {
        val entries = (0 until 100).map(::entry)

        val window = workspaceSearchVirtualWindowContaining(entries, entries[73].key)

        assertTrue(entries[73] in window.entries)
        assertTrue(window.entries.size <= 7)
    }
}

private fun entry(index: Int): WorkspaceSearchEntry =
    WorkspaceSearchEntry(
        id = "symbol:$index",
        kind = WorkspaceSearchKind.CLASS,
        label = "Class$index",
        detail = "Fixture class $index",
        terms = workspaceSearchTerms("Class$index"),
    )
