package zone.clanker.docx.service.workspace

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import zone.clanker.docx.service.temporaryDirectory
import zone.clanker.docx.service.writeSite
import zone.clanker.report.model.WorkspaceAvailability
import zone.clanker.report.model.WorkspaceEventKind
import zone.clanker.report.model.WorkspaceIndexStatus
import zone.clanker.report.model.WorkspaceRegistrationRequest
import java.nio.file.Files

class WorkspaceRegistryTest :
    BehaviorSpec({
        given("a persistent multi-workspace registry") {
            val root = temporaryDirectory("docx-registry")
            val siteA = root.resolve("site-a").also { writeSite(it, workspaceName = "A") }
            val siteB = root.resolve("site-b").also { writeSite(it, workspaceName = "B") }
            val store = WorkspaceRegistryStore(root.resolve("registry.json"))
            var now = 10L
            val registry = WorkspaceRegistry(store, indexingEnabled = true) { now++ }

            `when`("two generated sites are registered") {
                val first = registry.register(WorkspaceRegistrationRequest("alpha", siteA.toString()))
                val second = registry.register(WorkspaceRegistrationRequest("bravo", siteB.toString()))

                then("stable IDs map to isolated viewer routes") {
                    first.event?.kind shouldBe WorkspaceEventKind.REGISTERED
                    second.mount.viewerPath shouldBe "/w/bravo/"
                    registry.catalog().workspaces.map { it.workspaceId } shouldContainExactly listOf("alpha", "bravo")
                    registry.catalog().workspaces.all { it.index == WorkspaceIndexStatus.Pending } shouldBe true
                }

                then("registrations survive service reconstruction") {
                    val restored = WorkspaceRegistry(store, indexingEnabled = false) { 20L }
                    restored.catalog().workspaces.map { it.workspaceName } shouldContainExactly listOf("A", "B")
                    restored.catalog().workspaces.all { it.index == WorkspaceIndexStatus.Disabled } shouldBe true
                }
            }
        }

        given("a watched generation") {
            val root = temporaryDirectory("docx-refresh")
            val site = root.resolve("site").also(::writeSite)
            var now = 30L
            val registry = WorkspaceRegistry(WorkspaceRegistryStore(root.resolve("registry.json")), true) { now++ }
            registry.register(WorkspaceRegistrationRequest("sample", site.toString()))
            val pending = registry.pendingGenerations().single()

            `when`("indexing finishes and a later generation is published") {
                registry.completeIndex(pending, WorkspaceIndexStatus.Current(pending.generationId, now++))
                writeSite(site, generationId = "generation-b", message = "Updated")
                val changes = registry.refresh()

                then("the service reports the change and schedules only the new generation") {
                    changes.single().workspace?.generationId shouldBe "generation-b"
                    changes.single().workspace?.index shouldBe WorkspaceIndexStatus.Pending
                    registry.pendingGenerations().single().generationId shouldBe "generation-b"
                }
            }

            `when`("the generated directory temporarily disappears") {
                val registeredDirectory = registry.siteDirectory("sample")
                site.toFile().deleteRecursively()
                val unavailable = registry.refresh().single().workspace

                then("the registration stays mounted with an unavailable status") {
                    unavailable?.availability shouldBe WorkspaceAvailability.UNAVAILABLE
                    registry.siteDirectory("sample") shouldBe registeredDirectory
                }
            }
        }

        given("an invalid registration") {
            val root = temporaryDirectory("docx-invalid")
            val registry = WorkspaceRegistry(WorkspaceRegistryStore(root.resolve("registry.json")), false)

            `when`("the ID or site is unsafe") {
                then("registration fails before registry persistence") {
                    shouldThrow<IllegalArgumentException> { requireWorkspaceId("Not Safe") }
                        .message shouldContain "Workspace ID"
                    shouldThrow<IllegalArgumentException> {
                        registry.register(WorkspaceRegistrationRequest("safe", "relative/site"))
                    }.message shouldContain "absolute"
                    Files.exists(root.resolve("registry.json")) shouldBe false
                }
            }
        }

        given("a bounded event hub") {
            val events = WorkspaceEventHub(maxSubscribers = 1)

            then("one blocked SSE client cannot grow subscriber state without a limit") {
                val first = events.subscribe()
                (first == null) shouldBe false
                events.subscribe() shouldBe null
                first?.close()
                val replacement = events.subscribe()
                (replacement == null) shouldBe false
                replacement?.close()
                events.close()
                events.subscribe() shouldBe null
            }
        }
    })
