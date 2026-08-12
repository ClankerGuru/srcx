package zone.clanker.gradle.srcx.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly

class ArchitectureSummaryTest :
    BehaviorSpec({
        fun component(
            id: String,
            isTest: Boolean = false,
        ): ArchitectureComponent =
            ArchitectureComponent(
                id = id,
                name = id.substringAfterLast('.'),
                packageName = id.substringBeforeLast('.', ""),
                packageGroup = "app",
                role = "Service",
                layer = if (isTest) ArchitectureLayer.TEST else ArchitectureLayer.DOMAIN,
                filePath = "${id.substringAfterLast('.')}.kt",
                line = 1,
                isTest = isTest,
            )

        given("runtime architecture evidence") {
            val app = component("example.App")
            val service = component("example.Service")
            val appTest = component("example.AppTest", isTest = true)
            val runtimeEdge = ArchitectureDependency(app.id, service.id)
            val testEdge = ArchitectureDependency(appTest.id, service.id)
            val entryPoint = ArchitectureEntryPoint(app.id, "Declares main()", ArchitectureEntryPointKind.EXPLICIT)
            val summary =
                ArchitectureSummary(
                    components = listOf(app, appTest, service),
                    dependencies = listOf(testEdge, runtimeEdge),
                    entryPoints = listOf(entryPoint),
                )

            `when`("runtime views are read") {
                then("test components and their edges are excluded") {
                    summary.runtimeComponents shouldContainExactly listOf(app, service)
                    summary.runtimeDependencies shouldContainExactly listOf(runtimeEdge)
                }
            }
        }

        given("invalid architecture evidence") {
            val valid = component("example.App")

            `when`("required identities or locations are invalid") {
                then("the model rejects them") {
                    shouldThrow<IllegalArgumentException> { valid.copy(id = "") }
                    shouldThrow<IllegalArgumentException> { valid.copy(name = "") }
                    shouldThrow<IllegalArgumentException> { valid.copy(packageGroup = "") }
                    shouldThrow<IllegalArgumentException> { valid.copy(filePath = "") }
                    shouldThrow<IllegalArgumentException> { valid.copy(line = 0) }
                    shouldThrow<IllegalArgumentException> { ArchitectureDependency("", "target") }
                    shouldThrow<IllegalArgumentException> { ArchitectureDependency("source", "") }
                    shouldThrow<IllegalArgumentException> { ArchitectureDependency("same", "same") }
                    shouldThrow<IllegalArgumentException> {
                        ArchitectureEntryPoint("", "main", ArchitectureEntryPointKind.EXPLICIT)
                    }
                    shouldThrow<IllegalArgumentException> {
                        ArchitectureEntryPoint("example.App", "", ArchitectureEntryPointKind.EXPLICIT)
                    }
                }
            }
        }
    })
