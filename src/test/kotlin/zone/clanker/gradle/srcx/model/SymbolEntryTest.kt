package zone.clanker.gradle.srcx.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File

/**
 * Tests for [SymbolEntry] data class and value class validations.
 */
class SymbolEntryTest :
    BehaviorSpec({

        given("a SymbolEntry") {

            `when`("created with valid data") {
                val entry =
                    SymbolEntry(
                        name = SymbolName("MyService"),
                        kind = SymbolKind.CLASS,
                        packageName = PackageName("com.example"),
                        filePath = FilePath("MyService.kt"),
                        lineNumber = 5,
                    )

                then("all fields are accessible") {
                    entry.name.value shouldBe "MyService"
                    entry.kind shouldBe SymbolKind.CLASS
                    entry.packageName.value shouldBe "com.example"
                    entry.filePath.value shouldBe "MyService.kt"
                    entry.lineNumber shouldBe 5
                }
            }

            `when`("two entries have the same values") {
                val entry1 =
                    SymbolEntry(SymbolName("Foo"), SymbolKind.FUNCTION, PackageName("com.bar"), FilePath("Foo.kt"), 1)
                val entry2 =
                    SymbolEntry(SymbolName("Foo"), SymbolKind.FUNCTION, PackageName("com.bar"), FilePath("Foo.kt"), 1)

                then("they are equal") {
                    entry1 shouldBe entry2
                }
            }

            `when`("created with different kinds") {
                val classEntry = SymbolEntry(SymbolName("X"), SymbolKind.CLASS, PackageName("p"), FilePath("X.kt"), 1)
                val funcEntry = SymbolEntry(SymbolName("X"), SymbolKind.FUNCTION, PackageName("p"), FilePath("X.kt"), 1)
                val propEntry = SymbolEntry(SymbolName("X"), SymbolKind.PROPERTY, PackageName("p"), FilePath("X.kt"), 1)

                then("each kind is distinct") {
                    classEntry.kind shouldBe SymbolKind.CLASS
                    funcEntry.kind shouldBe SymbolKind.FUNCTION
                    propEntry.kind shouldBe SymbolKind.PROPERTY
                }
            }

            `when`("lineNumber is zero") {
                then("it throws IllegalArgumentException") {
                    val ex =
                        shouldThrow<IllegalArgumentException> {
                            SymbolEntry(SymbolName("X"), SymbolKind.CLASS, PackageName("p"), FilePath("X.kt"), 0)
                        }
                    ex.message shouldContain "lineNumber must be > 0"
                }
            }

            `when`("lineNumber is negative") {
                then("it throws IllegalArgumentException") {
                    shouldThrow<IllegalArgumentException> {
                        SymbolEntry(SymbolName("X"), SymbolKind.CLASS, PackageName("p"), FilePath("X.kt"), -1)
                    }
                }
            }
        }

        given("SymbolKind enum") {

            `when`("listing all values") {
                val values = SymbolKind.entries

                then("there are exactly three kinds") {
                    values.size shouldBe 3
                }
            }
        }

        given("SymbolName value class") {

            `when`("created with valid input") {
                then("it stores the value") {
                    SymbolName("MyService").value shouldBe "MyService"
                }
            }

            `when`("toString is called") {
                then("it returns the raw value") {
                    SymbolName("MyService").toString() shouldBe "MyService"
                }
            }

            `when`("created with blank input") {
                then("it throws IllegalArgumentException") {
                    val ex =
                        shouldThrow<IllegalArgumentException> {
                            SymbolName("")
                        }
                    ex.message shouldContain "SymbolName must not be blank"
                }
            }

            `when`("created with whitespace-only input") {
                then("it throws IllegalArgumentException") {
                    shouldThrow<IllegalArgumentException> {
                        SymbolName("   ")
                    }
                }
            }
        }

        given("PackageName value class") {

            `when`("created with valid input") {
                then("it stores the value") {
                    PackageName("com.example").value shouldBe "com.example"
                }
            }

            `when`("toString is called") {
                then("it returns the raw value") {
                    PackageName("com.example").toString() shouldBe "com.example"
                }
            }

            `when`("created with blank input") {
                then("it throws IllegalArgumentException") {
                    val ex =
                        shouldThrow<IllegalArgumentException> {
                            PackageName("")
                        }
                    ex.message shouldContain "PackageName must not be blank"
                }
            }

            `when`("created with whitespace-only input") {
                then("it throws IllegalArgumentException") {
                    shouldThrow<IllegalArgumentException> {
                        PackageName("  ")
                    }
                }
            }
        }

        given("FilePath value class") {

            `when`("created with valid input") {
                then("it stores the value") {
                    FilePath("com/example/App.kt").value shouldBe "com/example/App.kt"
                }
            }

            `when`("toString is called") {
                then("it returns the raw value") {
                    FilePath("App.kt").toString() shouldBe "App.kt"
                }
            }

            `when`("created with blank input") {
                then("it throws IllegalArgumentException") {
                    val ex =
                        shouldThrow<IllegalArgumentException> {
                            FilePath("")
                        }
                    ex.message shouldContain "FilePath must not be blank"
                }
            }

            `when`("created with whitespace-only input") {
                then("it throws IllegalArgumentException") {
                    shouldThrow<IllegalArgumentException> {
                        FilePath("   ")
                    }
                }
            }
        }

        given("Symbol data class") {
            `when`("created with valid data") {
                val sym =
                    Symbol(
                        name = "MyService",
                        qualifiedName = "com.example.MyService",
                        kind = SymbolDetailKind.CLASS,
                        file = File("/tmp/MyService.kt"),
                        line = 5,
                        packageName = "com.example",
                    )

                then("all fields are accessible") {
                    sym.name shouldBe "MyService"
                    sym.qualifiedName shouldBe "com.example.MyService"
                    sym.kind shouldBe SymbolDetailKind.CLASS
                    sym.line shouldBe 5
                    sym.packageName shouldBe "com.example"
                }
            }
        }

        given("SymbolDetailKind enum") {
            `when`("listing all values") {
                then("there are seven kinds") {
                    SymbolDetailKind.entries.size shouldBe 7
                }
            }

            `when`("checking labels") {
                then("each kind has a label") {
                    SymbolDetailKind.CLASS.label shouldBe "class"
                    SymbolDetailKind.INTERFACE.label shouldBe "interface"
                    SymbolDetailKind.ENUM.label shouldBe "enum"
                    SymbolDetailKind.DATA_CLASS.label shouldBe "data class"
                    SymbolDetailKind.OBJECT.label shouldBe "object"
                    SymbolDetailKind.FUNCTION.label shouldBe "fun"
                    SymbolDetailKind.PROPERTY.label shouldBe "val/var"
                }
            }
        }

        given("Reference data class") {
            `when`("created with valid data") {
                val ref =
                    Reference(
                        targetName = "MyService",
                        targetQualifiedName = "com.example.MyService",
                        kind = ReferenceKind.IMPORT,
                        file = File("/tmp/App.kt"),
                        line = 3,
                        context = "import com.example.MyService",
                    )

                then("all fields are accessible") {
                    ref.targetName shouldBe "MyService"
                    ref.kind shouldBe ReferenceKind.IMPORT
                }
            }
        }

        given("ReferenceKind enum") {
            `when`("listing all values") {
                then("there are six kinds") {
                    ReferenceKind.entries.size shouldBe 6
                }
            }

            `when`("checking labels") {
                then("each kind has a label") {
                    ReferenceKind.IMPORT.label shouldBe "import"
                    ReferenceKind.CALL.label shouldBe "call"
                    ReferenceKind.SUPERTYPE.label shouldBe "extends/implements"
                    ReferenceKind.TYPE_REF.label shouldBe "type"
                    ReferenceKind.CONSTRUCTOR.label shouldBe "constructor"
                    ReferenceKind.NAME_REF.label shouldBe "reference"
                }
            }
        }

        given("MethodCall data class") {
            `when`("created with valid data") {
                val caller = Symbol("foo", "com.a.foo", SymbolDetailKind.FUNCTION, File("/tmp/A.kt"), 1, "com.a")
                val target = Symbol("bar", "com.b.bar", SymbolDetailKind.FUNCTION, File("/tmp/B.kt"), 1, "com.b")
                val call = MethodCall(caller, target, File("/tmp/A.kt"), 5)

                then("all fields are accessible") {
                    call.caller.name shouldBe "foo"
                    call.target.name shouldBe "bar"
                    call.line shouldBe 5
                }
            }
        }

        given("VerifyAssertion data class") {
            `when`("created with valid data") {
                val assertion = VerifyAssertion("symbol-exists", "MyService")

                then("all fields are accessible") {
                    assertion.type shouldBe "symbol-exists"
                    assertion.argument shouldBe "MyService"
                }
            }
        }
    })
