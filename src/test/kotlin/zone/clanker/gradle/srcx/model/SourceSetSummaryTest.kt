package zone.clanker.gradle.srcx.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class SourceSetSummaryTest :
    BehaviorSpec({

        given("SourceSetName") {

            `when`("created with a valid name") {
                then("it holds the value") {
                    SourceSetName("main").value shouldBe "main"
                    SourceSetName("test").value shouldBe "test"
                    SourceSetName("androidTest").value shouldBe "androidTest"
                }

                then("toString returns the name") {
                    SourceSetName("main").toString() shouldBe "main"
                }
            }

            `when`("created with a blank name") {
                then("it throws") {
                    shouldThrow<IllegalArgumentException> {
                        SourceSetName("")
                    }
                    shouldThrow<IllegalArgumentException> {
                        SourceSetName("   ")
                    }
                }
            }
        }

        given("SourceSetSummary") {

            `when`("created with symbols") {
                val symbols =
                    listOf(
                        SymbolEntry(
                            SymbolName("Foo"),
                            SymbolKind.CLASS,
                            PackageName("com.example"),
                            FilePath("Foo.kt"),
                            1,
                        ),
                    )
                val summary =
                    SourceSetSummary(
                        name = SourceSetName("main"),
                        symbols = symbols,
                        sourceDirs = listOf("src/main/kotlin"),
                    )

                then("it holds the data") {
                    summary.name.value shouldBe "main"
                    summary.symbols.size shouldBe 1
                    summary.sourceDirs shouldBe listOf("src/main/kotlin")
                }
            }

            `when`("created with empty symbols") {
                val summary =
                    SourceSetSummary(
                        name = SourceSetName("test"),
                        symbols = emptyList(),
                        sourceDirs = emptyList(),
                    )

                then("it has no symbols") {
                    summary.symbols.size shouldBe 0
                }
            }
        }
    })
