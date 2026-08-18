package zone.clanker.gradle.srcx

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.file.shouldExist
import io.kotest.matchers.file.shouldNotExist
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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
                val siteHtml = rootOutput.resolve("site/index.html").readText()
                val firstSiteBytes = siteHtml.toByteArray()
                val graphData = siteHtml.architectureGraphData()
                val sourceFiles =
                    Json
                        .parseToJsonElement(graphData)
                        .jsonObject
                        .getValue("sourceFiles")
                        .jsonArray
                val expectedSourceFiles = expectedEmbeddedSources()

                workspace.gradle(Srcx.TASK_CONTEXT).build()
                val secondRelationshipPage = rootOutput.relationshipPages().single()

                then("the root owns the selected relationship index and page") {
                    firstResult.task(":${Srcx.TASK_CONTEXT}")?.outcome shouldBe TaskOutcome.SUCCESS
                    relationshipIndex.shouldExist()
                    relationshipPage.shouldExist()
                    relationshipIndex.readText() shouldContain relationshipPage.name
                    relationshipIndex.readText() shouldContain "| 1000 | 0 | 2 | 2 | observed |"
                }

                then("the cumulative page distinguishes local, workspace, and cross-build inbound relationships") {
                    relationshipMarkdown shouldContain "# fixture.contract.WorkspaceContract"
                    relationshipMarkdown shouldContain "| Local inbound | 0 |"
                    relationshipMarkdown shouldContain "| Workspace inbound | 2 |"
                    relationshipMarkdown shouldContain "| Cross-build inbound | 2 |"
                    relationshipMarkdown shouldContain
                        "| Workspace-referenced status | yes - resolved workspace inbound records observed |"
                    relationshipMarkdown.lowercase() shouldNotContain "globally unused"
                }

                then("each external consumer retains its scoped source evidence") {
                    relationshipMarkdown shouldContain
                        "| fixture.consumer.ContractImplementation " +
                        "(<code>consumer-build</code> / <code>:</code> / <code>main</code>) | implements | " +
                        "yes (<code>consumer-build</code> to <code>contract-build</code>) | DERIVED | " +
                        "<code>consumer-build</code> | <code>:</code> | " +
                        "<code>$SHARED_SOURCE_PATH</code> | 5 | " +
                        "WorkspaceContract |"
                    relationshipMarkdown shouldContain
                        "| fixture.root.RootConsumer " +
                        "(<code>workspace-root</code> / <code>:</code> / <code>main</code>) | parameter type | " +
                        "yes (<code>workspace-root</code> to <code>contract-build</code>) | DERIVED | " +
                        "<code>workspace-root</code> | <code>:</code> | " +
                        "<code>$SHARED_SOURCE_PATH</code> | 5 | WorkspaceContract |"
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

                then("the sourceFiles payload embeds every exact root and included-build source") {
                    val expectedPayload =
                        buildJsonArray {
                            expectedSourceFiles.forEach { sourceFile -> add(sourceFile.toJsonObject()) }
                        }
                    sourceFiles shouldBe expectedPayload
                }

                then("identical project-relative paths retain distinct workspace scopes") {
                    val sourceIds =
                        sourceFiles.map { sourceFile ->
                            sourceFile.jsonObject
                                .getValue("id")
                                .jsonPrimitive
                                .content
                        }

                    sourceIds shouldBe expectedSourceFiles.map(EmbeddedSourceExpectation::id)
                    sourceIds.distinct().size shouldBe 3
                    sourceFiles.count { sourceFile ->
                        sourceFile.jsonObject
                            .getValue("path")
                            .jsonPrimitive
                            .content == SHARED_SOURCE_PATH
                    } shouldBe 3
                }

                then("the generated site safely transports source and remains self-contained") {
                    siteHtml shouldContain "<script data-srcx-vendor=\"d3-7.9.0\">"
                    siteHtml shouldContain "<script data-srcx-owned=\"architecture-graph\">"
                    siteHtml shouldNotContain "<script src="
                    siteHtml shouldContain
                        "<link rel=\"stylesheet\" href=\"${Srcx.HTML_STYLES_FILE}\" data-srcx-theme=\"gort\">"
                    siteHtml shouldNotContain "<style data-srcx-theme=\"gort\">"
                    rootOutput.resolve("site/${Srcx.HTML_STYLES_FILE}").shouldExist()
                    graphData shouldNotContain "</script"
                    graphData shouldNotContain "{{root-source}}"
                    graphData shouldContain "\\u003c/script\\u003e"
                    graphData shouldContain "\\u007b\\u007broot-source\\u007d\\u007d"
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
                    rootOutput
                        .resolve("site/index.html")
                        .readBytes()
                        .contentEquals(firstSiteBytes) shouldBe true
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
            SHARED_SOURCE_PATH,
            ROOT_SOURCE_FIXTURE,
        )

        writeFixture("contract-build/settings.gradle.kts", "rootProject.name = \"contract-build\"")
        writeFixture("contract-build/build.gradle.kts", "plugins { base }")
        writeFixture(
            "contract-build/$SHARED_SOURCE_PATH",
            CONTRACT_SOURCE_FIXTURE,
        )

        writeFixture("consumer-build/settings.gradle.kts", "rootProject.name = \"consumer-build\"")
        writeFixture("consumer-build/build.gradle.kts", "plugins { base }")
        writeFixture(
            "consumer-build/$SHARED_SOURCE_PATH",
            CONSUMER_SOURCE_FIXTURE,
        )
    }

private data class EmbeddedSourceExpectation(
    val build: String,
    val content: String,
    val declarationLines: List<Int>,
    val relationshipLines: List<Int>,
) {
    val id: String = "file::$build:::::main::$SHARED_SOURCE_PATH"

    fun toJsonObject() =
        buildJsonObject {
            put("id", id)
            put("build", build)
            put("project", ":")
            put("sourceSet", "main")
            put("path", SHARED_SOURCE_PATH)
            put("content", content)
            put(
                "declarationLines",
                buildJsonArray { declarationLines.forEach { line -> add(JsonPrimitive(line)) } },
            )
            put(
                "relationshipLines",
                buildJsonArray { relationshipLines.forEach { line -> add(JsonPrimitive(line)) } },
            )
        }
}

private fun expectedEmbeddedSources(): List<EmbeddedSourceExpectation> =
    listOf(
        EmbeddedSourceExpectation(
            build = "contract-build",
            content = fixtureText(CONTRACT_SOURCE_FIXTURE),
            declarationLines = listOf(3),
            relationshipLines = emptyList(),
        ),
        EmbeddedSourceExpectation(
            build = "consumer-build",
            content = fixtureText(CONSUMER_SOURCE_FIXTURE),
            declarationLines = listOf(5),
            relationshipLines = listOf(5),
        ),
        EmbeddedSourceExpectation(
            build = "workspace-root",
            content = fixtureText(ROOT_SOURCE_FIXTURE),
            declarationLines = listOf(5),
            relationshipLines = listOf(5),
        ),
    ).sortedBy(EmbeddedSourceExpectation::id)

private fun File.writeFixture(
    relativePath: String,
    content: String,
) {
    val target = resolve(relativePath)
    target.parentFile.mkdirs()
    target.writeText(fixtureText(content))
}

private fun fixtureText(content: String): String = content.trimIndent() + "\n"

private const val SHARED_SOURCE_PATH = "src/main/kotlin/fixture/shared/ScopedSource.kt"

private const val ROOT_SOURCE_FIXTURE =
    """
    package fixture.root

    import fixture.contract.WorkspaceContract

    class RootConsumer(private val contract: WorkspaceContract)

    // Root source transport sentinel: </script><script src="root.invalid/atlas.js"></script> & {{root-source}} 雪
    """

private const val CONTRACT_SOURCE_FIXTURE =
    """
    package fixture.contract

    interface WorkspaceContract

    // Included contract source: exact scoped payload.
    """

private const val CONSUMER_SOURCE_FIXTURE =
    """
    package fixture.consumer

    import fixture.contract.WorkspaceContract

    class ContractImplementation : WorkspaceContract

    // Included consumer source: exact scoped payload.
    """
