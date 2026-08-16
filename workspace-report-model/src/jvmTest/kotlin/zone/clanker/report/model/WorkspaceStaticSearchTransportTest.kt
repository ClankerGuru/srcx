package zone.clanker.report.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class WorkspaceStaticSearchTransportTest :
    BehaviorSpec({
        given("a bounded content-addressed prefix shard") {
            val entry = searchEntry("symbol:consumer", "ConsumerController")
            val encodedShard =
                WorkspaceSearchJson.encodeShard(
                    WorkspaceSearchShard(prefix = "co", pageIndex = 0, entries = listOf(entry)),
                )
            val shardHash = "a".repeat(HASH_LENGTH)
            val shardReference =
                WorkspaceSearchShardReference(
                    prefix = "co",
                    pageIndex = 0,
                    file = WorkspaceSearchShardReference.path(shardHash),
                    contentHash = shardHash,
                    encodedByteSize = encodedShard.encodeToByteArray().size.toLong(),
                    entryCount = 1,
                )
            val catalog =
                WorkspaceSearchCatalog(
                    generationId = "generation-search",
                    workspaceId = "workspace:search",
                    entryCount = 1,
                    shards = listOf(shardReference),
                )

            then("catalog and shard contracts round-trip without graph or source payloads") {
                WorkspaceSearchJson.decodeShard(encodedShard).entries shouldContainExactly listOf(entry)
                WorkspaceSearchJson.decodeCatalog(
                    WorkspaceSearchJson.encodeCatalog(catalog),
                ) shouldBe catalog
                encodedShard.contains("content") shouldBe false
                encodedShard.contains("relationships") shouldBe false
            }

            then("camel-case identifiers expose both component and complete terms") {
                entry.terms shouldContainExactly listOf("consumer", "consumercontroller", "controller")
            }

            then("hash-derived paths and bounded pages are enforced") {
                shouldThrow<IllegalArgumentException> {
                    shardReference.copy(file = "data/search/shards/not-the-hash.json")
                }
                shouldThrow<IllegalArgumentException> {
                    WorkspaceSearchShard(
                        prefix = "co",
                        pageIndex = 0,
                        entries =
                            List(WorkspaceSearchShard.MAX_ENTRIES + 1) { index ->
                                searchEntry("symbol:$index", "Consumer$index")
                            }.sortedWith(workspaceSearchEntryComparator()),
                    )
                }
            }
        }

        given("a site manifest written before static search existed") {
            val manifest =
                WorkspaceSiteManifest(
                    generationId = "generation-legacy",
                    snapshotSchemaVersion = WorkspaceSnapshot.CURRENT_SCHEMA_VERSION,
                    workspaceFile = "data/workspace.json",
                    dashboardFile = "data/dashboard.json",
                    projectShards = emptyList(),
                    assetFiles = emptyList(),
                )

            then("the optional catalog reference retains backward decoding") {
                WorkspaceSiteJson.decodeManifest(WorkspaceSiteJson.encodeManifest(manifest)) shouldBe manifest
                manifest.searchCatalog shouldBe null
            }
        }

        given("a typed file target and bounded grouped results") {
            val fileEntry =
                WorkspaceSearchEntry(
                    id = "file:consumer",
                    kind = WorkspaceSearchKind.FILE,
                    label = "ConsumerController.kt",
                    detail = "src/main/kotlin/ConsumerController.kt",
                    terms = workspaceSearchTerms("ConsumerController.kt"),
                    target =
                        WorkspaceSearchTarget(
                            location =
                                WorkspaceSearchLocation(
                                    buildId = "build:root",
                                    projectId = "project:consumer",
                                    sourceSetId = "source-set:main",
                                    fileId = "file:consumer",
                                    line = 42,
                                ),
                            extension = "kt",
                        ),
                    badges =
                        listOf(
                            WorkspaceSearchBadge(WorkspaceSearchBadgeKind.RELATIONSHIPS, 12),
                            WorkspaceSearchBadge(WorkspaceSearchBadgeKind.PROBLEMS, 2),
                        ),
                )
            val group = WorkspaceSearchResultGroup(WorkspaceSearchKind.FILE, listOf(fileEntry))
            val results =
                WorkspaceSearchResults(
                    query = "ext:kt",
                    category = WorkspaceSearchCategory.EXTENSION,
                    groups = listOf(group),
                    loadedShardCount = 1,
                    loadedCandidateCount = 1,
                    loadedMatchCount = 1,
                    truncated = false,
                )

            then("the target keeps enough scope and line evidence for one-step navigation") {
                fileEntry.target.location.line shouldBe 42
                fileEntry.target.extension shouldBe "kt"
                fileEntry.badges.map(WorkspaceSearchBadge::count) shouldContainExactly listOf(12, 2)
                results.groups.single().results shouldContainExactly listOf(fileEntry)
            }

            then("invalid target ownership and result accounting are rejected") {
                shouldThrow<IllegalArgumentException> { fileEntry.copy(kind = WorkspaceSearchKind.CLASS) }
                shouldThrow<IllegalArgumentException> {
                    WorkspaceSearchLocation(projectId = "project:consumer")
                }
                shouldThrow<IllegalArgumentException> {
                    WorkspaceSearchLocation(buildId = "build:root", fileId = "file:consumer")
                }
                shouldThrow<IllegalArgumentException> {
                    results.copy(loadedShardCount = WorkspaceSearchCatalog.MAX_SHARD_LOADS + 1)
                }
                shouldThrow<IllegalArgumentException> { results.copy(loadedMatchCount = 0) }
                shouldThrow<IllegalArgumentException> {
                    fileEntry.copy(
                        badges =
                            listOf(
                                WorkspaceSearchBadge(WorkspaceSearchBadgeKind.PROBLEMS, 1),
                                WorkspaceSearchBadge(WorkspaceSearchBadgeKind.PROBLEMS, 2),
                            ),
                    )
                }
                shouldThrow<IllegalArgumentException> {
                    WorkspaceSearchBadge(WorkspaceSearchBadgeKind.CYCLES, 0)
                }
            }
        }
    })

private fun searchEntry(
    id: String,
    label: String,
): WorkspaceSearchEntry =
    WorkspaceSearchEntry(
        id = id,
        kind = WorkspaceSearchKind.CLASS,
        label = label,
        detail = "fixture.ConsumerController",
        terms = workspaceSearchTerms(label),
    )

private const val HASH_LENGTH: Int = 64
