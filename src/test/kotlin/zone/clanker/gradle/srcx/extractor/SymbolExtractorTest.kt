package zone.clanker.gradle.srcx.extractor

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import zone.clanker.gradle.srcx.model.SymbolKind
import java.io.File

/**
 * Tests for [SymbolExtractor] source parsing behavior.
 */
class SymbolExtractorTest :
    BehaviorSpec({

        fun tempProjectWithSource(content: String): File {
            val dir =
                File.createTempFile("srcx-sym", "").apply {
                    delete()
                    mkdirs()
                    deleteOnExit()
                }
            val srcDir = File(dir, "src/main/kotlin/com/example")
            srcDir.mkdirs()
            srcDir.resolve("Sample.kt").writeText(content)
            return dir
        }

        given("a SymbolExtractor") {

            `when`("extracting from a file with a class") {
                val projectDir =
                    tempProjectWithSource(
                        """
                        package com.example

                        class MyService {
                            fun doWork(): String = "done"
                        }
                        """.trimIndent(),
                    )
                val extractor = SymbolExtractor(projectDir)
                val symbols = extractor.extract()

                then("it finds the class") {
                    val classes = symbols.filter { it.kind == SymbolKind.CLASS }
                    classes shouldHaveSize 1
                    classes[0].name shouldBe "MyService"
                    classes[0].pkg shouldBe "com.example"
                }

                then("it finds the function") {
                    val functions = symbols.filter { it.kind == SymbolKind.FUNCTION }
                    functions shouldHaveSize 1
                    functions[0].name shouldBe "doWork"
                }

                projectDir.deleteRecursively()
            }

            `when`("extracting from a file with properties") {
                val projectDir =
                    tempProjectWithSource(
                        """
                        package com.example

                        class Config {
                            val name = "srcx"
                            var count = 0
                        }
                        """.trimIndent(),
                    )
                val extractor = SymbolExtractor(projectDir)
                val symbols = extractor.extract()

                then("it finds both properties") {
                    val props = symbols.filter { it.kind == SymbolKind.PROPERTY }
                    props shouldHaveSize 2
                    props.map { it.name }.toSet() shouldBe setOf("name", "count")
                }

                projectDir.deleteRecursively()
            }

            `when`("extracting from an empty project") {
                val dir =
                    File.createTempFile("srcx-empty", "").apply {
                        delete()
                        mkdirs()
                        deleteOnExit()
                    }
                val extractor = SymbolExtractor(dir)
                val symbols = extractor.extract()

                then("it returns an empty list") {
                    symbols.shouldBeEmpty()
                }

                dir.deleteRecursively()
            }

            `when`("extracting from a file with multiple classes") {
                val projectDir =
                    tempProjectWithSource(
                        """
                        package com.example

                        interface Service {
                            fun execute()
                        }

                        data class Result(val value: String)

                        object Registry
                        """.trimIndent(),
                    )
                val extractor = SymbolExtractor(projectDir)
                val symbols = extractor.extract()

                then("it finds all class-like declarations") {
                    val classes = symbols.filter { it.kind == SymbolKind.CLASS }
                    classes.map { it.name }.toSet() shouldBe setOf("Service", "Result", "Registry")
                }

                projectDir.deleteRecursively()
            }

            `when`("extracting from Java source files") {
                val dir =
                    File.createTempFile("srcx-java", "").apply {
                        delete()
                        mkdirs()
                        deleteOnExit()
                    }
                val srcDir = File(dir, "src/main/java/com/example")
                srcDir.mkdirs()
                srcDir.resolve("Hello.java").writeText(
                    """
                    package com.example;

                    public class Hello {
                        public void greet() {}
                    }
                    """.trimIndent(),
                )
                val extractor = SymbolExtractor(dir)
                val symbols = extractor.extract()

                then("it finds Java class and method") {
                    val classes = symbols.filter { it.kind == SymbolKind.CLASS }
                    classes shouldHaveSize 1
                    classes[0].name shouldBe "Hello"
                }

                dir.deleteRecursively()
            }

            `when`("querying source dir names") {
                val projectDir = tempProjectWithSource("package com.example\nclass X")
                val extractor = SymbolExtractor(projectDir)

                then("it returns existing source dirs") {
                    val dirs = extractor.sourceDirNames()
                    dirs shouldBe listOf("src/main/kotlin")
                }

                projectDir.deleteRecursively()
            }

            `when`("extracting line numbers") {
                val projectDir =
                    tempProjectWithSource(
                        """
                        package com.example

                        class Positioned {
                            fun atLine(): String = "here"
                        }
                        """.trimIndent(),
                    )
                val extractor = SymbolExtractor(projectDir)
                val symbols = extractor.extract()

                then("line numbers are correct") {
                    val cls = symbols.first { it.kind == SymbolKind.CLASS }
                    cls.line shouldBe 3
                    val func = symbols.first { it.kind == SymbolKind.FUNCTION }
                    func.line shouldBe 4
                }

                projectDir.deleteRecursively()
            }
        }
    })
