package zone.clanker.report.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class WorkspaceStaticEvidenceTest :
    FunSpec({
        test("round trips every typed evidence request and page") {
            val fixture = evidenceFixture()

            WorkspaceEvidenceJson.decodeDeclarationRequest(
                WorkspaceEvidenceJson.encodeDeclarationRequest(fixture.declarationRequest),
            ) shouldBe fixture.declarationRequest
            WorkspaceEvidenceJson.decodeDeclarationPage(
                WorkspaceEvidenceJson.encodeDeclarationPage(fixture.declarationPage),
            ) shouldBe fixture.declarationPage
            WorkspaceEvidenceJson.decodeReverseUsageRequest(
                WorkspaceEvidenceJson.encodeReverseUsageRequest(fixture.reverseRequest),
            ) shouldBe fixture.reverseRequest
            WorkspaceEvidenceJson.decodeReverseUsagePage(
                WorkspaceEvidenceJson.encodeReverseUsagePage(fixture.reversePage),
            ) shouldBe fixture.reversePage
            WorkspaceEvidenceJson.decodeOccurrenceRequest(
                WorkspaceEvidenceJson.encodeOccurrenceRequest(fixture.occurrenceRequest),
            ) shouldBe fixture.occurrenceRequest
            WorkspaceEvidenceJson.decodeOccurrencePage(
                WorkspaceEvidenceJson.encodeOccurrencePage(fixture.occurrencePage),
            ) shouldBe fixture.occurrencePage
            fixture.occurrences.map(WorkspaceRelationshipOccurrence::cursor) shouldContainExactly
                fixture.occurrences.map { occurrence ->
                    WorkspaceEvidenceCursor(
                        occurrence.location.filePath,
                        occurrence.location.line,
                        occurrence.relationshipId,
                    )
                }
        }

        test("round trips the bounded content-addressed static evidence hierarchy") {
            val fixture = evidenceFixture()
            val declarationEntry =
                WorkspaceStaticEvidenceEntry(
                    routeKey = workspaceDeclarationEvidenceRouteKey(fixture.declarationPage.symbolId),
                    pageIndex = 0,
                    declaration = fixture.declarationPage,
                )
            val occurrenceEntry =
                WorkspaceStaticEvidenceEntry(
                    routeKey = workspaceRelationshipOccurrenceRouteKey(fixture.selector),
                    pageIndex = 0,
                    relationshipOccurrences = fixture.occurrencePage,
                )
            declarationEntry.weight shouldBe 1
            declarationEntry.locator().routeKey shouldBe declarationEntry.routeKey
            occurrenceEntry.weight shouldBe fixture.occurrences.size
            val page = WorkspaceStaticEvidencePage(listOf(occurrenceEntry))
            val pageHash = "1".repeat(64)
            val pageReference =
                WorkspaceStaticEvidencePageReference(
                    file = WorkspaceStaticEvidencePageReference.path(pageHash),
                    contentHash = pageHash,
                    encodedByteSize =
                        WorkspaceEvidenceJson
                            .encodeStaticPage(page)
                            .encodeToByteArray()
                            .size
                            .toLong(),
                    routes = page.entries.map(WorkspaceStaticEvidenceEntry::locator),
                )
            val prefix =
                pageReference.routes
                    .first()
                    .routeHash
                    .take(2)
            val partition = WorkspaceStaticEvidencePartition(prefix, listOf(pageReference))
            val partitionHash = "2".repeat(64)
            val partitionReference =
                WorkspaceStaticEvidencePartitionReference(
                    prefix = prefix,
                    file = WorkspaceStaticEvidencePartitionReference.path(partitionHash),
                    contentHash = partitionHash,
                    encodedByteSize =
                        WorkspaceEvidenceJson
                            .encodeStaticPartition(partition)
                            .encodeToByteArray()
                            .size
                            .toLong(),
                    routePageCount = pageReference.routes.size,
                )
            val catalog =
                WorkspaceStaticEvidenceCatalog(
                    target = fixture.target,
                    partitions = listOf(partitionReference),
                )
            val catalogHash = "3".repeat(64)
            val catalogReference =
                WorkspaceStaticEvidenceCatalogReference(
                    file = WorkspaceStaticEvidenceCatalogReference.path(catalogHash),
                    contentHash = catalogHash,
                    encodedByteSize =
                        WorkspaceEvidenceJson
                            .encodeStaticCatalog(catalog)
                            .encodeToByteArray()
                            .size
                            .toLong(),
                )

            WorkspaceEvidenceJson.decodeStaticPage(WorkspaceEvidenceJson.encodeStaticPage(page)) shouldBe page
            WorkspaceEvidenceJson.decodeStaticPartition(
                WorkspaceEvidenceJson.encodeStaticPartition(partition),
            ) shouldBe partition
            WorkspaceEvidenceJson.decodeStaticCatalog(
                WorkspaceEvidenceJson.encodeStaticCatalog(catalog),
            ) shouldBe catalog
            catalogReference.file shouldBe "data/evidence/catalog-$catalogHash.json"
            workspaceStaticEvidencePageReferenceComparator().compare(pageReference, pageReference) shouldBe 0
            workspaceStaticEvidenceRouteLocatorComparator().compare(
                pageReference.routes.first(),
                pageReference.routes.first(),
            ) shouldBe 0
        }

        test("maps every captured relationship kind to a truthful usage category") {
            RelationshipKind.entries.associateWith(RelationshipKind::usageCategory) shouldBe
                mapOf(
                    RelationshipKind.CALL to WorkspaceUsageCategory.CALLER,
                    RelationshipKind.CONSTRUCTOR to WorkspaceUsageCategory.CONSTRUCTOR_INVOCATION,
                    RelationshipKind.EXTENDS to WorkspaceUsageCategory.OVERRIDE_OR_IMPLEMENTATION,
                    RelationshipKind.IMPLEMENTS to WorkspaceUsageCategory.OVERRIDE_OR_IMPLEMENTATION,
                    RelationshipKind.PROPERTY_TYPE to WorkspaceUsageCategory.TYPE_REFERENCE,
                    RelationshipKind.PARAMETER_TYPE to WorkspaceUsageCategory.TYPE_REFERENCE,
                    RelationshipKind.RETURN_TYPE to WorkspaceUsageCategory.TYPE_REFERENCE,
                    RelationshipKind.TYPE_REFERENCE to WorkspaceUsageCategory.TYPE_REFERENCE,
                    RelationshipKind.IMPORT to WorkspaceUsageCategory.REFERENCE,
                    RelationshipKind.NAME_REFERENCE to WorkspaceUsageCategory.REFERENCE,
                )
        }

        test("rejects malformed evidence and static routing records") {
            val fixture = evidenceFixture()
            shouldThrow<IllegalArgumentException> { WorkspaceEvidenceTarget("", "generation") }
            shouldThrow<IllegalArgumentException> {
                WorkspaceEvidenceCursor("", 0, "")
            }
            shouldThrow<IllegalArgumentException> {
                fixture.reversePage.copy(totalCount = 0)
            }
            shouldThrow<IllegalArgumentException> {
                fixture.reversePage.copy(occurrences = fixture.occurrences.reversed())
            }
            shouldThrow<IllegalArgumentException> {
                WorkspaceStaticEvidenceCatalogReference("wrong.json", "x", -1)
            }
            shouldThrow<IllegalArgumentException> {
                WorkspaceStaticEvidenceEntry(
                    routeKey = "wrong",
                    pageIndex = -1,
                    declaration = fixture.declarationPage,
                    reverseUsages = fixture.reversePage,
                )
            }
        }
    })

private data class EvidenceFixture(
    val target: WorkspaceEvidenceTarget,
    val selector: WorkspaceRelationshipOccurrenceSelector,
    val occurrences: List<WorkspaceRelationshipOccurrence>,
    val declarationRequest: WorkspaceDeclarationEvidenceRequest,
    val declarationPage: WorkspaceDeclarationEvidencePage,
    val reverseRequest: WorkspaceReverseUsageRequest,
    val reversePage: WorkspaceReverseUsagePage,
    val occurrenceRequest: WorkspaceRelationshipOccurrenceRequest,
    val occurrencePage: WorkspaceRelationshipOccurrencePage,
)

private fun evidenceFixture(): EvidenceFixture {
    val target = WorkspaceEvidenceTarget("workspace", "generation")
    val selector = WorkspaceRelationshipOccurrenceSelector(RelationshipKind.CALL, "source-node", "target-node")
    val occurrences = listOf(transportOccurrence(1), transportOccurrence(2))
    val declaration =
        WorkspaceDeclarationEvidence(
            symbolId = "target-symbol",
            ownerSymbolId = "owner-symbol",
            signature = "fun target(): Unit",
            name = "target",
            qualifiedName = "example.target",
            kind = SymbolKind.FUNCTION,
            semantic = DeclarationSemantic.OTHER,
            location = occurrences.first().location,
        )
    val declarationPage = WorkspaceDeclarationEvidencePage(target, declaration.symbolId, declaration)
    val reversePage =
        WorkspaceReverseUsagePage(
            target = target,
            symbolId = declaration.symbolId,
            totalCount = occurrences.size.toLong(),
            occurrences = occurrences,
            previousCursor = occurrences.first().cursor,
            nextCursor = occurrences.last().cursor,
        )
    val occurrencePage =
        WorkspaceRelationshipOccurrencePage(
            target = target,
            selector = selector,
            totalCount = occurrences.size.toLong(),
            occurrences = occurrences,
            previousCursor = occurrences.first().cursor,
            nextCursor = occurrences.last().cursor,
        )
    return EvidenceFixture(
        target = target,
        selector = selector,
        occurrences = occurrences,
        declarationRequest = WorkspaceDeclarationEvidenceRequest(target, declaration.symbolId),
        declarationPage = declarationPage,
        reverseRequest = WorkspaceReverseUsageRequest(target, declaration.symbolId, limit = 2),
        reversePage = reversePage,
        occurrenceRequest = WorkspaceRelationshipOccurrenceRequest(target, selector, limit = 2),
        occurrencePage = occurrencePage,
    )
}

private fun transportOccurrence(index: Int): WorkspaceRelationshipOccurrence =
    WorkspaceRelationshipOccurrence(
        relationshipId = "relationship-$index",
        referenceId = "reference-$index",
        sourceSymbolId = "source-symbol",
        targetSymbolId = "target-symbol",
        kind = RelationshipKind.CALL,
        evidence = RelationshipEvidence.DIRECT,
        category = WorkspaceUsageCategory.CALLER,
        location =
            WorkspaceEvidenceLocation(
                buildId = "build",
                projectId = "project",
                sourceSetId = "source-set",
                sourceSetName = "main",
                fileId = "file",
                filePath = "src/main/kotlin/Target.kt",
                line = index,
                range = SourceRangeSnapshot(index, index + 1),
            ),
        context = "target()",
    )
