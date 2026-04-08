package zone.clanker.gradle.srcx.analysis

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File

class AntiPatternDetectorTest :
    BehaviorSpec({

        val rootDir = File("/tmp/project")

        data class ComponentConfig(
            val simpleName: String,
            val packageName: String = "com.example",
            val annotations: List<String> = emptyList(),
            val imports: List<String> = emptyList(),
            val methods: List<String> = emptyList(),
            val lineCount: Int = 50,
            val isInterface: Boolean = false,
            val isDataClass: Boolean = false,
            val supertypes: List<String> = emptyList(),
        )

        fun component(config: ComponentConfig): ClassifiedComponent {
            val source =
                SourceFileMetadata(
                    file = File(rootDir, "${config.simpleName}.kt"),
                    packageName = config.packageName,
                    qualifiedName = "${config.packageName}.${config.simpleName}",
                    simpleName = config.simpleName,
                    imports = config.imports,
                    annotations = config.annotations,
                    supertypes = config.supertypes,
                    isInterface = config.isInterface,
                    isAbstract = false,
                    isObject = false,
                    isDataClass = config.isDataClass,
                    language = SourceFileMetadata.Language.KOTLIN,
                    lineCount = config.lineCount,
                    methods = config.methods,
                )
            return classifyComponent(source)
        }

        given("detectAntiPatterns") {
            `when`("codebase has a Manager class") {
                val mgr = component(ComponentConfig("SessionManager"))
                val patterns = detectAntiPatterns(listOf(mgr), emptyList(), rootDir)

                then("it detects the smell") {
                    patterns.any { it.message.contains("manager") } shouldBe true
                }
            }

            `when`("codebase has a god class") {
                val god =
                    component(
                        ComponentConfig(
                            simpleName = "GodClass",
                            imports = (1..35).map { "com.example.Dep$it" },
                            methods = (1..30).map { "method$it" },
                            lineCount = 600,
                        ),
                    )
                val patterns = detectAntiPatterns(listOf(god), emptyList(), rootDir)

                then("it detects the god class") {
                    patterns.any { it.message.contains("doing too much") } shouldBe true
                }
            }

            `when`("codebase is clean") {
                val clean =
                    component(
                        ComponentConfig("CleanService", annotations = listOf("Service")),
                    )
                val patterns = detectAntiPatterns(listOf(clean), emptyList(), rootDir)

                then("no warnings are generated") {
                    val warnings =
                        patterns.filter {
                            it.severity == AntiPattern.Severity.WARNING
                        }
                    warnings.shouldBeEmpty()
                }
            }

            `when`("interface has only one implementation") {
                val iface =
                    component(
                        ComponentConfig("Repository", isInterface = true),
                    )
                val impl =
                    component(
                        ComponentConfig("RepositoryImpl", supertypes = listOf("Repository")),
                    )
                val components = listOf(iface, impl)
                val edges = buildDependencyGraph(components)
                val patterns = detectAntiPatterns(components, edges, rootDir)

                then("it detects the single-impl interface") {
                    patterns.any { it.message.contains("only one implementation") } shouldBe true
                }
            }
        }
    })
