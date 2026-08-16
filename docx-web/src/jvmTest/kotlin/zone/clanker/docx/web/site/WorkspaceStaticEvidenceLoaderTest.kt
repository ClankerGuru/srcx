package zone.clanker.docx.web.site

import kotlinx.coroutines.test.runTest
import zone.clanker.docx.web.fixture.DocxWebFixture
import zone.clanker.report.model.RelationshipEvidence
import zone.clanker.report.model.RelationshipKind
import zone.clanker.report.model.WorkspaceEvidenceJson
import zone.clanker.report.model.WorkspaceEvidenceLocation
import zone.clanker.report.model.WorkspaceEvidenceTarget
import zone.clanker.report.model.WorkspaceRelationshipOccurrence
import zone.clanker.report.model.WorkspaceRelationshipOccurrencePage
import zone.clanker.report.model.WorkspaceRelationshipOccurrenceRequest
import zone.clanker.report.model.WorkspaceRelationshipOccurrenceSelector
import zone.clanker.report.model.WorkspaceStaticEvidenceCatalog
import zone.clanker.report.model.WorkspaceStaticEvidenceCatalogReference
import zone.clanker.report.model.WorkspaceStaticEvidenceEntry
import zone.clanker.report.model.WorkspaceStaticEvidencePage
import zone.clanker.report.model.WorkspaceStaticEvidencePageReference
import zone.clanker.report.model.WorkspaceStaticEvidencePartition
import zone.clanker.report.model.WorkspaceStaticEvidencePartitionReference
import zone.clanker.report.model.WorkspaceUsageCategory
import zone.clanker.report.model.workspaceEvidenceRouteHash
import zone.clanker.report.model.workspaceRelationshipOccurrenceRouteKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WorkspaceStaticEvidenceLoaderTest {
    @Test
    fun pagesFromAnInteriorCursorWithoutScanningOrLoadingProjectShards() =
        runTest {
            val fixture = staticEvidenceFixture()
            val requestedPaths = mutableListOf<String>()
            val loader = WorkspaceSiteLoader { path -> fixture.content.getValue(path).also(requestedPaths::add) }
            val gateway = loader.staticEvidenceGateway(fixture.site)

            val first = gateway.relationshipOccurrences(fixture.request(limit = 1))
            val second = gateway.relationshipOccurrences(fixture.request(after = first.nextCursor, limit = 1))
            val third = gateway.relationshipOccurrences(fixture.request(after = second.nextCursor, limit = 1))
            val previous = gateway.relationshipOccurrences(fixture.request(before = third.previousCursor, limit = 1))

            assertEquals(listOf("relationship:1"), first.occurrences.map { occurrence -> occurrence.relationshipId })
            assertEquals(listOf("relationship:2"), second.occurrences.map { occurrence -> occurrence.relationshipId })
            assertEquals(listOf("relationship:3"), third.occurrences.map { occurrence -> occurrence.relationshipId })
            assertEquals(listOf("relationship:2"), previous.occurrences.map { occurrence -> occurrence.relationshipId })
            assertNotNull(first.nextCursor)
            assertNotNull(second.nextCursor)
            assertEquals(null, third.nextCursor)
            assertTrue(requestedPaths.none { path -> "/shards/" in path })
            assertEquals(requestedPaths.distinct(), requestedPaths)
        }

    @Test
    fun evictsArtifactsByEncodedBytesAndResetsTheCacheBetweenGenerations() =
        runTest {
            val first = staticEvidenceFixture()
            val second =
                staticEvidenceFixture(
                    generationId = "generation:other",
                    partitionHashDigit = "c",
                    catalogHashDigit = "d",
                    pageHashDigits = listOf("3", "4"),
                )
            val content = first.content + second.content
            val requestedPaths = mutableListOf<String>()
            val source =
                WorkspaceSiteTextSource { path ->
                    content.getValue(path).also { requestedPaths += path }
                }
            val largestArtifact =
                content
                    .filterKeys { path -> "/evidence/" in path && "catalog-" !in path }
                    .values
                    .maxOf { encoded -> encoded.encodeToByteArray().size.toLong() }
            val loader = WorkspaceStaticEvidenceLoader(source, largestArtifact)
            val firstGateway = StaticWorkspaceEvidenceGateway(first.site, loader)
            val secondGateway = StaticWorkspaceEvidenceGateway(second.site, loader)

            firstGateway.relationshipOccurrences(first.request(limit = 1))
            firstGateway.relationshipOccurrences(first.request(after = occurrence(2).cursor, limit = 1))
            firstGateway.relationshipOccurrences(first.request(limit = 1))
            secondGateway.relationshipOccurrences(second.request(limit = 1))
            firstGateway.relationshipOccurrences(first.request(limit = 1))

            val firstCatalog = requireNotNull(first.site.manifest.evidenceCatalog).file
            assertEquals(2, requestedPaths.count { path -> path == firstCatalog })
            val firstPage = first.content.keys.single { path -> "/pages/" in path && "1".repeat(HASH_LENGTH) in path }
            assertTrue(requestedPaths.count { path -> path == firstPage } >= 2)
        }
}

private data class StaticEvidenceFixture(
    val site: LoadedWorkspaceSite,
    val selector: WorkspaceRelationshipOccurrenceSelector,
    val content: Map<String, String>,
) {
    fun request(
        after: zone.clanker.report.model.WorkspaceEvidenceCursor? = null,
        before: zone.clanker.report.model.WorkspaceEvidenceCursor? = null,
        limit: Int,
    ): WorkspaceRelationshipOccurrenceRequest =
        WorkspaceRelationshipOccurrenceRequest(
            target = WorkspaceEvidenceTarget(site.summary.workspace.id, site.manifest.generationId),
            selector = selector,
            after = after,
            before = before,
            limit = limit,
        )
}

private fun staticEvidenceFixture(
    generationId: String = DocxWebFixture.manifest.generationId,
    partitionHashDigit: String = "a",
    catalogHashDigit: String = "b",
    pageHashDigits: List<String> = listOf("1", "2"),
): StaticEvidenceFixture {
    val target = WorkspaceEvidenceTarget(DocxWebFixture.summary.workspace.id, generationId)
    val selector = WorkspaceRelationshipOccurrenceSelector(RelationshipKind.CALL, "symbol:source", "symbol:target")
    val occurrences = (1..3).map(::occurrence)
    val entries =
        listOf(
            staticEntry(target, selector, occurrences.take(2), pageIndex = 0, totalCount = 3),
            staticEntry(target, selector, occurrences.drop(2), pageIndex = 1, totalCount = 3),
        )
    val encodedPages = entries.mapIndexed { index, entry -> encodedPage(entry, pageHashDigits[index]) }
    val prefix = workspaceEvidenceRouteHash(workspaceRelationshipOccurrenceRouteKey(selector)).take(2)
    val partition = WorkspaceStaticEvidencePartition(prefix, encodedPages.map(EncodedEvidencePage::reference))
    val encodedPartition = WorkspaceEvidenceJson.encodeStaticPartition(partition)
    val partitionReference =
        WorkspaceStaticEvidencePartitionReference(
            prefix = prefix,
            file = WorkspaceStaticEvidencePartitionReference.path(partitionHashDigit.repeat(HASH_LENGTH)),
            contentHash = partitionHashDigit.repeat(HASH_LENGTH),
            encodedByteSize = encodedPartition.encodeToByteArray().size.toLong(),
            routePageCount = 2,
        )
    val catalog = WorkspaceStaticEvidenceCatalog(target = target, partitions = listOf(partitionReference))
    val encodedCatalog = WorkspaceEvidenceJson.encodeStaticCatalog(catalog)
    val catalogReference =
        WorkspaceStaticEvidenceCatalogReference(
            file = WorkspaceStaticEvidenceCatalogReference.path(catalogHashDigit.repeat(HASH_LENGTH)),
            contentHash = catalogHashDigit.repeat(HASH_LENGTH),
            encodedByteSize = encodedCatalog.encodeToByteArray().size.toLong(),
        )
    val site =
        LoadedWorkspaceSite(
            manifest = DocxWebFixture.manifest.copy(generationId = generationId, evidenceCatalog = catalogReference),
            summary = DocxWebFixture.summary,
            dashboard = DocxWebFixture.dashboard,
            atlasOverview = DocxWebFixture.atlasOverview,
            atlasOverviews = DocxWebFixture.atlasOverviews,
        )
    val content =
        buildMap {
            put(catalogReference.file, encodedCatalog)
            put(partitionReference.file, encodedPartition)
            encodedPages.forEach { page -> put(page.reference.file, page.encoded) }
        }
    return StaticEvidenceFixture(site, selector, content)
}

private data class EncodedEvidencePage(
    val reference: WorkspaceStaticEvidencePageReference,
    val encoded: String,
)

private fun encodedPage(
    entry: WorkspaceStaticEvidenceEntry,
    hashDigit: String,
): EncodedEvidencePage {
    val page = WorkspaceStaticEvidencePage(listOf(entry))
    val encoded = WorkspaceEvidenceJson.encodeStaticPage(page)
    val hash = hashDigit.repeat(HASH_LENGTH)
    return EncodedEvidencePage(
        WorkspaceStaticEvidencePageReference(
            file = WorkspaceStaticEvidencePageReference.path(hash),
            contentHash = hash,
            encodedByteSize = encoded.encodeToByteArray().size.toLong(),
            routes = listOf(entry.locator()),
        ),
        encoded,
    )
}

private fun staticEntry(
    target: WorkspaceEvidenceTarget,
    selector: WorkspaceRelationshipOccurrenceSelector,
    occurrences: List<WorkspaceRelationshipOccurrence>,
    pageIndex: Int,
    totalCount: Long,
): WorkspaceStaticEvidenceEntry =
    WorkspaceStaticEvidenceEntry(
        routeKey = workspaceRelationshipOccurrenceRouteKey(selector),
        pageIndex = pageIndex,
        relationshipOccurrences =
            WorkspaceRelationshipOccurrencePage(
                target = target,
                selector = selector,
                totalCount = totalCount,
                occurrences = occurrences,
                previousCursor = occurrences.first().cursor.takeIf { pageIndex > 0 },
                nextCursor = occurrences.last().cursor.takeIf { pageIndex == 0 },
            ),
    )

private fun occurrence(index: Int): WorkspaceRelationshipOccurrence =
    WorkspaceRelationshipOccurrence(
        relationshipId = "relationship:$index",
        referenceId = "reference:$index",
        sourceSymbolId = "symbol:source",
        targetSymbolId = "symbol:target",
        kind = RelationshipKind.CALL,
        evidence = RelationshipEvidence.DIRECT,
        category = WorkspaceUsageCategory.CALLER,
        location =
            WorkspaceEvidenceLocation(
                buildId = "build:fixture",
                projectId = "project:fixture",
                sourceSetId = "source-set:fixture",
                sourceSetName = "main",
                fileId = "file:fixture",
                filePath = "src/main/kotlin/Fixture.kt",
                line = index,
            ),
        context = "target()",
    )

private const val HASH_LENGTH: Int = 64
