package zone.clanker.gradle.srcx.report

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.string.shouldContain
import zone.clanker.gradle.srcx.model.SymbolDetailKind
import zone.clanker.gradle.srcx.model.UnusedClass

class UnusedClassesRendererTest :
    BehaviorSpec({
        given("unused class candidates") {
            val output =
                UnusedClassesRenderer(
                    listOf(
                        UnusedClass(
                            name = "UnusedService",
                            qualifiedName = "example.UnusedService",
                            kind = SymbolDetailKind.CLASS,
                            buildName = "services",
                            projectPath = ":api",
                            sourceSet = "main",
                            filePath = "../services/api/src/main/kotlin/example/UnusedService.kt",
                            line = 7,
                        ),
                    ),
                ).render()

            then("it renders ownership and source location") {
                output shouldContain "# Potentially Unused Classes"
                output shouldContain "`example.UnusedService`"
                output shouldContain "`services`"
                output shouldContain "`:api`"
                output shouldContain "UnusedService.kt:7"
                output shouldContain "Verify each candidate before deleting it"
            }
        }

        given("no candidates") {
            then("it renders an explicit empty state") {
                UnusedClassesRenderer(emptyList()).render() shouldContain "No potentially unused classes detected."
            }
        }
    })
