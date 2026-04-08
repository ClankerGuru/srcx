package zone.clanker.gradle.srcx.parse

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import zone.clanker.gradle.srcx.model.ReferenceKind
import zone.clanker.gradle.srcx.model.SymbolDetailKind
import java.io.File

class PsiParserTest :
    BehaviorSpec({

        fun tempFile(name: String, content: String): File {
            val dir =
                File.createTempFile("psi-test", "").apply {
                    delete()
                    mkdirs()
                    deleteOnExit()
                }
            return File(dir, name).apply { writeText(content) }
        }

        val env = PsiEnvironment()
        val parser = PsiParser(env)

        afterSpec {
            env.close()
        }

        given("Kotlin file parsing") {

            `when`("parsing a class with members") {
                val file =
                    tempFile(
                        "Service.kt",
                        """
                        package com.example

                        data class User(val id: String, val name: String)

                        class Service {
                            val version = "1.0"
                            fun process(): String = "done"
                        }
                        """.trimIndent(),
                    )
                val symbols = parser.extractDeclarations(file)

                then("it extracts classes") {
                    symbols.any { it.name == "User" && it.kind == SymbolDetailKind.DATA_CLASS } shouldBe true
                    symbols.any { it.name == "Service" && it.kind == SymbolDetailKind.CLASS } shouldBe true
                }

                then("it extracts member functions") {
                    symbols.any { it.name == "Service.process" && it.kind == SymbolDetailKind.FUNCTION } shouldBe true
                }

                then("it extracts member properties") {
                    symbols.any { it.name == "Service.version" && it.kind == SymbolDetailKind.PROPERTY } shouldBe true
                }

                then("qualified names include package") {
                    symbols.first { it.name == "Service" }.qualifiedName shouldBe "com.example.Service"
                }
            }

            `when`("parsing an interface") {
                val file =
                    tempFile(
                        "Repository.kt",
                        """
                        package com.example

                        interface Repository {
                            fun findAll(): List<String>
                        }
                        """.trimIndent(),
                    )
                val symbols = parser.extractDeclarations(file)

                then("it detects interface kind") {
                    symbols.first { it.name == "Repository" }.kind shouldBe SymbolDetailKind.INTERFACE
                }
            }

            `when`("parsing an enum") {
                val file =
                    tempFile(
                        "Status.kt",
                        """
                        package com.example
                        enum class Status { ACTIVE, INACTIVE }
                        """.trimIndent(),
                    )
                val symbols = parser.extractDeclarations(file)

                then("it detects enum kind") {
                    symbols.first { it.name == "Status" }.kind shouldBe SymbolDetailKind.ENUM
                }
            }

            `when`("parsing an object") {
                val file =
                    tempFile(
                        "Config.kt",
                        """
                        package com.example
                        object Config {
                            val debug = false
                        }
                        """.trimIndent(),
                    )
                val symbols = parser.extractDeclarations(file)

                then("it detects object kind") {
                    symbols.first { it.name == "Config" }.kind shouldBe SymbolDetailKind.OBJECT
                }
            }

            `when`("parsing top-level functions and properties") {
                val file =
                    tempFile(
                        "Utils.kt",
                        """
                        package com.example
                        fun helper(): String = "help"
                        val version = "1.0"
                        """.trimIndent(),
                    )
                val symbols = parser.extractDeclarations(file)

                then("it extracts top-level function") {
                    symbols.any { it.name == "helper" && it.kind == SymbolDetailKind.FUNCTION } shouldBe true
                }

                then("it extracts top-level property") {
                    symbols.any { it.name == "version" && it.kind == SymbolDetailKind.PROPERTY } shouldBe true
                }
            }
        }

        given("Kotlin references") {

            `when`("parsing imports and supertypes") {
                val file =
                    tempFile(
                        "Impl.kt",
                        """
                        package com.example

                        import com.example.Repository

                        class RepoImpl : Repository {
                            override fun findAll(): List<String> = emptyList()
                        }
                        """.trimIndent(),
                    )
                val refs = parser.extractReferences(file)

                then("it extracts import references") {
                    refs.any { it.kind == ReferenceKind.IMPORT && it.targetName == "Repository" } shouldBe true
                }

                then("it extracts supertype references") {
                    refs.any { it.kind == ReferenceKind.SUPERTYPE && it.targetName == "Repository" } shouldBe true
                }
            }

            `when`("parsing function calls") {
                val file =
                    tempFile(
                        "Caller.kt",
                        """
                        package com.example
                        class Caller {
                            fun run() {
                                println("hello")
                            }
                        }
                        """.trimIndent(),
                    )
                val refs = parser.extractReferences(file)

                then("it extracts call references") {
                    refs.any { it.kind == ReferenceKind.CALL && it.targetName == "println" } shouldBe true
                }
            }
        }

        given("Java file parsing") {

            `when`("parsing a Java class") {
                val file =
                    tempFile(
                        "App.java",
                        """
                        package com.example;

                        public class App {
                            private String name;
                            public void run() {}
                        }
                        """.trimIndent(),
                    )
                val symbols = parser.extractDeclarations(file)

                then("it extracts the class") {
                    symbols.any { it.name == "App" && it.kind == SymbolDetailKind.CLASS } shouldBe true
                }

                then("it extracts methods") {
                    symbols.any { it.name == "App.run" && it.kind == SymbolDetailKind.FUNCTION } shouldBe true
                }

                then("it extracts fields") {
                    symbols.any { it.name == "App.name" && it.kind == SymbolDetailKind.PROPERTY } shouldBe true
                }

                then("qualified names are correct") {
                    symbols.first { it.name == "App" }.qualifiedName shouldBe "com.example.App"
                }
            }

            `when`("parsing a Java interface") {
                val file =
                    tempFile(
                        "Repo.java",
                        """
                        package com.example;
                        public interface Repo {
                            void save(String item);
                        }
                        """.trimIndent(),
                    )
                val symbols = parser.extractDeclarations(file)

                then("it detects interface kind") {
                    symbols.first { it.name == "Repo" }.kind shouldBe SymbolDetailKind.INTERFACE
                }
            }

            `when`("parsing Java imports") {
                then("it extracts import and supertype references") {
                    val file =
                        tempFile(
                            "Ref.java",
                            """
                            package com.example;
                            import com.example.Foo;
                            public class Ref implements Runnable {
                                public void run() {}
                            }
                            """.trimIndent(),
                        )
                    val refs = parser.extractReferences(file)
                    refs.any { it.kind == ReferenceKind.IMPORT && it.targetName == "Foo" } shouldBe true
                    refs.any { it.kind == ReferenceKind.SUPERTYPE && it.targetName == "Runnable" } shouldBe true
                }
            }
        }

        given("gradle.kts file parsing") {

            `when`("parsing a build.gradle.kts") {
                val file =
                    tempFile(
                        "build.gradle.kts",
                        """
                        plugins {
                            kotlin("jvm") version "2.1.20"
                        }
                        dependencies {
                            implementation("com.example:lib:1.0.0")
                        }
                        """.trimIndent(),
                    )
                val symbols = parser.extractDeclarations(file)
                val refs = parser.extractReferences(file)

                then("it parses without error") {
                    // .kts files may not have top-level class declarations
                    // but should parse successfully
                    refs.isNotEmpty() shouldBe true
                }
            }
        }

        given("unsupported file types") {

            `when`("parsing a .txt file") {
                val file = tempFile("readme.txt", "hello world")

                then("it returns empty") {
                    parser.extractDeclarations(file).shouldBeEmpty()
                    parser.extractReferences(file).shouldBeEmpty()
                }
            }
        }
    })
