package zone.clanker.gradle.srcx.analysis

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File

class AntiPatternDetailTest :
    BehaviorSpec({

        val rootDir = File("/tmp/project")

        data class MetaConfig(
            val simpleName: String,
            val packageName: String = "com.example",
            val annotations: List<String> = emptyList(),
            val imports: List<String> = emptyList(),
            val supertypes: List<String> = emptyList(),
            val methods: List<String> = emptyList(),
            val lineCount: Int = 50,
        )

        fun metadata(config: MetaConfig): SourceFileMetadata =
            SourceFileMetadata(
                file = File(rootDir, "${config.simpleName}.kt"),
                packageName = config.packageName,
                qualifiedName = "${config.packageName}.${config.simpleName}",
                simpleName = config.simpleName,
                imports = config.imports,
                annotations = config.annotations,
                supertypes = config.supertypes,
                isInterface = false,
                isAbstract = false,
                isObject = false,
                isDataClass = false,
                language = SourceFileMetadata.Language.KOTLIN,
                lineCount = config.lineCount,
                methods = config.methods,
            )

        given("circular dependency detection") {
            `when`("two components form a cycle via imports") {
                val sources =
                    listOf(
                        metadata(MetaConfig("A", imports = listOf("com.example.B"))),
                        metadata(MetaConfig("B", imports = listOf("com.example.A"))),
                    )
                val components = classifyAll(sources)
                val edges = buildDependencyGraph(components)
                val patterns =
                    detectAntiPatterns(
                        components, edges, rootDir,
                    )

                then("it reports circular dependency") {
                    patterns.any {
                        it.message.contains("Circular dependency")
                    } shouldBe true
                }
            }
        }

        given("deep inheritance detection") {
            `when`("class has deep inheritance chain") {
                val sources =
                    listOf(
                        metadata(MetaConfig("Base")),
                        metadata(MetaConfig("Level1", supertypes = listOf("Base"))),
                        metadata(MetaConfig("Level2", supertypes = listOf("Level1"))),
                        metadata(MetaConfig("Level3", supertypes = listOf("Level2"))),
                    )
                val components = classifyAll(sources)
                val edges = buildDependencyGraph(components)
                val patterns =
                    detectAntiPatterns(
                        components, edges, rootDir,
                    )

                then("it reports deep inheritance") {
                    patterns.any {
                        it.message.contains("inheritance depth")
                    } shouldBe true
                }
            }
        }

        given("missing tests detection") {
            `when`("annotated components have no tests") {
                val sources =
                    listOf(
                        metadata(MetaConfig("OrderService", annotations = listOf("Service"))),
                        metadata(MetaConfig("PaymentService", annotations = listOf("Service"))),
                    )
                val components = classifyAll(sources)
                val patterns =
                    detectAntiPatterns(
                        components, emptyList(), rootDir,
                    )

                then("it reports missing tests") {
                    patterns.any {
                        it.message.contains("no test")
                    } shouldBe true
                }
            }
        }

        given("Helper class detection") {
            `when`("class is named Helper") {
                val sources = listOf(metadata(MetaConfig("FormatHelper")))
                val components = classifyAll(sources)
                val patterns =
                    detectAntiPatterns(
                        components, emptyList(), rootDir,
                    )

                then("it reports the smell") {
                    patterns.any {
                        it.message.contains("helper")
                    } shouldBe true
                }
            }
        }

        given("Util class detection") {
            `when`("class is named Util") {
                val sources = listOf(metadata(MetaConfig("StringUtil")))
                val components = classifyAll(sources)
                val patterns =
                    detectAntiPatterns(
                        components, emptyList(), rootDir,
                    )

                then("it reports the smell") {
                    patterns.any {
                        it.message.contains("util")
                    } shouldBe true
                }
            }
        }

        given("clean codebase") {
            `when`("no anti-patterns exist") {
                val patterns =
                    detectAntiPatterns(
                        emptyList(), emptyList(), rootDir,
                    )

                then("no findings") {
                    patterns.shouldBeEmpty()
                }
            }
        }
    })
