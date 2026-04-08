package zone.clanker.gradle.srcx.analysis

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File

class AnalysisIntegrationTest :
    BehaviorSpec({

        fun tempDir(): File =
            File.createTempFile("analysis", "").apply {
                delete()
                mkdirs()
                deleteOnExit()
            }

        given("full architecture analysis pipeline") {
            val dir = tempDir()

            dir.resolve("Controller.kt").writeText(
                """
                package com.example

                @RestController
                class UserController {
                    fun getUser(): String = ""
                    fun listUsers(): String = ""
                }
                """.trimIndent(),
            )
            dir.resolve("Service.kt").writeText(
                """
                package com.example

                import com.example.UserRepository

                @Service
                class UserService {
                    fun process(): String = ""
                }
                """.trimIndent(),
            )
            dir.resolve("Repository.kt").writeText(
                """
                package com.example

                @Repository
                class UserRepository {
                    fun find(): String = ""
                    fun save(): Unit {}
                }
                """.trimIndent(),
            )

            val sources = scanSources(listOf(dir))
            val components = classifyAll(sources)
            val edges = buildDependencyGraph(components)

            `when`("classifying components") {
                then("it assigns correct roles") {
                    val controller =
                        components.first {
                            it.source.simpleName == "UserController"
                        }
                    controller.role shouldBe ComponentRole.CONTROLLER
                }
            }

            `when`("building dependency graph") {
                then("it finds import-based edges") {
                    edges.any {
                        it.from.source.simpleName == "UserService"
                    } shouldBe true
                }
            }

            `when`("finding entry points") {
                val entries = findEntryPoints(components, edges)

                then("it identifies the controller") {
                    entries.any {
                        it.source.simpleName == "UserController"
                    } shouldBe true
                }
            }

            `when`("generating dependency diagram") {
                val diagram = generateDependencyDiagram(components, edges)

                then("it produces mermaid output if edges exist") {
                    if (edges.isNotEmpty()) {
                        diagram shouldContain "mermaid"
                    }
                }
            }

            `when`("finding hub classes") {
                val hubs = findHubClasses(components, edges)

                then("it returns results if edges exist") {
                    if (edges.isNotEmpty()) {
                        hubs shouldHaveAtLeastSize 1
                    }
                }
            }

            `when`("detecting anti-patterns") {
                val patterns =
                    detectAntiPatterns(
                        components, edges, dir,
                    )

                then("it returns findings") {
                    // At minimum, single-impl interface or missing tests
                    patterns.isNotEmpty() shouldBe true
                }
            }

            dir.deleteRecursively()
        }

        given("parseSourceFile edge cases") {
            `when`("file has abstract class") {
                val dir = tempDir()
                val file = dir.resolve("Abstract.kt")
                file.writeText(
                    """
                    package com.example

                    abstract class AbstractBase {
                        abstract fun doWork()
                    }
                    """.trimIndent(),
                )

                val meta = parseSourceFile(file)

                then("it detects abstract modifier") {
                    meta?.isAbstract shouldBe true
                }

                dir.deleteRecursively()
            }

            `when`("file has Java extends and implements") {
                val dir = tempDir()
                val file = dir.resolve("Complex.java")
                file.writeText(
                    """
                    package com.example;

                    public class Complex extends Base implements Runnable {
                        public void run() {}
                    }
                    """.trimIndent(),
                )

                val meta = parseSourceFile(file)

                then("it parses both extends and implements") {
                    meta?.supertypes shouldBe listOf("Base", "Runnable")
                }

                dir.deleteRecursively()
            }

            `when`("file has Kotlin constructor supertypes") {
                val dir = tempDir()
                val file = dir.resolve("Child.kt")
                file.writeText(
                    """
                    package com.example

                    class Child(val name: String) : Parent(), Comparable<Child> {
                    }
                    """.trimIndent(),
                )

                val meta = parseSourceFile(file)

                then("it parses supertypes after constructor") {
                    meta?.supertypes shouldBe listOf("Parent", "Comparable")
                }

                dir.deleteRecursively()
            }
        }
    })
