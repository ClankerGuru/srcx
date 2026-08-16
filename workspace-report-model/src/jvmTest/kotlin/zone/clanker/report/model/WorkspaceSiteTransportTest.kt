package zone.clanker.report.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

class WorkspaceSiteTransportTest :
    BehaviorSpec({
        given("the static-site manifest and lazy shards") {
            val snapshot = completeWorkspaceSnapshot()
            val shardReferences =
                snapshot.projects.map { project ->
                    ProjectShardReference(project.id, "data/shards/${project.id.substringAfter(':')}.json")
                }
            val manifest =
                WorkspaceSiteManifest(
                    generationId = "generation-1",
                    snapshotSchemaVersion = snapshot.schemaVersion,
                    workspaceFile = "data/workspace.json",
                    dashboardFile = "data/dashboard.json",
                    atlasOverviewsFile = "data/atlas-overviews.json",
                    projectShards = shardReferences,
                    assetFiles = listOf("assets/app.js", "assets/style.css"),
                )
            val summary =
                WorkspaceSummaryShard(
                    workspace = snapshot.workspace,
                    builds = snapshot.builds,
                    projects = snapshot.projects,
                    sourceSets =
                        snapshot.sourceSets.map { sourceSet ->
                            WorkspaceSourceSetSummary(
                                id = sourceSet.id,
                                projectId = sourceSet.projectId,
                                name = sourceSet.name,
                                fileCount = snapshot.files.count { file -> file.sourceSetId == sourceSet.id },
                            )
                        },
                    projectShards = shardReferences,
                    buildEdges = snapshot.buildEdges,
                )
            val rootSourceSetIds =
                snapshot.sourceSets
                    .filter { it.projectId == "project:root" }
                    .mapTo(mutableSetOf(), SourceSetSnapshot::id)
            val rootFileIds =
                snapshot.files
                    .filter { it.sourceSetId in rootSourceSetIds }
                    .mapTo(mutableSetOf(), SourceFileSnapshot::id)
            val references = snapshot.references.filter { reference -> reference.sourceFileId in rootFileIds }
            val referenceIds = references.mapTo(mutableSetOf(), ReferenceSnapshot::id)
            val relationships =
                snapshot.relationships.filter { relationship -> relationship.referenceId in referenceIds }
            val externalTargetIds = relationships.mapTo(mutableSetOf(), RelationshipSnapshot::targetSymbolId)
            val symbolClosure =
                snapshot.symbols.filter { it.fileId in rootFileIds || it.id in externalTargetIds }
            val fileClosureIds = symbolClosure.mapTo(rootFileIds, SymbolSnapshot::fileId)
            val files = snapshot.files.filter { it.id in fileClosureIds }.map { it.copy(content = null) }
            val sourceSetClosureIds = files.mapTo(mutableSetOf(), SourceFileSnapshot::sourceSetId)
            val sourceSets = snapshot.sourceSets.filter { it.id in sourceSetClosureIds }
            val project =
                ProjectGraphShard(
                    projectId = "project:root",
                    sourceSets = sourceSets,
                    files = files,
                    symbols = symbolClosure,
                    references = references,
                    relationships = relationships,
                    findings = snapshot.projectAnalyses.single().findings,
                    analysis = snapshot.projectAnalyses.single(),
                )
            val cycle = snapshot.relationshipCycles.single()
            val cycleRelationshipIds = cycle.relationshipIds.toSet()
            val cycleRelationships = snapshot.relationships.filter { it.id in cycleRelationshipIds }
            val cycleReferenceIds = cycleRelationships.mapTo(mutableSetOf(), RelationshipSnapshot::referenceId)
            val cycleReferences = snapshot.references.filter { it.id in cycleReferenceIds }
            val cycleSymbolIds = cycle.symbolIds.toSet()
            val cycleSymbols = snapshot.symbols.filter { it.id in cycleSymbolIds }
            val cycleFileIds =
                cycleSymbols.mapTo(mutableSetOf(), SymbolSnapshot::fileId).apply {
                    addAll(cycleReferences.map(ReferenceSnapshot::sourceFileId))
                }
            val cycleFiles = snapshot.files.filter { it.id in cycleFileIds }
            val cycleSourceSetIds = cycleFiles.mapTo(mutableSetOf(), SourceFileSnapshot::sourceSetId)
            val cycleSourceSets = snapshot.sourceSets.filter { it.id in cycleSourceSetIds }
            val crossProjectCycleProject =
                ProjectGraphShard(
                    projectId = "project:root",
                    sourceSets = cycleSourceSets,
                    files = cycleFiles,
                    symbols = cycleSymbols,
                    references = cycleReferences,
                    relationships = cycleRelationships,
                    cycles = listOf(cycle),
                )

            `when`("each transport crosses its shared serializer") {
                then("the JVM and Wasm-facing contract is reversible") {
                    WorkspaceSiteJson.decodeManifest(WorkspaceSiteJson.encodeManifest(manifest)) shouldBe manifest
                    WorkspaceSiteJson.decodeWorkspace(WorkspaceSiteJson.encodeWorkspace(summary)) shouldBe summary
                    WorkspaceSiteJson.decodeProject(WorkspaceSiteJson.encodeProject(project)) shouldBe project
                    summary.sourceSets.sumOf(WorkspaceSourceSetSummary::fileCount) shouldBe snapshot.files.size
                }
            }

            `when`("the bounded workspace overview bundle crosses its shared serializer") {
                val scope = AtlasScope(AtlasScopeKind.WORKSPACE, snapshot.workspace.id, snapshot.workspace.name)
                val overviews =
                    WorkspaceAtlasOverviewShard(
                        AtlasLens.entries.map { lens -> emptyAtlasFrame(scope, lens) },
                    )

                then("every lens remains available in deterministic order") {
                    WorkspaceSiteJson.decodeAtlasOverviews(
                        WorkspaceSiteJson.encodeAtlasOverviews(overviews),
                    ) shouldBe overviews
                    overviews.frame(AtlasLens.CYCLES).lens shouldBe AtlasLens.CYCLES
                }
            }

            `when`("a future site generator adds an unknown manifest field") {
                then("the current loader preserves the supported manifest") {
                    val fields =
                        Json
                            .parseToJsonElement(WorkspaceSiteJson.encodeManifest(manifest))
                            .jsonObject
                            .toMutableMap()
                    fields["futureAssetMap"] = JsonPrimitive("ignored")
                    WorkspaceSiteJson.decodeManifest(JsonObject(fields).toString()) shouldBe manifest
                }
            }

            `when`("a manifest claims an unsupported version") {
                then("the loader rejects it before following any paths") {
                    val fields =
                        Json
                            .parseToJsonElement(WorkspaceSiteJson.encodeManifest(manifest))
                            .jsonObject
                            .toMutableMap()
                    fields["schemaVersion"] = JsonPrimitive(3)
                    shouldThrow<IllegalArgumentException> {
                        WorkspaceSiteJson.decodeManifest(JsonObject(fields).toString())
                    }
                }
            }

            `when`("a current manifest omits its required dashboard shard") {
                then("the loader rejects the incomplete full-report catalog") {
                    val fields =
                        Json
                            .parseToJsonElement(WorkspaceSiteJson.encodeManifest(manifest))
                            .jsonObject
                            .toMutableMap()
                    fields.remove("dashboardFile")
                    shouldThrow<Exception> {
                        WorkspaceSiteJson.decodeManifest(JsonObject(fields).toString())
                    }
                }
            }

            `when`("a relationship endpoint is omitted from a lazy shard") {
                then("construction rejects the incomplete graph closure") {
                    shouldThrow<IllegalArgumentException> {
                        project.copy(
                            symbols = project.symbols.filterNot { it.id == "symbol:implementation" },
                        )
                    }
                }
            }

            `when`("a directed cycle crosses a project boundary") {
                then("its external source evidence and endpoints survive the lazy-shard boundary") {
                    WorkspaceSiteJson.decodeProject(
                        WorkspaceSiteJson.encodeProject(crossProjectCycleProject),
                    ) shouldBe crossProjectCycleProject
                    crossProjectCycleProject.ownedSourceSetIds shouldBe listOf("source-set:root")
                    crossProjectCycleProject.sourceSets.map(SourceSetSnapshot::id) shouldBe
                        listOf("source-set:included", "source-set:root")
                }
            }

            `when`("external relationship evidence is no longer required by a retained cycle") {
                then("construction rejects the unrelated external source closure") {
                    shouldThrow<IllegalArgumentException> {
                        crossProjectCycleProject.copy(cycles = emptyList())
                    }
                }
            }

            `when`("a cross-project cycle omits its external source file") {
                then("construction rejects the incomplete cycle closure") {
                    shouldThrow<IllegalArgumentException> {
                        crossProjectCycleProject.copy(
                            files = crossProjectCycleProject.files.filterNot { it.id == "file:implementation" },
                        )
                    }
                }
            }

            `when`("a finding source file is omitted from a lazy shard") {
                then("construction rejects the incomplete finding closure") {
                    shouldThrow<IllegalArgumentException> {
                        project.copy(files = project.files.filterNot { it.id == "file:consumer" })
                    }
                }
            }
        }

        given("a generation publication status") {
            val status =
                WorkspaceSiteStatus(
                    state = WorkspaceSiteState.UPDATING,
                    generationId = "generation-2",
                    workspaceName = "fixture-workspace",
                    message = "Refreshing the static workspace generation",
                )

            then("the status codec preserves the live publication contract") {
                WorkspaceSiteJson.decodeStatus(WorkspaceSiteJson.encodeStatus(status)) shouldBe status
                WorkspaceSiteState.entries.map(WorkspaceSiteState::label) shouldBe
                    listOf("Current", "Updating", "Stale", "Failed")
            }

            then("invalid publication identities and messages are rejected before transport") {
                shouldThrow<IllegalArgumentException> { status.copy(schemaVersion = 1) }
                shouldThrow<IllegalArgumentException> { status.copy(generationId = "") }
                shouldThrow<IllegalArgumentException> { status.copy(workspaceName = "") }
                shouldThrow<IllegalArgumentException> { status.copy(message = "") }
            }
        }
    })

private fun emptyAtlasFrame(
    scope: AtlasScope,
    lens: AtlasLens,
): AtlasFrame =
    AtlasFrame(
        frameId = "atlas:workspace:fixture:${lens.name.lowercase()}:overview",
        scope = scope,
        lens = lens,
        builds = emptyList(),
        nodes = emptyList(),
        edges = emptyList(),
        totalNodeCount = 0,
        matchingNodeCount = 0,
        pageIndex = 0,
        pageCount = 0,
        totalRelationshipRecordCount = 0,
        shownRelationshipRecordCount = 0,
    )
