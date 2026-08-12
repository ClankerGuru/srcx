package zone.clanker.gradle.srcx

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.file.shouldExist
import io.kotest.matchers.file.shouldNotExist
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import java.io.File

class WorkspaceCumulativeCompositeBuildTest :
    BehaviorSpec({
        given("a source-only composite workspace with consumers in two external builds") {
            val workspace = cumulativeCompositeWorkspace()

            `when`("the root srcx-context task runs twice without source changes") {
                val firstResult = workspace.gradle(Srcx.TASK_CONTEXT).build()
                val rootOutput = workspace.resolve(".srcx")
                val relationshipIndex = rootOutput.resolve("relationships/index.md")
                val relationshipPage = rootOutput.relationshipPages().single()
                val firstIndexBytes = relationshipIndex.readBytes()
                val firstPageBytes = relationshipPage.readBytes()
                val contextMarkdown = rootOutput.resolve("context.md").readText()
                val relationshipMarkdown = relationshipPage.readText()
                val graphData = rootOutput.resolve("site/index.html").readText().architectureGraphData()

                workspace.gradle(Srcx.TASK_CONTEXT).build()
                val secondRelationshipPage = rootOutput.relationshipPages().single()

                then("the root owns the selected relationship index and page") {
                    firstResult.task(":${Srcx.TASK_CONTEXT}")?.outcome shouldBe TaskOutcome.SUCCESS
                    relationshipIndex.shouldExist()
                    relationshipPage.shouldExist()
                    relationshipIndex.readText() shouldContain relationshipPage.name
                    relationshipIndex.readText() shouldContain "| 1000 | 0 | 2 | 2 | observed |"
                }

                then("the cumulative page distinguishes local, workspace, and cross-build inbound usage") {
                    relationshipMarkdown shouldContain "# fixture.contract.WorkspaceContract"
                    relationshipMarkdown shouldContain "| Local inbound | 0 |"
                    relationshipMarkdown shouldContain "| Workspace inbound | 2 |"
                    relationshipMarkdown shouldContain "| Cross-build inbound | 2 |"
                    relationshipMarkdown shouldContain
                        "| Workspace-used status | yes - resolved workspace usage observed |"
                    relationshipMarkdown.lowercase() shouldNotContain "globally unused"
                }

                then("each external consumer retains its scoped source evidence") {
                    relationshipMarkdown shouldContain
                        "| fixture.consumer.ContractImplementation " +
                        "(<code>consumer-build</code> / <code>:</code> / <code>main</code>) | implements | " +
                        "yes (<code>consumer-build</code> to <code>contract-build</code>) | DERIVED | " +
                        "<code>consumer-build</code> | <code>:</code> | " +
                        "<code>src/main/kotlin/fixture/consumer/ContractImplementation.kt</code> | 5 | " +
                        "WorkspaceContract |"
                    relationshipMarkdown shouldContain
                        "| fixture.root.RootConsumer " +
                        "(<code>workspace-root</code> / <code>:</code> / <code>main</code>) | parameter type | " +
                        "yes (<code>workspace-root</code> to <code>contract-build</code>) | DERIVED | " +
                        "<code>workspace-root</code> | <code>:</code> | " +
                        "<code>src/main/kotlin/fixture/root/RootConsumer.kt</code> | 5 | WorkspaceContract |"
                }

                then("the root context links both relationship entry points") {
                    contextMarkdown shouldContain "[Workspace Relationships](relationships/index.md)"
                    contextMarkdown shouldContain "[Browse the complete relationship index](relationships/index.md)"
                    contextMarkdown shouldContain "(relationships/${relationshipPage.name})"
                }

                then("the D3 graph data includes all three source clusters and both relationships") {
                    graphData shouldContain "\"qualifiedName\":\"fixture.contract.WorkspaceContract\""
                    graphData shouldContain "\"qualifiedName\":\"fixture.consumer.ContractImplementation\""
                    graphData shouldContain "\"qualifiedName\":\"fixture.root.RootConsumer\""
                    graphData shouldContain "\"label\":\"contract-build / :\""
                    graphData shouldContain "\"label\":\"consumer-build / :\""
                    graphData shouldContain "\"label\":\"workspace-root / :\""
                    Regex("\\\"nodeCount\\\":").findAll(graphData).count() shouldBe 3
                    graphData shouldContain "\"kind\":\"IMPLEMENTS\""
                    graphData shouldContain "\"kind\":\"PARAMETER_TYPE\""
                }

                then("included build output stays a local summary rather than the relationship-document root") {
                    workspace.resolve("contract-build/.srcx/context.md").shouldExist()
                    workspace.resolve("consumer-build/.srcx/context.md").shouldExist()
                    workspace.resolve("contract-build/.srcx/relationships").shouldNotExist()
                    workspace.resolve("consumer-build/.srcx/relationships").shouldNotExist()
                }

                then("the unchanged rerun preserves relationship files byte for byte") {
                    secondRelationshipPage.name shouldBe relationshipPage.name
                    relationshipIndex.readBytes().contentEquals(firstIndexBytes) shouldBe true
                    secondRelationshipPage.readBytes().contentEquals(firstPageBytes) shouldBe true
                }
            }
        }
    })

private fun File.gradle(vararg args: String) =
    GradleRunner
        .create()
        .withProjectDir(this)
        .withPluginClasspath()
        .withArguments(*args, "--stacktrace")

private fun File.relationshipPages(): List<File> =
    resolve("relationships")
        .listFiles()
        .orEmpty()
        .filter { it.isFile && it.extension == "md" && it.name != "index.md" }
        .sortedBy { it.name }

private fun String.architectureGraphData(): String {
    val marker = "<script type=\"application/json\" data-srcx-architecture-data>"
    check(marker in this) { "Architecture graph data was not generated" }
    return substringAfter(marker).substringBefore("</script>")
}

@Suppress("LongMethod")
private fun cumulativeCompositeWorkspace(): File =
    File.createTempFile("srcx-workspace-cumulative", "").apply {
        delete()
        mkdirs()
        deleteOnExit()

        writeFixture(
            "settings.gradle.kts",
            """
            plugins {
                id("zone.clanker.gradle.srcx")
            }
            rootProject.name = "workspace-root"
            includeBuild("contract-build")
            includeBuild("consumer-build")
            """,
        )
        writeFixture("build.gradle.kts", "plugins { base }")
        writeFixture(
            "src/main/kotlin/fixture/root/RootConsumer.kt",
            """
            package fixture.root

            import fixture.contract.WorkspaceContract

            class RootConsumer(private val contract: WorkspaceContract)
            """,
        )

        writeFixture("contract-build/settings.gradle.kts", "rootProject.name = \"contract-build\"")
        writeFixture("contract-build/build.gradle.kts", "plugins { base }")
        writeFixture(
            "contract-build/src/main/kotlin/fixture/contract/WorkspaceContract.kt",
            """
            package fixture.contract

            interface WorkspaceContract
            """,
        )

        writeFixture("consumer-build/settings.gradle.kts", "rootProject.name = \"consumer-build\"")
        writeFixture("consumer-build/build.gradle.kts", "plugins { base }")
        writeFixture(
            "consumer-build/src/main/kotlin/fixture/consumer/ContractImplementation.kt",
            """
            package fixture.consumer

            import fixture.contract.WorkspaceContract

            class ContractImplementation : WorkspaceContract
            """,
        )
    }

private fun File.writeFixture(
    relativePath: String,
    content: String,
) {
    val target = resolve(relativePath)
    target.parentFile.mkdirs()
    target.writeText(content.trimIndent() + "\n")
}
