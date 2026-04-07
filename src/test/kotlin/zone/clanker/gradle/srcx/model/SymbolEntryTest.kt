package zone.clanker.gradle.srcx.model

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * Tests for [SymbolEntry] data class behavior.
 */
class SymbolEntryTest :
    BehaviorSpec({

        given("a SymbolEntry") {

            `when`("created with valid data") {
                val entry =
                    SymbolEntry(
                        name = "MyService",
                        kind = SymbolKind.CLASS,
                        pkg = "com.example",
                        file = "MyService.kt",
                        line = 5,
                    )

                then("all fields are accessible") {
                    entry.name shouldBe "MyService"
                    entry.kind shouldBe SymbolKind.CLASS
                    entry.pkg shouldBe "com.example"
                    entry.file shouldBe "MyService.kt"
                    entry.line shouldBe 5
                }
            }

            `when`("two entries have the same values") {
                val entry1 = SymbolEntry("Foo", SymbolKind.FUNCTION, "com.bar", "Foo.kt", 1)
                val entry2 = SymbolEntry("Foo", SymbolKind.FUNCTION, "com.bar", "Foo.kt", 1)

                then("they are equal") {
                    entry1 shouldBe entry2
                }
            }

            `when`("created with different kinds") {
                val classEntry = SymbolEntry("X", SymbolKind.CLASS, "", "X.kt", 1)
                val funcEntry = SymbolEntry("X", SymbolKind.FUNCTION, "", "X.kt", 1)
                val propEntry = SymbolEntry("X", SymbolKind.PROPERTY, "", "X.kt", 1)

                then("each kind is distinct") {
                    classEntry.kind shouldBe SymbolKind.CLASS
                    funcEntry.kind shouldBe SymbolKind.FUNCTION
                    propEntry.kind shouldBe SymbolKind.PROPERTY
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
    })
