package zone.clanker.docx.web.site

import kotlinx.coroutines.test.runTest
import zone.clanker.docx.web.fixture.DocxWebFixture
import zone.clanker.report.model.WorkspaceSearchCatalog
import zone.clanker.report.model.WorkspaceSearchCatalogReference
import zone.clanker.report.model.WorkspaceSearchEntry
import zone.clanker.report.model.WorkspaceSearchJson
import zone.clanker.report.model.WorkspaceSearchKind
import zone.clanker.report.model.WorkspaceSearchShard
import zone.clanker.report.model.WorkspaceSearchShardReference
import zone.clanker.report.model.WorkspaceSearchTarget
import zone.clanker.report.model.workspaceSearchEntryComparator
import zone.clanker.report.model.workspaceSearchShardReferenceComparator
import zone.clanker.report.model.workspaceSearchTerms
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkspaceStaticSearchLoaderTest {
    @Test
    fun loadsTheGenerationCatalogWithoutFollowingAnyProjectShard() =
        runTest {
            val fixture = staticSearchFixture()
            val requestedPaths = mutableListOf<String>()
            val loader = WorkspaceSiteLoader { path -> fixture.content.getValue(path).also { requestedPaths += path } }

            val loaded = loader.loadSearchCatalog(fixture.site)

            assertEquals(fixture.catalog, loaded?.catalog)
            assertEquals(listOf(requireNotNull(fixture.site.manifest.searchCatalog).file), requestedPaths)
            assertTrue(requestedPaths.none { path -> "/shards/project-" in path })
        }

    @Test
    fun appliesTypedCategoryPrefixesAndLoadsOnlyTheRoutedShard() =
        runTest {
            val fixture = staticSearchFixture()
            val requestedPaths = mutableListOf<String>()
            val loader = WorkspaceSiteLoader { path -> fixture.content.getValue(path).also { requestedPaths += path } }

            val classResults = loader.search(fixture.site, fixture.catalog, "class:atlas")

            assertEquals(
                listOf(WorkspaceSearchKind.CLASS, WorkspaceSearchKind.INTERFACE),
                classResults.groups.map { it.kind },
            )
            assertEquals(1, classResults.loadedShardCount)
            assertEquals(listOf(fixture.reference("at").file), requestedPaths)
            requestedPaths.clear()

            val buildResults = loader.search(fixture.site, fixture.catalog, "build:atlas")
            assertEquals(listOf(WorkspaceSearchKind.BUILD), buildResults.groups.map { it.kind })
            requestedPaths.clear()

            val projectResults = loader.search(fixture.site, fixture.catalog, "project:atlas")
            assertEquals(listOf(WorkspaceSearchKind.PROJECT), projectResults.groups.map { it.kind })
            requestedPaths.clear()

            val sourceResults = loader.search(fixture.site, fixture.catalog, "source:atlas")
            assertEquals(listOf(WorkspaceSearchKind.SOURCE_SET), sourceResults.groups.map { it.kind })
            requestedPaths.clear()

            val packageResults = loader.search(fixture.site, fixture.catalog, "package:atlas")
            assertEquals(listOf(WorkspaceSearchKind.PACKAGE), packageResults.groups.map { it.kind })
            requestedPaths.clear()

            val fileResults = loader.search(fixture.site, fixture.catalog, "file:atlas")
            assertEquals(listOf(WorkspaceSearchKind.FILE), fileResults.groups.map { it.kind })
            requestedPaths.clear()

            val symbolResults = loader.search(fixture.site, fixture.catalog, "symbol:atlas")
            assertEquals(
                listOf(WorkspaceSearchKind.CLASS, WorkspaceSearchKind.INTERFACE),
                symbolResults.groups.map { it.kind },
            )
            requestedPaths.clear()

            val problemResults = loader.search(fixture.site, fixture.catalog, "problem:atlas")
            assertEquals(listOf(WorkspaceSearchKind.FINDING), problemResults.groups.map { it.kind })
            requestedPaths.clear()

            val cycleResults = loader.search(fixture.site, fixture.catalog, "cycle:atlas")
            assertEquals(listOf(WorkspaceSearchKind.CYCLE), cycleResults.groups.map { it.kind })
            requestedPaths.clear()

            val methodResults = loader.search(fixture.site, fixture.catalog, "method:render")
            assertEquals(listOf(WorkspaceSearchKind.FUNCTION), methodResults.groups.map { it.kind })
            assertEquals(listOf(fixture.reference("re").file), requestedPaths)
            requestedPaths.clear()

            val extensionResults = loader.search(fixture.site, fixture.catalog, "ext:kt")
            assertEquals(listOf(WorkspaceSearchKind.FILE), extensionResults.groups.map { it.kind })
            assertEquals(listOf(fixture.reference("kt").file), requestedPaths)
            assertTrue(requestedPaths.none { path -> "/shards/project-" in path })
        }

    @Test
    fun capsShardLoadsAndReportsTheUnsearchedTail() =
        runTest {
            val fixture = boundedSearchFixture()
            val requestedPaths = mutableListOf<String>()
            val loader = WorkspaceSiteLoader { path -> fixture.content.getValue(path).also { requestedPaths += path } }

            val results = loader.search(fixture.site, fixture.catalog, "bounded")

            assertEquals(WorkspaceSearchCatalog.MAX_SHARD_LOADS, results.loadedShardCount)
            assertEquals(WorkspaceSearchCatalog.MAX_SHARD_LOADS, requestedPaths.size)
            assertEquals(WorkspaceSearchCatalog.MAX_SHARD_LOADS, results.loadedMatchCount)
            assertTrue(results.truncated)
            assertTrue(
                requestedPaths.none { path ->
                    path in
                        fixture.site.manifest.projectShards
                            .map { it.file }
                },
            )
        }

    @Test
    fun aBlankQueryPerformsNoShardReads() =
        runTest {
            val fixture = staticSearchFixture()
            val loader = WorkspaceSiteLoader { path -> error("Unexpected search read: $path") }

            val results = loader.search(fixture.site, fixture.catalog, "  ")

            assertTrue(results.groups.isEmpty())
            assertEquals(0, results.loadedShardCount)
            assertFalse(results.truncated)
        }
}

private data class StaticSearchFixture(
    val site: LoadedWorkspaceSite,
    val catalog: WorkspaceSearchCatalog,
    val content: Map<String, String>,
) {
    fun reference(prefix: String): WorkspaceSearchShardReference =
        catalog.shards.single { reference -> reference.prefix == prefix }
}

private fun staticSearchFixture(): StaticSearchFixture {
    val atlasFile = entry("file:atlas", WorkspaceSearchKind.FILE, "AtlasController.kt", "kt")
    val shards =
        listOf(
            shard(
                prefix = "at",
                pageIndex = 0,
                entries =
                    listOf(
                        atlasFile,
                        entry("build:atlas", WorkspaceSearchKind.BUILD, "Atlas Build"),
                        entry("project:atlas", WorkspaceSearchKind.PROJECT, ":atlas"),
                        entry("source:atlas", WorkspaceSearchKind.SOURCE_SET, "Atlas main"),
                        entry("package:atlas", WorkspaceSearchKind.PACKAGE, "atlas.fixture"),
                        entry("symbol:atlas-class", WorkspaceSearchKind.CLASS, "AtlasController"),
                        entry("symbol:atlas-port", WorkspaceSearchKind.INTERFACE, "AtlasPort"),
                        entry("finding:atlas", WorkspaceSearchKind.FINDING, "Atlas problem"),
                        entry("cycle:atlas", WorkspaceSearchKind.CYCLE, "Atlas cycle"),
                    ),
                hashDigit = 'a',
            ),
            shard("kt", 0, listOf(atlasFile), 'b'),
            shard(
                "re",
                0,
                listOf(entry("symbol:render", WorkspaceSearchKind.FUNCTION, "renderAtlas")),
                'c',
            ),
        )
    return fixture(shards)
}

private fun boundedSearchFixture(): StaticSearchFixture =
    fixture(
        List(WorkspaceSearchCatalog.MAX_SHARD_LOADS + 2) { page ->
            shard(
                prefix = "bo",
                pageIndex = page,
                entries = listOf(entry("symbol:bounded-$page", WorkspaceSearchKind.CLASS, "Bounded$page")),
                hashDigit = "012345"[page],
            )
        },
    )

private fun fixture(shards: List<EncodedSearchShard>): StaticSearchFixture {
    val references = shards.map(EncodedSearchShard::reference).sortedWith(workspaceSearchShardReferenceComparator())
    val catalog =
        WorkspaceSearchCatalog(
            generationId = DocxWebFixture.manifest.generationId,
            workspaceId = DocxWebFixture.summary.workspace.id,
            entryCount = shards.flatMap { shard -> shard.shard.entries }.distinctBy(WorkspaceSearchEntry::key).size,
            shards = references,
        )
    val encodedCatalog = WorkspaceSearchJson.encodeCatalog(catalog)
    val catalogHash = "f".repeat(HASH_LENGTH)
    val catalogReference =
        WorkspaceSearchCatalogReference(
            file = WorkspaceSearchCatalogReference.path(catalogHash),
            contentHash = catalogHash,
            encodedByteSize = encodedCatalog.encodeToByteArray().size.toLong(),
        )
    val site =
        LoadedWorkspaceSite(
            manifest = DocxWebFixture.manifest.copy(searchCatalog = catalogReference),
            summary = DocxWebFixture.summary,
            dashboard = DocxWebFixture.dashboard,
            atlasOverview = DocxWebFixture.atlasOverview,
            atlasOverviews = DocxWebFixture.atlasOverviews,
        )
    val content =
        buildMap {
            put(catalogReference.file, encodedCatalog)
            shards.forEach { encoded -> put(encoded.reference.file, encoded.content) }
        }
    return StaticSearchFixture(site, catalog, content)
}

private data class EncodedSearchShard(
    val shard: WorkspaceSearchShard,
    val reference: WorkspaceSearchShardReference,
    val content: String,
)

private fun shard(
    prefix: String,
    pageIndex: Int,
    entries: List<WorkspaceSearchEntry>,
    hashDigit: Char,
): EncodedSearchShard {
    val shard = WorkspaceSearchShard(prefix, pageIndex, entries.sortedWith(workspaceSearchEntryComparator()))
    val content = WorkspaceSearchJson.encodeShard(shard)
    val hash = hashDigit.toString().repeat(HASH_LENGTH)
    return EncodedSearchShard(
        shard = shard,
        reference =
            WorkspaceSearchShardReference(
                prefix = prefix,
                pageIndex = pageIndex,
                file = WorkspaceSearchShardReference.path(hash),
                contentHash = hash,
                encodedByteSize = content.encodeToByteArray().size.toLong(),
                entryCount = entries.size,
            ),
        content = content,
    )
}

private fun entry(
    id: String,
    kind: WorkspaceSearchKind,
    label: String,
    extension: String? = null,
): WorkspaceSearchEntry =
    WorkspaceSearchEntry(
        id = id,
        kind = kind,
        label = label,
        detail = "Fixture search entry",
        terms = workspaceSearchTerms(label, extension.orEmpty()),
        target = WorkspaceSearchTarget(extension = extension),
    )

private const val HASH_LENGTH: Int = 64
