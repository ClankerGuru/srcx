package zone.clanker.gradle.srcx.analysis

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
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
            val isAbstract: Boolean = false,
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
                    isAbstract = config.isAbstract,
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

            `when`("class is in a forbidden-named package") {
                val helper =
                    component(
                        ComponentConfig(
                            simpleName = "StringHelper",
                            packageName = "com.example.helpers",
                        ),
                    )
                val patterns = detectAntiPatterns(listOf(helper), emptyList(), rootDir)

                then("it detects forbidden package name") {
                    val forbidden =
                        patterns.filter { it.severity == AntiPattern.Severity.FORBIDDEN }
                    forbidden.any { it.message.contains("forbidden name") } shouldBe true
                    forbidden.any { it.message.contains("helpers") } shouldBe true
                }

                then("smell class in forbidden package gets FORBIDDEN severity") {
                    val smellPatterns =
                        patterns.filter { it.message.contains("helper class") }
                    smellPatterns shouldHaveSize 1
                    smellPatterns[0].severity shouldBe AntiPattern.Severity.FORBIDDEN
                }
            }

            `when`("class is in a normal package with smell name") {
                val helper =
                    component(
                        ComponentConfig(
                            simpleName = "StringHelper",
                            packageName = "com.example.text",
                        ),
                    )
                val patterns = detectAntiPatterns(listOf(helper), emptyList(), rootDir)

                then("smell class gets WARNING severity") {
                    val smellPatterns =
                        patterns.filter { it.message.contains("helper class") }
                    smellPatterns shouldHaveSize 1
                    smellPatterns[0].severity shouldBe AntiPattern.Severity.WARNING
                }

                then("no forbidden package name detected") {
                    patterns.none { it.message.contains("forbidden name") } shouldBe true
                }
            }

            `when`("dependency inversion is violated") {
                val iface =
                    component(
                        ComponentConfig("Dispatcher", isInterface = true),
                    )
                val concrete =
                    component(
                        ComponentConfig(
                            simpleName = "AgentDispatcher",
                            supertypes = listOf("Dispatcher"),
                        ),
                    )
                val consumer =
                    component(
                        ComponentConfig(
                            simpleName = "WorkflowEngine",
                            imports = listOf("com.example.AgentDispatcher"),
                        ),
                    )
                val components = listOf(iface, concrete, consumer)
                val edges = buildDependencyGraph(components)
                val patterns = detectAntiPatterns(components, edges, rootDir)

                then("it detects the dependency inversion violation") {
                    val dipViolation =
                        patterns.filter {
                            it.message.contains("Constructor takes concrete")
                        }
                    dipViolation shouldHaveSize 1
                    dipViolation[0].severity shouldBe AntiPattern.Severity.WARNING
                    dipViolation[0].message shouldBe
                        "Constructor takes concrete `AgentDispatcher` instead of interface `Dispatcher`"
                }
            }

            `when`("dependency is on concrete class without interface") {
                val concrete =
                    component(
                        ComponentConfig(simpleName = "EmailSender"),
                    )
                val consumer =
                    component(
                        ComponentConfig(
                            simpleName = "NotificationService",
                            annotations = listOf("Service"),
                            imports = listOf("com.example.EmailSender"),
                        ),
                    )
                val components = listOf(concrete, consumer)
                val edges = buildDependencyGraph(components)
                val patterns = detectAntiPatterns(components, edges, rootDir)

                then("it suggests extracting an interface") {
                    val suggestions =
                        patterns.filter {
                            it.message.contains("Dependency on concrete class")
                        }
                    suggestions shouldHaveSize 1
                    suggestions[0].severity shouldBe AntiPattern.Severity.INFO
                    suggestions[0].suggestion shouldBe
                        "Consider extracting an interface for `EmailSender`."
                }
            }

            `when`("severity FORBIDDEN has the correct icon") {
                then("FORBIDDEN icon is the no-entry emoji") {
                    AntiPattern.Severity.FORBIDDEN.icon shouldBe "\uD83D\uDEAB"
                }

                then("WARNING icon is the warning emoji") {
                    AntiPattern.Severity.WARNING.icon shouldBe "⚠\uFE0F"
                }

                then("INFO icon is the info emoji") {
                    AntiPattern.Severity.INFO.icon shouldBe "ℹ\uFE0F"
                }
            }
        }
    })
