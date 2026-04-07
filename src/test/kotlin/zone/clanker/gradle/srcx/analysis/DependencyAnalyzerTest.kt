package zone.clanker.gradle.srcx.analysis

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.io.File

class DependencyAnalyzerTest :
    BehaviorSpec({

        fun component(
            simpleName: String,
            packageName: String = "com.example",
            imports: List<String> = emptyList(),
            supertypes: List<String> = emptyList(),
        ): ClassifiedComponent {
            val source =
                SourceFileMetadata(
                    file = File("/tmp/$simpleName.kt"),
                    packageName = packageName,
                    qualifiedName = "$packageName.$simpleName",
                    simpleName = simpleName,
                    imports = imports,
                    annotations = emptyList(),
                    supertypes = supertypes,
                    isInterface = false,
                    isAbstract = false,
                    isObject = false,
                    isDataClass = false,
                    language = SourceFileMetadata.Language.KOTLIN,
                    lineCount = 50,
                    methods = emptyList(),
                )
            return ClassifiedComponent(source, ComponentRole.OTHER, "(root)")
        }

        given("buildDependencyGraph") {
            `when`("components import each other") {
                val a = component("A", imports = listOf("com.example.B"))
                val b = component("B")

                val edges = buildDependencyGraph(listOf(a, b))

                then("it creates an edge from A to B") {
                    edges shouldHaveSize 1
                    edges
                        .first()
                        .from.source.simpleName shouldBe "A"
                    edges
                        .first()
                        .to.source.simpleName shouldBe "B"
                }
            }

            `when`("component has supertype") {
                val parent = component("Parent")
                val child = component("Child", supertypes = listOf("Parent"))

                val edges = buildDependencyGraph(listOf(parent, child))

                then("it creates an edge from child to parent") {
                    edges shouldHaveSize 1
                    edges
                        .first()
                        .from.source.simpleName shouldBe "Child"
                    edges
                        .first()
                        .to.source.simpleName shouldBe "Parent"
                }
            }

            `when`("no dependencies exist") {
                val a = component("A")
                val b = component("B")

                val edges = buildDependencyGraph(listOf(a, b))

                then("it returns empty list") {
                    edges.shouldBeEmpty()
                }
            }
        }

        given("findHubClasses") {
            `when`("one component is depended on by many") {
                val hub = component("Hub")
                val a = component("A", imports = listOf("com.example.Hub"))
                val b = component("B", imports = listOf("com.example.Hub"))
                val c = component("C", imports = listOf("com.example.Hub"))

                val components = listOf(hub, a, b, c)
                val edges = buildDependencyGraph(components)
                val hubs = findHubClasses(components, edges)

                then("it identifies the hub") {
                    hubs shouldHaveSize 1
                    hubs
                        .first()
                        .first.source.simpleName shouldBe "Hub"
                    hubs.first().second shouldBe 3
                }
            }
        }

        given("findCycles") {
            `when`("components form a cycle") {
                val a = component("A", imports = listOf("com.example.B"))
                val b = component("B", imports = listOf("com.example.A"))

                val components = listOf(a, b)
                val edges = buildDependencyGraph(components)
                val cycles = findCycles(edges)

                then("it detects the cycle") {
                    cycles.isNotEmpty() shouldBe true
                }
            }

            `when`("no cycles exist") {
                val a = component("A", imports = listOf("com.example.B"))
                val b = component("B")

                val components = listOf(a, b)
                val edges = buildDependencyGraph(components)
                val cycles = findCycles(edges)

                then("it returns empty list") {
                    cycles.shouldBeEmpty()
                }
            }
        }
    })
