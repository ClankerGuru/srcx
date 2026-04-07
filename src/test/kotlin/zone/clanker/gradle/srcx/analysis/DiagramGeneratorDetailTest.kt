package zone.clanker.gradle.srcx.analysis

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File

class DiagramGeneratorDetailTest :
    BehaviorSpec({

        fun component(
            simpleName: String,
            packageGroup: String = "(root)",
            imports: List<String> = emptyList(),
            role: ComponentRole = ComponentRole.OTHER,
            methods: List<String> = emptyList(),
        ): ClassifiedComponent {
            val source =
                SourceFileMetadata(
                    file = File("/tmp/$simpleName.kt"),
                    packageName = "com.example",
                    qualifiedName = "com.example.$simpleName",
                    simpleName = simpleName,
                    imports = imports,
                    annotations = emptyList(),
                    supertypes = emptyList(),
                    isInterface = false,
                    isAbstract = false,
                    isObject = false,
                    isDataClass = false,
                    language = SourceFileMetadata.Language.KOTLIN,
                    lineCount = 50,
                    methods = methods,
                )
            return ClassifiedComponent(source, role, packageGroup)
        }

        given("generateDependencyDiagram with grouped components") {
            `when`("components are in different groups") {
                val a = component("A", packageGroup = "api", imports = listOf("com.example.B"))
                val b = component("B", packageGroup = "core")
                val components = listOf(a, b)
                val edges = buildDependencyGraph(components)

                val diagram = generateDependencyDiagram(components, edges)

                then("it creates subgraphs for each group") {
                    diagram shouldContain "subgraph api"
                    diagram shouldContain "subgraph core"
                }

                then("it contains edges") {
                    diagram shouldContain "-->"
                }
            }
        }

        given("generateSequenceDiagrams") {
            `when`("entry point has a chain of dependencies") {
                val controller =
                    component(
                        "Controller",
                        role = ComponentRole.CONTROLLER,
                        imports = listOf("com.example.Service"),
                    )
                val service =
                    component(
                        "Service",
                        imports = listOf("com.example.Repo"),
                    )
                val repo = component("Repo")
                val components = listOf(controller, service, repo)
                val edges = buildDependencyGraph(components)

                val diagrams = generateSequenceDiagrams(components, edges)

                then("it generates sequence diagram markdown") {
                    diagrams shouldContain "sequenceDiagram"
                    diagrams shouldContain "participant"
                }

                then("it includes the flow name") {
                    diagrams shouldContain "Controller Flow"
                }
            }

            `when`("entry point has only one node (no chain)") {
                val controller =
                    component(
                        "Solo",
                        role = ComponentRole.CONTROLLER,
                    )
                val components = listOf(controller)

                val diagrams = generateSequenceDiagrams(components, emptyList())

                then("it returns empty since chain is too short") {
                    diagrams shouldBe ""
                }
            }
        }
    })
