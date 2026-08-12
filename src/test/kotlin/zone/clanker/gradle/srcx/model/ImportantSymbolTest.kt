package zone.clanker.gradle.srcx.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class ImportantSymbolTest :
    BehaviorSpec({
        val symbol =
            WorkspaceSymbol(
                build = "root",
                project = ":app",
                sourceSet = "main",
                name = "App",
                qualifiedName = "sample.App",
                kind = SymbolDetailKind.CLASS,
                projectRelativeFile = "src/main/kotlin/sample/App.kt",
                declarationLine = 1,
            )
        val usage = WorkspaceSymbolUsage(symbol, emptyList(), emptyList())

        given("an important symbol model") {
            `when`("selection evidence is represented") {
                val result =
                    ImportantSymbol(
                        symbol = symbol,
                        reasons = listOf(ImportantSymbolReason.ENTRY_POINT),
                        score = 1,
                        usage = usage,
                    )

                then("it retains report-safe identity, purpose, score, and usage") {
                    result.symbol.identity shouldBe symbol.identity
                    result.reasons shouldBe listOf(ImportantSymbolReason.ENTRY_POINT)
                    result.reasons.single().label shouldBe "Entry point"
                    result.score shouldBe 1
                    result.usage shouldBe usage
                }
            }

            `when`("typed external signals are omitted") {
                then("all exact identity sets default to empty") {
                    ImportantSymbolSignals() shouldBe
                        ImportantSymbolSignals(
                            entryPoints = emptySet(),
                            cycleParticipants = emptySet(),
                            antiPatternSymbols = emptySet(),
                        )
                }
            }

            `when`("the model is internally inconsistent") {
                val other = symbol.copy(qualifiedName = "sample.Other", name = "Other")

                then("it rejects missing reasons, invalid scores, duplicates, and mismatched usage") {
                    shouldThrow<IllegalArgumentException> { ImportantSymbol(symbol, emptyList(), 1, usage) }
                    shouldThrow<IllegalArgumentException> {
                        ImportantSymbol(symbol, listOf(ImportantSymbolReason.ENTRY_POINT), 0, usage)
                    }
                    shouldThrow<IllegalArgumentException> {
                        ImportantSymbol(
                            symbol,
                            listOf(ImportantSymbolReason.ENTRY_POINT, ImportantSymbolReason.ENTRY_POINT),
                            1,
                            usage,
                        )
                    }
                    shouldThrow<IllegalArgumentException> {
                        ImportantSymbol(
                            symbol,
                            listOf(ImportantSymbolReason.ENTRY_POINT),
                            1,
                            usage.copy(symbol = other),
                        )
                    }
                }
            }
        }
    })
