package zone.clanker.docx.live.observation

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.runBlocking
import zone.clanker.report.model.WorkspaceCatalogResponse
import zone.clanker.report.model.WorkspaceEvent
import zone.clanker.report.model.WorkspaceEventKind
import zone.clanker.report.model.WorkspaceServiceJson

class WorkspaceObservationTest :
    BehaviorSpec({
        given("the transport-neutral observation contract") {
            val catalog = WorkspaceCatalogResponse(workspaces = emptyList())
            val event =
                WorkspaceEvent(
                    sequence = 7,
                    kind = WorkspaceEventKind.SNAPSHOT,
                    workspaceId = "workspace:sample",
                )
            val observation = FixedWorkspaceObservation(catalog, event)

            `when`("the contract is consumed through its public function types") {
                val catalogCall: suspend WorkspaceObservation.() -> WorkspaceCatalogResponse =
                    WorkspaceObservation::catalog
                val updatesCall: WorkspaceObservation.() -> Flow<WorkspaceEvent> = WorkspaceObservation::updates

                then("the unary and streaming shapes remain explicit") {
                    runBlocking { catalogCall(observation) } shouldBe catalog
                    runBlocking { updatesCall(observation).single() } shouldBe event
                }
            }

            `when`("the existing model payloads cross their canonical JSON boundary") {
                then("the catalog and update remain serialization-compatible") {
                    WorkspaceServiceJson.decodeCatalog(WorkspaceServiceJson.encodeCatalog(catalog)) shouldBe catalog
                    WorkspaceServiceJson.decodeEvent(WorkspaceServiceJson.encodeEvent(event)) shouldBe event
                }
            }

            `when`("the contract version is inspected") {
                then("the current read-only protocol is accepted") {
                    WorkspaceObservationProtocol.CURRENT_VERSION shouldBe 1
                    WorkspaceObservationProtocol.supports(WorkspaceObservationProtocol.CURRENT_VERSION) shouldBe true
                }

                then("an unsupported protocol is rejected") {
                    WorkspaceObservationProtocol.supports(0) shouldBe false
                }
            }
        }
    })

private class FixedWorkspaceObservation(
    private val response: WorkspaceCatalogResponse,
    private val event: WorkspaceEvent,
) : WorkspaceObservation {
    override suspend fun catalog(): WorkspaceCatalogResponse = response

    override fun updates(): Flow<WorkspaceEvent> = flowOf(event)
}
