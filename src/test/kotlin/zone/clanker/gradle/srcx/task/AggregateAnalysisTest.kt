package zone.clanker.gradle.srcx.task

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.gradle.testfixtures.ProjectBuilder
import zone.clanker.gradle.srcx.model.AnalysisSummary
import zone.clanker.gradle.srcx.model.Finding
import zone.clanker.gradle.srcx.model.FindingSeverity
import zone.clanker.gradle.srcx.model.HubClass
import zone.clanker.gradle.srcx.model.ProjectPath
import zone.clanker.gradle.srcx.model.ProjectSummary

class AggregateAnalysisTest :
    BehaviorSpec({

        fun summaryWithAnalysis(analysis: AnalysisSummary?) =
            ProjectSummary(
                projectPath = ProjectPath(":"),
                symbols = emptyList(),
                dependencies = emptyList(),
                buildFile = "build.gradle.kts",
                sourceDirs = emptyList(),
                subprojects = emptyList(),
                sourceSets = emptyList(),
                analysis = analysis,
            )

        fun createTask(): ContextTask {
            val project = ProjectBuilder.builder().build()
            return project.tasks.create("testTask", ContextTask::class.java)
        }

        given("aggregateAnalysis") {

            `when`("all summaries have no analysis") {
                val task = createTask()
                val summaries = listOf(summaryWithAnalysis(null))
                val result = task.aggregateAnalysis(summaries, emptyMap())

                then("returns null") {
                    result.shouldBeNull()
                }
            }

            `when`("single build has hub classes") {
                val task = createTask()
                val hubs =
                    listOf(
                        HubClass("Foo", 10, "service", "Foo.kt", 1),
                        HubClass("Bar", 5, "", "Bar.kt", 1),
                    )
                val analysis = AnalysisSummary(emptyList(), hubs, emptyList())
                val result =
                    task.aggregateAnalysis(
                        listOf(summaryWithAnalysis(analysis)),
                        emptyMap(),
                    )

                then("returns hubs sorted by dependent count") {
                    result.shouldNotBeNull()
                    result.hubs shouldHaveSize 2
                    result.hubs[0].name shouldBe "Foo"
                    result.hubs[1].name shouldBe "Bar"
                }
            }

            `when`("multiple builds have hub classes") {
                val task = createTask()
                val buildA =
                    AnalysisSummary(
                        emptyList(),
                        listOf(HubClass("Alpha", 20, "", "Alpha.kt", 1)),
                        emptyList(),
                    )
                val buildB =
                    AnalysisSummary(
                        emptyList(),
                        listOf(HubClass("Beta", 30, "", "Beta.kt", 1)),
                        emptyList(),
                    )
                val result =
                    task.aggregateAnalysis(
                        listOf(summaryWithAnalysis(buildA)),
                        mapOf("lib" to listOf(summaryWithAnalysis(buildB))),
                    )

                then("merges and ranks hubs across builds") {
                    result.shouldNotBeNull()
                    result.hubs shouldHaveSize 2
                    result.hubs[0].name shouldBe "Beta"
                    result.hubs[1].name shouldBe "Alpha"
                }
            }

            `when`("findings exist across builds") {
                val task = createTask()
                val finding1 = Finding(FindingSeverity.WARNING, "msg1", "fix1")
                val finding2 = Finding(FindingSeverity.WARNING, "msg2", "fix2")
                val duplicate = Finding(FindingSeverity.WARNING, "msg1", "fix1")
                val buildA = AnalysisSummary(listOf(finding1), emptyList(), emptyList())
                val buildB = AnalysisSummary(listOf(finding2, duplicate), emptyList(), emptyList())
                val result =
                    task.aggregateAnalysis(
                        listOf(summaryWithAnalysis(buildA)),
                        mapOf("lib" to listOf(summaryWithAnalysis(buildB))),
                    )

                then("deduplicates findings by message") {
                    result.shouldNotBeNull()
                    result.findings shouldHaveSize 2
                }
            }

            `when`("cycles exist across builds") {
                val task = createTask()
                val buildA =
                    AnalysisSummary(
                        emptyList(),
                        emptyList(),
                        listOf(listOf("A", "B", "A")),
                    )
                val buildB =
                    AnalysisSummary(
                        emptyList(),
                        emptyList(),
                        listOf(listOf("X", "Y", "X"), listOf("A", "B", "A")),
                    )
                val result =
                    task.aggregateAnalysis(
                        listOf(summaryWithAnalysis(buildA)),
                        mapOf("lib" to listOf(summaryWithAnalysis(buildB))),
                    )

                then("deduplicates cycles") {
                    result.shouldNotBeNull()
                    result.cycles shouldHaveSize 2
                    result.cycles.shouldContainExactly(
                        listOf("A", "B", "A"),
                        listOf("X", "Y", "X"),
                    )
                }
            }

            `when`("some summaries have analysis and some don't") {
                val task = createTask()
                val analysis =
                    AnalysisSummary(
                        emptyList(),
                        listOf(HubClass("Hub", 5, "", "Hub.kt", 1)),
                        emptyList(),
                    )
                val result =
                    task.aggregateAnalysis(
                        listOf(summaryWithAnalysis(null)),
                        mapOf("lib" to listOf(summaryWithAnalysis(analysis))),
                    )

                then("uses available analysis data") {
                    result.shouldNotBeNull()
                    result.hubs shouldHaveSize 1
                    result.hubs[0].name shouldBe "Hub"
                }
            }
        }
    })
