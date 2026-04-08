package zone.clanker.gradle.srcx.parse

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import zone.clanker.gradle.srcx.model.Reference
import zone.clanker.gradle.srcx.model.ReferenceKind
import zone.clanker.gradle.srcx.model.Symbol
import zone.clanker.gradle.srcx.model.SymbolDetailKind
import java.io.File

class SymbolIndexTest :
    BehaviorSpec({

        fun tempKotlinFile(name: String, content: String): File =
            File.createTempFile(name, ".kt").apply {
                writeText(content)
                deleteOnExit()
            }

        given("build") {
            `when`("building from kotlin files") {
                val serviceFile =
                    tempKotlinFile(
                        "Service",
                        """
                        package com.example

                        class Service {
                            fun process(): String = "done"
                        }
                        """.trimIndent(),
                    )

                val callerFile =
                    tempKotlinFile(
                        "Caller",
                        """
                        package com.example

                        import com.example.Service

                        class Caller {
                            fun run() {
                                val svc = Service()
                                svc.process()
                            }
                        }
                        """.trimIndent(),
                    )

                val index = SymbolIndex.build(listOf(serviceFile, callerFile))

                then("it finds symbols") {
                    index.symbols.any { it.name == "Service" } shouldBe true
                }

                then("it finds references") {
                    index.references.any {
                        it.targetName == "Service" && it.kind == ReferenceKind.IMPORT
                    } shouldBe true
                }

                serviceFile.delete()
                callerFile.delete()
            }

            `when`("building from empty file list") {
                val index = SymbolIndex.build(emptyList())

                then("index is empty") {
                    index.symbols.shouldBeEmpty()
                    index.references.shouldBeEmpty()
                }
            }
        }

        given("resolve") {
            val fileA = File("/tmp/test/A.kt")
            val fileB = File("/tmp/test/B.kt")

            val symbols =
                listOf(
                    Symbol(
                        "MyService", "com.example.MyService",
                        SymbolDetailKind.CLASS, fileA, 1, "com.example",
                    ),
                    Symbol(
                        "Other", "com.other.Other",
                        SymbolDetailKind.CLASS, fileB, 1, "com.other",
                    ),
                )

            val refs =
                listOf(
                    Reference(
                        "MyService", "com.example.MyService",
                        ReferenceKind.IMPORT, fileB, 2, "import com.example.MyService",
                    ),
                    Reference(
                        "MyService", null,
                        ReferenceKind.TYPE_REF, fileB, 5, "val svc: MyService",
                    ),
                )

            val index = SymbolIndex(symbols, refs)

            `when`("reference has qualified name") {
                val ref = refs[0]
                val resolved = index.resolve(ref)

                then("it resolves to the correct symbol") {
                    resolved shouldNotBe null
                    resolved?.qualifiedName shouldBe "com.example.MyService"
                }
            }

            `when`("reference has only simple name with unique match") {
                val uniqueSymbols =
                    listOf(
                        Symbol("Unique", "com.example.Unique", SymbolDetailKind.CLASS, fileA, 1, "com.example"),
                    )
                val uniqueRefs =
                    listOf(
                        Reference("Unique", null, ReferenceKind.TYPE_REF, fileB, 5, "val u: Unique"),
                    )
                val uniqueIndex = SymbolIndex(uniqueSymbols, uniqueRefs)
                val resolved = uniqueIndex.resolve(uniqueRefs[0])

                then("it resolves via simple name") {
                    resolved shouldNotBe null
                    resolved?.qualifiedName shouldBe "com.example.Unique"
                }
            }
        }

        given("findUsages") {
            val fileA = File("/tmp/test/A.kt")
            val fileB = File("/tmp/test/B.kt")

            val symbols =
                listOf(
                    Symbol("MyService", "com.example.MyService", SymbolDetailKind.CLASS, fileA, 1, "com.example"),
                )
            val refs =
                listOf(
                    Reference(
                        "MyService", "com.example.MyService",
                        ReferenceKind.IMPORT, fileB, 2, "import com.example.MyService",
                    ),
                    Reference(
                        "MyService", null,
                        ReferenceKind.TYPE_REF, fileB, 5, "val svc: MyService",
                    ),
                )
            val index = SymbolIndex(symbols, refs)

            `when`("searching by qualified name") {
                val usages = index.findUsages("com.example.MyService")

                then("it finds usages") {
                    usages shouldHaveSize 2
                }
            }

            `when`("searching for non-existent symbol") {
                val usages = index.findUsages("com.example.NonExistent")

                then("it returns empty list") {
                    usages.shouldBeEmpty()
                }
            }
        }

        given("findUsagesByName") {
            val fileA = File("/tmp/test/A.kt")
            val fileB = File("/tmp/test/B.kt")

            val symbols =
                listOf(
                    Symbol(
                        "MyService", "com.example.MyService",
                        SymbolDetailKind.CLASS, fileA, 1, "com.example",
                    ),
                )
            val refs =
                listOf(
                    Reference(
                        "MyService", "com.example.MyService",
                        ReferenceKind.IMPORT, fileB, 2, "import com.example.MyService",
                    ),
                )
            val index = SymbolIndex(symbols, refs)

            `when`("searching by simple name") {
                val results = index.findUsagesByName("MyService")

                then("it returns matching symbols with usages") {
                    results shouldHaveSize 1
                    results.first().first.name shouldBe "MyService"
                    results.first().second shouldHaveSize 1
                }
            }
        }

        given("symbolsInFile") {
            val fileA = File("/tmp/test/A.kt")

            val symbols =
                listOf(
                    Symbol("Foo", "com.example.Foo", SymbolDetailKind.CLASS, fileA, 1, "com.example"),
                    Symbol("Bar", "com.example.Bar", SymbolDetailKind.CLASS, fileA, 5, "com.example"),
                )
            val index = SymbolIndex(symbols, emptyList())

            `when`("querying a specific file") {
                val result = index.symbolsInFile(fileA)

                then("it returns all symbols in that file") {
                    result shouldHaveSize 2
                }
            }
        }

        given("usageCounts") {
            val fileA = File("/tmp/test/A.kt")
            val fileB = File("/tmp/test/B.kt")

            val symbols =
                listOf(
                    Symbol(
                        "MyService", "com.example.MyService",
                        SymbolDetailKind.CLASS, fileA, 1, "com.example",
                    ),
                )
            val refs =
                listOf(
                    Reference(
                        "MyService", "com.example.MyService",
                        ReferenceKind.IMPORT, fileB, 2, "import com.example.MyService",
                    ),
                )
            val index = SymbolIndex(symbols, refs)

            `when`("computing usage counts") {
                val counts = index.usageCounts()

                then("it returns symbols with their usage counts") {
                    counts shouldHaveSize 1
                    counts.first().second shouldBe 1
                }
            }
        }

        given("callGraph") {
            `when`("building call graph from files with method calls") {
                val file =
                    tempKotlinFile(
                        "CallTest",
                        """
                        package com.example

                        class Caller {
                            fun doWork() {
                                helper()
                            }
                        }

                        class Callee {
                            fun helper() {}
                        }
                        """.trimIndent(),
                    )

                val index = SymbolIndex.build(listOf(file))
                val calls = index.callGraph()

                then("it detects method calls") {
                    // The call graph should find the doWork -> helper call
                    calls.isNotEmpty() shouldBe true
                }

                file.delete()
            }
        }
    })
