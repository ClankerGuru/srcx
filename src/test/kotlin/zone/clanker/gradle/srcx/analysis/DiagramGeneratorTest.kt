package zone.clanker.gradle.srcx.analysis

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File

class DiagramGeneratorTest :
    BehaviorSpec({

        fun component(
            simpleName: String,
            packageName: String = "com.example",
            packageGroup: String = "(root)",
            imports: List<String> = emptyList(),
        ): ClassifiedComponent {
            val source =
                SourceFileMetadata(
                    file = File("/tmp/$simpleName.kt"),
                    packageName = packageName,
                    qualifiedName = "$packageName.$simpleName",
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
                    methods = emptyList(),
                )
            return ClassifiedComponent(source, ComponentRole.OTHER, packageGroup)
        }

        given("generateDependencyDiagram") {
            `when`("components have dependencies") {
                val a = component("A", imports = listOf("com.example.B"))
                val b = component("B")
                val components = listOf(a, b)
                val edges = buildDependencyGraph(components)

                val diagram = generateDependencyDiagram(components, edges)

                then("it generates a mermaid diagram") {
                    diagram shouldContain "```mermaid"
                    diagram shouldContain "flowchart TD"
                    diagram shouldContain "A"
                    diagram shouldContain "B"
                }
            }

            `when`("no edges exist") {
                val a = component("A")
                val diagram = generateDependencyDiagram(listOf(a), emptyList())

                then("it returns empty string") {
                    diagram shouldBe ""
                }
            }
        }

        given("generateSequenceDiagrams") {
            `when`("no entry points exist") {
                val a = component("Internal")
                val diagram = generateSequenceDiagrams(listOf(a), emptyList())

                then("it returns empty string") {
                    diagram shouldBe ""
                }
            }
        }
    })
