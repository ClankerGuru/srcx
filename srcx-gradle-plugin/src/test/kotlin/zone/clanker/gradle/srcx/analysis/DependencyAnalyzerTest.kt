package zone.clanker.gradle.srcx.analysis

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
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

            `when`("a qualified component identity is duplicated across source inputs") {
                val first = component("Shared")
                val second = component("Shared")
                val consumer = component("Consumer", imports = listOf("com.example.Shared"))

                val edges = buildDependencyGraph(listOf(first, second, consumer))

                then("it omits the ambiguous endpoint instead of choosing one by input order") {
                    edges.shouldBeEmpty()
                    buildDependencyGraph(listOf(second, consumer, first)).shouldBeEmpty()
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
                    val detectedHub = hubs.first()
                    detectedHub.component.source.simpleName shouldBe "Hub"
                    detectedHub.count shouldBe 3
                    detectedHub.dependents.map { it.name } shouldBe listOf("A", "B", "C")
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

            `when`("three components close one directed route") {
                val a = component("A", imports = listOf("com.example.B"))
                val b = component("B", imports = listOf("com.example.C"))
                val c = component("C", imports = listOf("com.example.A"))

                val cycles = findQualifiedCycles(buildDependencyGraph(listOf(c, a, b)))

                then("it retains every qualified participant in direction order") {
                    cycles.single() shouldContainExactly
                        listOf("com.example.A", "com.example.B", "com.example.C", "com.example.A")
                }
            }

            `when`("a strongly connected component contains two related routes") {
                val a = component("A", imports = listOf("com.example.B", "com.example.C"))
                val b = component("B", imports = listOf("com.example.A", "com.example.C"))
                val c = component("C", imports = listOf("com.example.A"))

                val cycles = findQualifiedCycles(buildDependencyGraph(listOf(a, b, c)))

                then("deterministic routes cover every internal directed edge") {
                    val routeEdges =
                        cycles.flatMap { cycle -> cycle.zipWithNext() }.toSet()
                    routeEdges shouldBe
                        setOf(
                            "com.example.A" to "com.example.B",
                            "com.example.A" to "com.example.C",
                            "com.example.B" to "com.example.A",
                            "com.example.B" to "com.example.C",
                            "com.example.C" to "com.example.A",
                        )
                    findQualifiedCycles(buildDependencyGraph(listOf(c, b, a))) shouldContainExactly cycles
                }
            }
        }
    })
