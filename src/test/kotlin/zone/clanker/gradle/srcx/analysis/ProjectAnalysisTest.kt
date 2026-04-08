package zone.clanker.gradle.srcx.analysis

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import zone.clanker.gradle.srcx.model.FindingSeverity
import java.io.File

class ProjectAnalysisTest :
    BehaviorSpec({

        fun tempDir(): File =
            File.createTempFile("srcx-pa", "").apply {
                delete()
                mkdirs()
                deleteOnExit()
            }

        given("analyzeProject") {

            `when`("analyzing a project with classes") {
                val projectDir = tempDir()
                val srcDir = File(projectDir, "src/main/kotlin/com/example")
                srcDir.mkdirs()
                srcDir.resolve("UserService.kt").writeText(
                    """
                    package com.example

                    import com.example.UserRepository

                    class UserService(private val repo: UserRepository) {
                        fun findUser(id: String) = repo.findById(id)
                        fun saveUser(name: String) = repo.save(name)
                        fun deleteUser(id: String) = repo.delete(id)
                    }
                    """.trimIndent(),
                )
                srcDir.resolve("UserRepository.kt").writeText(
                    """
                    package com.example

                    interface UserRepository {
                        fun findById(id: String): String?
                        fun save(name: String): Boolean
                        fun delete(id: String): Boolean
                    }
                    """.trimIndent(),
                )
                srcDir.resolve("UserController.kt").writeText(
                    """
                    package com.example

                    import com.example.UserService

                    class UserController(private val service: UserService) {
                        fun getUser(id: String) = service.findUser(id)
                    }
                    """.trimIndent(),
                )
                srcDir.resolve("UserHelper.kt").writeText(
                    """
                    package com.example

                    class UserHelper {
                        fun formatName(first: String, last: String) = "${'$'}first ${'$'}last"
                    }
                    """.trimIndent(),
                )

                val result =
                    analyzeProject(
                        listOf(File(projectDir, "src/main/kotlin")),
                        projectDir,
                    )

                then("it detects the helper anti-pattern") {
                    result.antiPatterns.any {
                        it.message.contains("UserHelper") && it.message.contains("helper")
                    } shouldBe true
                }

                then("it identifies hub classes") {
                    result.hubs.isNotEmpty() shouldBe true
                }

                then("it classifies roles") {
                    result.roles.isNotEmpty() shouldBe true
                    result.roles["UserHelper"] shouldBe ComponentRole.HELPER
                }

                then("it detects missing tests") {
                    result.antiPatterns.any {
                        it.severity == AntiPattern.Severity.INFO && it.message.contains("no test")
                    } shouldBe true
                }
            }

            `when`("analyzing empty dirs") {
                val result = analyzeProject(emptyList(), File("."))

                then("it returns empty results") {
                    result.antiPatterns.shouldBeEmpty()
                    result.hubs.shouldBeEmpty()
                    result.roles.isEmpty() shouldBe true
                    result.cycles.shouldBeEmpty()
                }
            }
        }

        given("ProjectAnalysis.toSummary") {

            `when`("converting with data") {
                val analysis =
                    ProjectAnalysis(
                        antiPatterns =
                            listOf(
                                AntiPattern(
                                    AntiPattern.Severity.WARNING,
                                    "`FooHelper` is a helper class",
                                    File("Foo.kt"),
                                    "Move methods closer to usage",
                                ),
                                AntiPattern(
                                    AntiPattern.Severity.INFO,
                                    "`Bar` has no test",
                                    File("Bar.kt"),
                                    "Add BarTest",
                                ),
                            ),
                        hubs = listOf("Core" to 5, "Repo" to 3),
                        roles =
                            mapOf(
                                "Core" to ComponentRole.SERVICE,
                                "Repo" to ComponentRole.REPOSITORY,
                                "Other" to ComponentRole.OTHER,
                            ),
                        cycles = listOf(listOf("A", "B", "A")),
                    )

                val summary = analysis.toSummary()

                then("findings are converted") {
                    summary.findings.size shouldBe 2
                    summary.findings[0].severity shouldBe FindingSeverity.WARNING
                    summary.findings[0].message shouldContain "FooHelper"
                    summary.findings[1].severity shouldBe FindingSeverity.INFO
                }

                then("hubs are converted with roles") {
                    summary.hubs.size shouldBe 2
                    summary.hubs[0].name shouldBe "Core"
                    summary.hubs[0].dependents shouldBe 5
                    summary.hubs[0].role shouldBe "service"
                    summary.hubs[1].role shouldBe "repository"
                }

                then("cycles are preserved") {
                    summary.cycles.size shouldBe 1
                    summary.cycles[0] shouldBe listOf("A", "B", "A")
                }
            }

            `when`("converting with OTHER role") {
                val analysis =
                    ProjectAnalysis(
                        antiPatterns = emptyList(),
                        hubs = listOf("Foo" to 1),
                        roles = mapOf("Foo" to ComponentRole.OTHER),
                        cycles = emptyList(),
                    )

                val summary = analysis.toSummary()

                then("OTHER role maps to empty string") {
                    summary.hubs[0].role shouldBe ""
                }
            }
        }
    })
