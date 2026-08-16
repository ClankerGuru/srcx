package zone.clanker.gradle.srcx.parse

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import zone.clanker.gradle.srcx.model.DeclarationSemantic
import zone.clanker.gradle.srcx.model.ReferenceEvidence
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

                then("members retain stable signatures and their semantic owner") {
                    val function = symbols.first { it.name == "Service.process" }
                    function.ownerQualifiedName shouldBe "com.example.Service"
                    function.signature shouldBe "fun com.example.Service.process():String"
                }
            }

            `when`("parsing multiline declarations and overloads") {
                val file =
                    tempFile(
                        "Overloads.kt",
                        """
                        package com.example

                        class Formatter {
                            fun render(
                                value: String,
                            ): String {
                                return value
                            }

                            fun render(value: Int): String = value.toString()
                        }
                        """.trimIndent(),
                    )
                val source = file.readText()
                val symbols = parser.extractDeclarations(file)
                val functions = symbols.filter { it.qualifiedName == "com.example.Formatter.render" }

                then("each overload has a distinct semantic signature") {
                    functions.map { it.signature }.toSet() shouldBe
                        setOf(
                            "fun com.example.Formatter.render(String):String",
                            "fun com.example.Formatter.render(Int):String",
                        )
                }

                then("the exact PSI range spans the complete multiline declaration") {
                    val multiline = functions.single { "render(String)" in it.signature }
                    val range = requireNotNull(multiline.declarationRange)
                    source.substring(range.startOffset, range.endOffsetExclusive) shouldContain
                        "fun render(\n        value: String,\n    ): String {\n        return value\n    }"
                    multiline.ownerQualifiedName shouldBe "com.example.Formatter"
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

                then("it retains the explicit interface declaration form") {
                    symbols.first { it.name == "Repository" }.declarationSemantic shouldBe
                        DeclarationSemantic.INTERFACE
                }
            }

            `when`("parsing abstract, sealed, and concrete classes") {
                val file =
                    tempFile(
                        "ClassForms.kt",
                        """
                        package com.example

                        abstract class AbstractWorker
                        sealed class SealedResult
                        class ConcreteWorker
                        """.trimIndent(),
                    )
                val symbols = parser.extractDeclarations(file).associateBy { it.name }

                then("it retains abstract and sealed class semantics") {
                    symbols.getValue("AbstractWorker").declarationSemantic shouldBe
                        DeclarationSemantic.ABSTRACT_CLASS
                    symbols.getValue("SealedResult").declarationSemantic shouldBe
                        DeclarationSemantic.ABSTRACT_CLASS
                }

                then("it distinguishes an ordinary concrete class") {
                    symbols.getValue("ConcreteWorker").declarationSemantic shouldBe
                        DeclarationSemantic.CONCRETE_CLASS
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

                then("it identifies the language-managed singleton form") {
                    symbols.first { it.name == "Config" }.declarationSemantic shouldBe
                        DeclarationSemantic.SINGLETON_OBJECT
                }

                then("its members link to the singleton declaration") {
                    symbols.first { it.name == "Config.debug" }.ownerQualifiedName shouldBe "com.example.Config"
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

            `when`("parsing enum entries and named supertypes") {
                val file =
                    tempFile(
                        "State.kt",
                        """
                        package com.example

                        interface Marker

                        enum class State(val label: String) : Marker {
                            CURRENT("Current"),
                            STALE("Stale"),
                        }

                        class Consumer : MissingPolicy
                        """.trimIndent(),
                    )
                val refs = parser.extractReferences(file)

                then("enum constructor initializers do not become blank supertype facts") {
                    refs.none { it.targetName.isBlank() } shouldBe true
                }

                then("real and unresolved named supertypes remain reference facts") {
                    refs.any { it.kind == ReferenceKind.SUPERTYPE && it.targetName == "Marker" } shouldBe true
                    refs.any { it.kind == ReferenceKind.SUPERTYPE && it.targetName == "MissingPolicy" } shouldBe true
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

                then("it captures the exact Kotlin call target range") {
                    val call = refs.first { it.kind == ReferenceKind.CALL && it.targetName == "println" }
                    val range = requireNotNull(call.occurrenceRange)
                    file.readText().substring(range.startOffset, range.endOffsetExclusive) shouldBe "println"
                }
            }

            `when`("parsing declaration-owned references and types") {
                val file =
                    tempFile(
                        "Owned.kt",
                        """
                        package com.example

                        import sample.Parent
                        import sample.Input
                        import sample.Output
                        import sample.State

                        class Owned : Parent {
                            val state: State = State()

                            fun execute(input: Input): Output {
                                input as State
                                consume(input)
                                return Output()
                            }
                        }
                        """.trimIndent(),
                    )
                val facts = parser.extractFacts(file)
                val refs = facts.references

                then("extractFacts returns declarations and references together") {
                    facts.declarations.any { it.qualifiedName == "com.example.Owned.execute" } shouldBe true
                    refs.isNotEmpty() shouldBe true
                }

                then("the existing extraction APIs remain compatible") {
                    parser.extractDeclarations(file) shouldBe facts.declarations
                    parser.extractReferences(file) shouldBe facts.references
                }

                then("supertypes retain their containing class") {
                    val reference = refs.first { it.kind == ReferenceKind.SUPERTYPE && it.targetName == "Parent" }
                    reference.sourceQualifiedName shouldBe "com.example.Owned"
                }

                then("property, parameter, and return types are precise") {
                    refs
                        .first { it.kind == ReferenceKind.PROPERTY_TYPE && it.targetName == "State" }
                        .sourceQualifiedName shouldBe "com.example.Owned.state"
                    refs
                        .first { it.kind == ReferenceKind.PARAMETER_TYPE && it.targetName == "Input" }
                        .sourceQualifiedName shouldBe "com.example.Owned.execute"
                    refs
                        .first { it.kind == ReferenceKind.RETURN_TYPE && it.targetName == "Output" }
                        .sourceQualifiedName shouldBe "com.example.Owned.execute"
                }

                then("calls and heuristic constructors retain their containing function") {
                    refs
                        .first { it.kind == ReferenceKind.CALL && it.targetName == "consume" }
                        .sourceQualifiedName shouldBe "com.example.Owned.execute"
                    val constructor = refs.first { it.kind == ReferenceKind.CONSTRUCTOR && it.targetName == "Output" }
                    constructor.sourceQualifiedName shouldBe "com.example.Owned.execute"
                    constructor.evidence shouldBe ReferenceEvidence.HEURISTIC
                }

                then("property constructors and name references retain their nearest declaration") {
                    refs
                        .first { it.kind == ReferenceKind.CONSTRUCTOR && it.targetName == "State" }
                        .sourceQualifiedName shouldBe "com.example.Owned.state"
                    refs
                        .first {
                            it.kind == ReferenceKind.NAME_REF &&
                                it.targetName == "Output" &&
                                it.sourceQualifiedName != null
                        }.sourceQualifiedName shouldBe "com.example.Owned.execute"
                }

                then("types outside declaration signatures preserve the generic type kind") {
                    refs
                        .first { it.kind == ReferenceKind.TYPE_REF && it.targetName == "State" }
                        .sourceQualifiedName shouldBe "com.example.Owned.execute"
                }

                then("imports have no source declaration") {
                    refs.filter { it.kind == ReferenceKind.IMPORT }.all { it.sourceQualifiedName == null } shouldBe true
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

                then("Java declarations retain owner, signature, and exact range evidence") {
                    val source = file.readText()
                    val app = symbols.first { it.name == "App" }
                    val method = symbols.first { it.name == "App.run" }
                    val field = symbols.first { it.name == "App.name" }
                    method.ownerQualifiedName shouldBe app.qualifiedName
                    field.ownerQualifiedName shouldBe app.qualifiedName
                    method.signature shouldContain "com.example.App.run()"
                    val range = requireNotNull(method.declarationRange)
                    source.substring(range.startOffset, range.endOffsetExclusive) shouldBe "public void run() {}"
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

                then("it retains the explicit interface declaration form") {
                    symbols.first { it.name == "Repo" }.declarationSemantic shouldBe
                        DeclarationSemantic.INTERFACE
                }
            }

            `when`("parsing abstract and concrete Java classes") {
                val file =
                    tempFile(
                        "ClassForms.java",
                        """
                        package com.example;

                        abstract class AbstractWorker {}
                        class ConcreteWorker {}
                        """.trimIndent(),
                    )
                val symbols = parser.extractDeclarations(file).associateBy { it.name }

                then("it retains the Java abstract modifier") {
                    symbols.getValue("AbstractWorker").declarationSemantic shouldBe
                        DeclarationSemantic.ABSTRACT_CLASS
                }

                then("it distinguishes an ordinary Java class") {
                    symbols.getValue("ConcreteWorker").declarationSemantic shouldBe
                        DeclarationSemantic.CONCRETE_CLASS
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

            `when`("parsing Java calls, constructors, and declaration types") {
                val file =
                    tempFile(
                        "JavaOwned.java",
                        """
                        package com.example;

                        import sample.Contract;
                        import sample.Input;
                        import sample.Result;
                        import sample.State;
                        import static sample.Worker.run;

                        class JavaOwned implements Contract {
                            private State state;

                            Result execute(Input input) {
                                State local = state;
                                run();
                                return new Result();
                            }
                        }
                        """.trimIndent(),
                    )
                val refs = parser.extractFacts(file).references

                then("Java type positions retain precise owning declarations") {
                    refs
                        .first { it.kind == ReferenceKind.PROPERTY_TYPE && it.targetName == "State" }
                        .sourceQualifiedName shouldBe "com.example.JavaOwned.state"
                    refs
                        .first { it.kind == ReferenceKind.PARAMETER_TYPE && it.targetName == "Input" }
                        .sourceQualifiedName shouldBe "com.example.JavaOwned.execute"
                    refs
                        .first { it.kind == ReferenceKind.RETURN_TYPE && it.targetName == "Result" }
                        .sourceQualifiedName shouldBe "com.example.JavaOwned.execute"
                }

                then("Java method calls and new expressions are direct PSI facts") {
                    val call = refs.first { it.kind == ReferenceKind.CALL && it.targetName == "run" }
                    call.targetQualifiedName shouldBe "sample.Worker.run"
                    call.sourceQualifiedName shouldBe "com.example.JavaOwned.execute"
                    val constructor = refs.first { it.kind == ReferenceKind.CONSTRUCTOR && it.targetName == "Result" }
                    constructor.targetQualifiedName shouldBe "sample.Result"
                    constructor.sourceQualifiedName shouldBe "com.example.JavaOwned.execute"
                    constructor.evidence shouldBe ReferenceEvidence.DIRECT
                    val source = file.readText()
                    val callRange = requireNotNull(call.occurrenceRange)
                    source.substring(callRange.startOffset, callRange.endOffsetExclusive) shouldBe "run"
                    val constructorRange = requireNotNull(constructor.occurrenceRange)
                    source.substring(constructorRange.startOffset, constructorRange.endOffsetExclusive) shouldBe
                        "Result"
                }

                then("Java name references retain their containing method") {
                    refs
                        .first { it.kind == ReferenceKind.NAME_REF && it.targetName == "state" }
                        .sourceQualifiedName shouldBe "com.example.JavaOwned.execute"
                }

                then("Java local variable types preserve the generic type kind") {
                    refs
                        .first {
                            it.kind == ReferenceKind.TYPE_REF &&
                                it.targetName == "State" &&
                                it.sourceQualifiedName != null
                        }.sourceQualifiedName shouldBe "com.example.JavaOwned.execute"
                }

                then("Java supertypes retain their containing class") {
                    refs
                        .first { it.kind == ReferenceKind.SUPERTYPE && it.targetName == "Contract" }
                        .sourceQualifiedName shouldBe "com.example.JavaOwned"
                }

                then("Java imports have no source declaration") {
                    refs.filter { it.kind == ReferenceKind.IMPORT }.all { it.sourceQualifiedName == null } shouldBe true
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
                    parser.extractFacts(file).declarations.shouldBeEmpty()
                    parser.extractFacts(file).references.shouldBeEmpty()
                }
            }
        }
    })
