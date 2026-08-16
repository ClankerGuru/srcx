package zone.clanker.docx.index

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import zone.clanker.report.model.WorkspaceGraphFacet
import zone.clanker.report.model.WorkspaceGraphFilter
import zone.clanker.report.model.WorkspaceGraphLimits
import zone.clanker.report.model.WorkspaceGraphNodeRole
import zone.clanker.report.model.WorkspaceGraphRelationKind
import zone.clanker.report.model.WorkspaceGraphRelationshipCountFilter
import zone.clanker.report.model.WorkspaceGraphRelationshipDirection
import zone.clanker.report.model.WorkspaceGraphRequest
import zone.clanker.report.model.WorkspaceGraphSelection
import zone.clanker.report.model.WorkspaceGraphSlice
import zone.clanker.report.model.WorkspaceGraphView
import zone.clanker.report.model.WorkspaceSiteJson
import zone.clanker.report.model.WorkspaceSummaryShard
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.concurrent.CancellationException

class WorkspaceGraphLiveParityTest :
    BehaviorSpec({
        given("the source hierarchy imported from the static fixture") {
            val site = WorkspaceIndexFixture.createSite()
            val database = WorkspaceIndexFixture.createDatabase()
            WorkspaceIndexFixture.writeGeneration(site, GENERATION_ID)

            then("the live SQLite slice has the same deterministic hierarchy, relation, and availability truth") {
                WorkspaceReportIndex.open(database).use { index ->
                    index.indexSite(WorkspaceIndexFixture.INDEX_WORKSPACE_ID, site)
                    val slice = index.graphSlice(WorkspaceIndexFixture.INDEX_WORKSPACE_ID, alphaFileRequest())
                    val staticSlice = staticSourceSlice(site, alphaFileRequest())

                    slice shouldBe staticSlice
                    slice.target.workspaceId shouldBe WorkspaceIndexFixture.SOURCE_WORKSPACE_ID
                    slice.target.generationId shouldBe GENERATION_ID
                    slice.counts.matchingPrimaryNodeCount shouldBe 4
                    slice.counts.matchingRelationCount shouldBe 1
                    slice.content.nodes.map { node -> node.id } shouldContainExactly EXPECTED_ALPHA_FILE_SLICE_IDS
                    slice.content.relations.single().let { relation ->
                        relation.id shouldBe
                            "source-rollup:CALL:21:symbol:alpha:function:18:symbol:alpha:class"
                        relation.kind shouldBe WorkspaceGraphRelationKind.CALL
                        relation.endpoints.sourceNodeId shouldBe WorkspaceIndexFixture.ALPHA_FUNCTION_ID
                        relation.endpoints.targetNodeId shouldBe WorkspaceIndexFixture.ALPHA_CLASS_ID
                        relation.facts.factCount shouldBe 2
                        relation.facts.sampleFactIds shouldContainExactly
                            listOf("relationship:alpha:call", "relationship:alpha:call:later")
                    }
                    val alphaClass =
                        slice.content.nodes.single { node ->
                            node.id == WorkspaceIndexFixture.ALPHA_CLASS_ID
                        }
                    alphaClass.roles shouldBe
                        listOf(WorkspaceGraphNodeRole.ENDPOINT, WorkspaceGraphNodeRole.PRIMARY)
                    val memberOwnership =
                        slice.content.availability.single { available ->
                            available.fact.name == "MEMBER_OWNERSHIP"
                        }
                    memberOwnership.reason?.name shouldBe "NOT_CAPTURED"
                }
            }

            then("strict outgoing degree filtering preserves the exact incoming endpoint closure") {
                WorkspaceReportIndex.open(database).use { index ->
                    index.indexSite(WorkspaceIndexFixture.INDEX_WORKSPACE_ID, site)
                    val request =
                        alphaFileRequest(
                            filter =
                                WorkspaceGraphFilter(
                                    relationshipCountFilter =
                                        WorkspaceGraphRelationshipCountFilter(
                                            moreThan = 0,
                                            direction = WorkspaceGraphRelationshipDirection.OUTGOING,
                                        ),
                                ),
                        )
                    val slice = index.graphSlice(WorkspaceIndexFixture.INDEX_WORKSPACE_ID, request)

                    slice shouldBe staticSourceSlice(site, request)
                    slice.counts.matchingPrimaryNodeCount shouldBe 2
                    slice.content.relations.map { relation -> relation.kind } shouldContainExactly
                        listOf(WorkspaceGraphRelationKind.CALL)
                    val alphaClass =
                        slice.content.nodes.single { node ->
                            node.id == WorkspaceIndexFixture.ALPHA_CLASS_ID
                        }
                    alphaClass.roles shouldBe
                        listOf(WorkspaceGraphNodeRole.ENDPOINT)
                    val alphaFunction =
                        slice.content.nodes.single { node ->
                            node.id == WorkspaceIndexFixture.ALPHA_FUNCTION_ID
                        }
                    alphaFunction.roles shouldBe
                        listOf(WorkspaceGraphNodeRole.ENDPOINT, WorkspaceGraphNodeRole.PRIMARY)
                }
            }

            then("generation mismatch and cancellation fail before a response is assembled") {
                WorkspaceReportIndex.open(database).use { index ->
                    index.indexSite(WorkspaceIndexFixture.INDEX_WORKSPACE_ID, site)
                    shouldThrow<IllegalArgumentException> {
                        index.graphSlice(
                            WorkspaceIndexFixture.INDEX_WORKSPACE_ID,
                            alphaFileRequest().copy(generationId = "generation:stale"),
                        )
                    }
                    shouldThrow<CancellationException> {
                        index.graphSlice(
                            WorkspaceIndexFixture.INDEX_WORKSPACE_ID,
                            alphaFileRequest(),
                            WorkspaceGraphCancellation { true },
                        )
                    }
                }
            }

            then("zero evidence keeps aggregate counts without serializing fact IDs") {
                WorkspaceReportIndex.open(database).use { index ->
                    index.indexSite(WorkspaceIndexFixture.INDEX_WORKSPACE_ID, site)
                    val request =
                        alphaFileRequest().copy(
                            limits = WorkspaceGraphLimits(evidencePerRelationLimit = 0),
                        )
                    val slice = index.graphSlice(WorkspaceIndexFixture.INDEX_WORKSPACE_ID, request)
                    val facts =
                        slice.content.relations
                            .single()
                            .facts
                    slice shouldBe staticSourceSlice(site, request)
                    facts.factCount shouldBe 2
                    facts.sampleFactIds shouldBe emptyList()
                    facts.omittedFactCount shouldBe 2
                }
            }

            then("the node bound never permits a relation with incomplete endpoint closure") {
                WorkspaceReportIndex.open(database).use { index ->
                    index.indexSite(WorkspaceIndexFixture.INDEX_WORKSPACE_ID, site)
                    val request =
                        alphaFileRequest().copy(
                            limits = WorkspaceGraphLimits(nodeLimit = 9),
                        )
                    val slice = index.graphSlice(WorkspaceIndexFixture.INDEX_WORKSPACE_ID, request)

                    slice shouldBe staticSourceSlice(site, request)
                    slice.content.nodes.size shouldBe 9
                    slice.content.relations shouldBe emptyList()
                    slice.counts.matchingRelationCount shouldBe 1
                }
            }

            then("an older current index without graph tables is rebuilt from the immutable site") {
                WorkspaceReportIndex.open(database).use { index ->
                    index.indexSite(WorkspaceIndexFixture.INDEX_WORKSPACE_ID, site)
                }
                DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
                    connection.createStatement().use { statement ->
                        statement.execute("PRAGMA foreign_keys = ON")
                        statement.executeUpdate("DELETE FROM graph_nodes")
                    }
                }
                WorkspaceReportIndex.open(database).use { index ->
                    index.indexSite(WorkspaceIndexFixture.INDEX_WORKSPACE_ID, site).alreadyCurrent shouldBe false
                    index.graphSlice(WorkspaceIndexFixture.INDEX_WORKSPACE_ID, alphaFileRequest()) shouldBe
                        staticSourceSlice(site, alphaFileRequest())
                }
            }
        }
    })

private fun alphaFileRequest(filter: WorkspaceGraphFilter = WorkspaceGraphFilter()): WorkspaceGraphRequest =
    WorkspaceGraphRequest(
        workspaceId = WorkspaceIndexFixture.SOURCE_WORKSPACE_ID,
        generationId = GENERATION_ID,
        facet = WorkspaceGraphFacet.SOURCE,
        view =
            WorkspaceGraphView(
                selection =
                    WorkspaceGraphSelection(
                        scopeRootIds = listOf(ALPHA_FILE_ID),
                        expandedNodeIds = listOf(ALPHA_FILE_ID),
                    ),
                filter = filter,
            ),
    )

private const val GENERATION_ID: String = "generation-live-parity"
private const val ALPHA_FILE_ID: String = "file:alpha:main"
private const val ALPHA_EXAMPLE_PACKAGE_ID: String =
    "source-package:21:source-set:alpha:main:7:example"
private const val ALPHA_PACKAGE_ID: String =
    "source-package:21:source-set:alpha:main:13:example.alpha"

private val EXPECTED_ALPHA_FILE_SLICE_IDS =
    listOf(
        WorkspaceIndexFixture.BUILD_ID,
        ALPHA_FILE_ID,
        "finding:alpha:warning",
        WorkspaceIndexFixture.ALPHA_PROJECT_ID,
        ALPHA_EXAMPLE_PACKAGE_ID,
        ALPHA_PACKAGE_ID,
        WorkspaceIndexFixture.ALPHA_SOURCE_SET_ID,
        WorkspaceIndexFixture.ALPHA_CLASS_ID,
        WorkspaceIndexFixture.ALPHA_FUNCTION_ID,
        WorkspaceIndexFixture.SOURCE_WORKSPACE_ID,
    ).sorted()

private fun staticSourceSlice(
    site: Path,
    request: WorkspaceGraphRequest,
): WorkspaceGraphSlice {
    val summary = WorkspaceSiteJson.decodeWorkspace(Files.readString(site.resolve("data/workspace.json")))
    val projects =
        summary.projectShards.map { reference ->
            WorkspaceSiteJson.decodeProject(Files.readString(site.resolve(reference.file)))
        }
    val projector =
        Class
            .forName("zone.clanker.docx.web.atlas.hierarchy.WorkspaceSourceHierarchyProjectorKt")
            .getMethod(
                "workspaceSourceHierarchySlice",
                WorkspaceSummaryShard::class.java,
                String::class.java,
                WorkspaceGraphRequest::class.java,
                List::class.java,
            )
    return projector.invoke(null, summary, GENERATION_ID, request, projects) as WorkspaceGraphSlice
}
