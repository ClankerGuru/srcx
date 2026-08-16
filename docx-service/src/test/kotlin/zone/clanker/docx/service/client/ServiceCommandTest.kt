package zone.clanker.docx.service.client

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import zone.clanker.docx.service.DocxServiceLimits
import zone.clanker.docx.service.defaultEventSubscriberCount
import zone.clanker.docx.service.defaultHttpQueueCapacity
import zone.clanker.docx.service.temporaryDirectory
import zone.clanker.docx.service.workspace.DisabledWorkspaceGenerationIndexer
import zone.clanker.docx.service.workspace.WorkspaceGeneration
import zone.clanker.report.model.WorkspaceServiceEndpoint
import zone.clanker.report.model.WorkspaceServiceJson
import java.nio.file.Files

class ServiceCommandTest :
    BehaviorSpec({
        given("the service command line") {
            `when`("a headless ephemeral server is requested") {
                val command =
                    parseCommand(
                        arrayOf("serve", "--port", "0", "--token", "configured"),
                        emptyMap(),
                    ) as ServiceCommand.Serve

                then("loopback and an OS-selected port are the defaults") {
                    command.options.host shouldBe "127.0.0.1"
                    command.options.port shouldBe 0
                    command.options.token shouldBe "configured"
                    command.options.limits.maxQueuedRequests shouldBe
                        defaultHttpQueueCapacity(command.options.limits.maxHttpThreads)
                    command.options.limits.maxEventSubscribers shouldBe
                        defaultEventSubscriberCount(command.options.limits.maxHttpThreads)
                }
            }

            `when`("registration discovers the running endpoint") {
                val root = temporaryDirectory("docx-command")
                val endpoint = root.resolve("endpoint.json")
                val site = root.resolve("site")
                Files.writeString(
                    endpoint,
                    WorkspaceServiceJson.encodeEndpoint(
                        WorkspaceServiceEndpoint(baseUrl = "http://127.0.0.1:4321/", token = "secret"),
                    ),
                )
                val command =
                    parseCommand(
                        arrayOf(
                            "register",
                            "--id",
                            "sample",
                            "--site",
                            site.toString(),
                            "--endpoint",
                            endpoint.toString(),
                        ),
                        emptyMap(),
                    ) as ServiceCommand.Register

                then("the client uses the endpoint port and token without another server configuration step") {
                    command.connection.baseUrl.toString() shouldBe "http://127.0.0.1:4321/"
                    command.connection.token shouldBe "secret"
                    command.siteDirectory shouldBe site.toAbsolutePath().normalize()
                }
            }

            `when`("options are malformed") {
                then("the usage error is explicit") {
                    shouldThrow<IllegalArgumentException> {
                        parseCommand(arrayOf("register", "--id"), emptyMap())
                    }.message shouldContain "Every option"
                    shouldThrow<IllegalArgumentException> {
                        parseCommand(arrayOf("unknown"), emptyMap())
                    }.message shouldContain "Unknown command"
                    shouldThrow<IllegalArgumentException> {
                        parseCommand(arrayOf("serve", "--http-threads", "many"), emptyMap())
                    }.message shouldContain "integer"
                    shouldThrow<IllegalArgumentException> {
                        DocxServiceLimits(maxHttpThreads = 4, maxEventSubscribers = 4)
                    }.message shouldContain "fewer than"
                }
            }

            `when`("client commands use an explicit service") {
                val list =
                    parseCommand(
                        arrayOf("list", "--service", "http://127.0.0.1:4040"),
                        emptyMap(),
                    ) as ServiceCommand.ListWorkspaces
                val unregister =
                    parseCommand(
                        arrayOf(
                            "unregister",
                            "--id",
                            "sample",
                            "--service",
                            "http://127.0.0.1:4040",
                        ),
                        mapOf("DOCX_SERVICE_TOKEN" to "environment-secret"),
                    ) as ServiceCommand.Unregister

                then("URLs are normalized and mutating commands read the token environment") {
                    list.connection.baseUrl.toString() shouldBe "http://127.0.0.1:4040/"
                    unregister.connection.token shouldBe "environment-secret"
                    parseCommand(emptyArray(), emptyMap()) shouldBe ServiceCommand.Help
                }
            }

            `when`("the optional index implementation is disabled") {
                val generation = WorkspaceGeneration("sample", temporaryDirectory("disabled-index"), "generation-a")

                then("the no-op boundary leaves static serving independent") {
                    DisabledWorkspaceGenerationIndexer.enabled shouldBe false
                    DisabledWorkspaceGenerationIndexer.index(generation) shouldBe
                        zone.clanker.report.model.WorkspaceIndexStatus.Disabled
                    DisabledWorkspaceGenerationIndexer.remove("sample")
                    DisabledWorkspaceGenerationIndexer.close()
                }
            }
        }
    })
