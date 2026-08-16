package zone.clanker.docx.web.state

import zone.clanker.report.model.WorkspaceSearchCatalog
import zone.clanker.report.model.WorkspaceSearchCategory
import zone.clanker.report.model.WorkspaceSearchEntry
import zone.clanker.report.model.WorkspaceSearchKind
import zone.clanker.report.model.WorkspaceSearchResultGroup
import zone.clanker.report.model.WorkspaceSearchResults
import zone.clanker.report.model.workspaceSearchTerms
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame

class WorkspaceGlobalSearchStateTest {
    @Test
    fun acceptsOnlyTheCurrentGenerationAndQueryRevision() {
        val initial = WorkspaceGlobalSearchState().attachGeneration("generation-a").updateQuery("class:atlas")
        val request = requireNotNull(initial.pendingRequest())
        val staleCompletion = success(request, classResults(request.query))
        val newer = initial.updateQuery("method:render")

        assertEquals(newer, newer.accept(staleCompletion))

        val accepted = initial.accept(staleCompletion)
        assertFalse(accepted.queryState.loading)
        assertEquals("CLASS:symbol:atlas", accepted.activeResultKey)
        assertEquals(catalog("generation-a"), accepted.catalog)

        val nextGeneration = accepted.attachGeneration("generation-b")
        assertEquals("generation-b", nextGeneration.queryState.generationId)
        assertEquals("", nextGeneration.queryState.query)
        assertNull(nextGeneration.catalog)
        assertNull(nextGeneration.results)
    }

    @Test
    fun blankQueriesCancelTheTransactionWithoutCreatingAReadRequest() {
        val searching = WorkspaceGlobalSearchState().attachGeneration("generation-a").updateQuery("file:atlas")
        val blank = searching.updateQuery("   ")

        assertFalse(blank.queryState.loading)
        assertNull(blank.pendingRequest())
        assertNull(blank.results)
    }

    @Test
    fun keyboardFocusRemainsDeterministic() {
        val first = entry("symbol:alpha", "Alpha")
        val second = entry("symbol:beta", "Beta")
        val requested = WorkspaceGlobalSearchState().attachGeneration("generation-a").updateQuery("class:a")
        val loaded =
            requested.accept(
                success(
                    requireNotNull(requested.pendingRequest()),
                    classResults(requested.queryState.query, first, second),
                ),
            )

        assertEquals(first.key, loaded.activeResultKey)
        assertSame(loaded, loaded.activate(first.key))
        assertEquals(second.key, loaded.moveActive(1).activeResultKey)
        assertEquals(second.key, loaded.moveActive(-1).activeResultKey)
        assertEquals(first.key, loaded.activate(second.key).moveActive(1).activeResultKey)
    }
}

private fun success(
    request: WorkspaceGlobalSearchRequest,
    results: WorkspaceSearchResults,
): WorkspaceGlobalSearchCompletion.Success =
    WorkspaceGlobalSearchCompletion.Success(
        request = request,
        catalog = catalog(request.generationId),
        results = results,
    )

private fun catalog(generationId: String): WorkspaceSearchCatalog =
    WorkspaceSearchCatalog(
        generationId = generationId,
        workspaceId = "workspace:test",
        entryCount = 2,
        shards = emptyList(),
    )

private fun classResults(
    query: String,
    vararg entries: WorkspaceSearchEntry,
): WorkspaceSearchResults {
    val resolvedEntries = entries.toList().ifEmpty { listOf(entry("symbol:atlas", "Atlas")) }
    return WorkspaceSearchResults(
        query = query.trim(),
        category = WorkspaceSearchCategory.CLASS,
        groups = listOf(WorkspaceSearchResultGroup(WorkspaceSearchKind.CLASS, resolvedEntries)),
        loadedShardCount = 1,
        loadedCandidateCount = resolvedEntries.size,
        loadedMatchCount = resolvedEntries.size,
        truncated = false,
    )
}

private fun entry(
    id: String,
    label: String,
): WorkspaceSearchEntry =
    WorkspaceSearchEntry(
        id = id,
        kind = WorkspaceSearchKind.CLASS,
        label = label,
        detail = "Fixture class",
        terms = workspaceSearchTerms(label),
    )
