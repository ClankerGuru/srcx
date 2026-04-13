@file:Suppress("LargeClass")

package zone.clanker.gradle.srcx.analysis

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.io.File

class ComponentClassifierTest :
    BehaviorSpec({

        data class MetadataConfig(
            val simpleName: String,
            val packageName: String = "com.example",
            val annotations: List<String> = emptyList(),
            val isInterface: Boolean = false,
            val isDataClass: Boolean = false,
            val methods: List<String> = emptyList(),
        )

        fun metadata(config: MetadataConfig): SourceFileMetadata =
            SourceFileMetadata(
                file = File("/tmp/${config.simpleName}.kt"),
                packageName = config.packageName,
                qualifiedName = "${config.packageName}.${config.simpleName}",
                simpleName = config.simpleName,
                imports = emptyList(),
                annotations = config.annotations,
                supertypes = emptyList(),
                isInterface = config.isInterface,
                isAbstract = false,
                isObject = false,
                isDataClass = config.isDataClass,
                language = SourceFileMetadata.Language.KOTLIN,
                lineCount = 50,
                methods = config.methods,
            )

        given("classifyComponent") {
            `when`("class has @RestController annotation") {
                val source = metadata(MetadataConfig("UserController", annotations = listOf("RestController")))
                val component = classifyComponent(source)

                then("role is CONTROLLER") {
                    component.role shouldBe ComponentRole.CONTROLLER
                }
            }

            `when`("class has @Service annotation") {
                val source = metadata(MetadataConfig("UserService", annotations = listOf("Service")))
                val component = classifyComponent(source)

                then("role is SERVICE") {
                    component.role shouldBe ComponentRole.SERVICE
                }
            }

            `when`("class has @Repository annotation") {
                val source = metadata(MetadataConfig("UserRepository", annotations = listOf("Repository")))
                val component = classifyComponent(source)

                then("role is REPOSITORY") {
                    component.role shouldBe ComponentRole.REPOSITORY
                }
            }

            `when`("class has @Entity annotation") {
                val source = metadata(MetadataConfig("User", annotations = listOf("Entity")))
                val component = classifyComponent(source)

                then("role is ENTITY") {
                    component.role shouldBe ComponentRole.ENTITY
                }
            }

            `when`("class has @Configuration annotation") {
                val source = metadata(MetadataConfig("AppConfig", annotations = listOf("Configuration")))
                val component = classifyComponent(source)

                then("role is CONFIGURATION") {
                    component.role shouldBe ComponentRole.CONFIGURATION
                }
            }

            `when`("class name ends with Manager") {
                val source = metadata(MetadataConfig("SessionManager"))
                val component = classifyComponent(source)

                then("role is MANAGER") {
                    component.role shouldBe ComponentRole.MANAGER
                }
            }

            `when`("class name ends with Helper") {
                val source = metadata(MetadataConfig("StringHelper"))
                val component = classifyComponent(source)

                then("role is HELPER") {
                    component.role shouldBe ComponentRole.HELPER
                }
            }

            `when`("class name ends with Util") {
                val source = metadata(MetadataConfig("DateUtil"))
                val component = classifyComponent(source)

                then("role is UTIL") {
                    component.role shouldBe ComponentRole.UTIL
                }
            }

            `when`("class has @Dao annotation") {
                val source = metadata(MetadataConfig("UserDao", annotations = listOf("Dao")))
                val component = classifyComponent(source)

                then("role is DAO") {
                    component.role shouldBe ComponentRole.DAO
                }
            }

            `when`("class name ends with Utils") {
                val source = metadata(MetadataConfig("StringUtils"))
                val component = classifyComponent(source)

                then("role is UTIL") {
                    component.role shouldBe ComponentRole.UTIL
                }
            }

            `when`("class name ends with Utility") {
                val source = metadata(MetadataConfig("FormatUtility"))
                val component = classifyComponent(source)

                then("role is UTIL") {
                    component.role shouldBe ComponentRole.UTIL
                }
            }

            `when`("class has @Table annotation") {
                val source = metadata(MetadataConfig("UserTable", annotations = listOf("Table")))
                val component = classifyComponent(source)

                then("role is ENTITY") {
                    component.role shouldBe ComponentRole.ENTITY
                }
            }

            `when`("class has @SpringBootApplication annotation") {
                val source = metadata(MetadataConfig("App", annotations = listOf("SpringBootApplication")))
                val component = classifyComponent(source)

                then("role is CONFIGURATION") {
                    component.role shouldBe ComponentRole.CONFIGURATION
                }
            }

            `when`("class has @RequestMapping annotation") {
                val source = metadata(MetadataConfig("ApiController", annotations = listOf("RequestMapping")))
                val component = classifyComponent(source)

                then("role is CONTROLLER") {
                    component.role shouldBe ComponentRole.CONTROLLER
                }
            }

            `when`("class has @Database annotation") {
                val source = metadata(MetadataConfig("AppDatabase", annotations = listOf("Database")))
                val component = classifyComponent(source)

                then("role is DAO") {
                    component.role shouldBe ComponentRole.DAO
                }
            }

            `when`("class has no annotations and no smell name") {
                val source = metadata(MetadataConfig("BookRepository"))
                val component = classifyComponent(source)

                then("role is OTHER (no annotation detected)") {
                    component.role shouldBe ComponentRole.OTHER
                }
            }
        }

        given("classifyAll") {
            `when`("sources have different packages") {
                val sources =
                    listOf(
                        metadata(MetadataConfig("Service", packageName = "com.example.service")),
                        metadata(MetadataConfig("Controller", packageName = "com.example.controller")),
                        metadata(MetadataConfig("Model", packageName = "com.example.model")),
                    )

                val components = classifyAll(sources)

                then("it computes package groups") {
                    components.map { it.packageGroup }.toSet() shouldBe setOf("service", "controller", "model")
                }
            }

            `when`("all sources share the same package") {
                val sources =
                    listOf(
                        metadata(MetadataConfig("A", packageName = "com.example")),
                        metadata(MetadataConfig("B", packageName = "com.example")),
                    )

                val components = classifyAll(sources)

                then("package group is (root)") {
                    components.all { it.packageGroup == "(root)" } shouldBe true
                }
            }

            `when`("sources have empty package names") {
                val sources =
                    listOf(
                        SourceFileMetadata(
                            file = File("/tmp/A.kt"),
                            packageName = "",
                            qualifiedName = "A",
                            simpleName = "A",
                            imports = emptyList(),
                            annotations = emptyList(),
                            supertypes = emptyList(),
                            isInterface = false,
                            isAbstract = false,
                            isObject = false,
                            isDataClass = false,
                            language = SourceFileMetadata.Language.KOTLIN,
                            lineCount = 10,
                            methods = emptyList(),
                        ),
                    )

                val components = classifyAll(sources)

                then("package group is (root) with empty basePackage") {
                    components.all { it.packageGroup == "(root)" } shouldBe true
                }
            }

            `when`("sources list is empty") {
                val components = classifyAll(emptyList())

                then("it returns empty list") {
                    components shouldBe emptyList()
                }
            }
        }

        given("commonPackagePrefix") {
            `when`("packages share a prefix") {
                val result =
                    commonPackagePrefix(
                        listOf("com.example.service", "com.example.controller", "com.example.model"),
                    )

                then("it returns the common prefix") {
                    result shouldBe "com.example"
                }
            }

            `when`("packages have no common prefix") {
                val result = commonPackagePrefix(listOf("com.foo", "org.bar"))

                then("it returns empty string") {
                    result shouldBe ""
                }
            }

            `when`("list is empty") {
                val result = commonPackagePrefix(emptyList())

                then("it returns empty string") {
                    result shouldBe ""
                }
            }
        }

        given("findEntryPoints") {
            `when`("a component has main() method") {
                val source = metadata(MetadataConfig("App", methods = listOf("main")))
                val components = listOf(classifyComponent(source))

                val entries = findEntryPoints(components)

                then("it returns the main class") {
                    entries.first().source.simpleName shouldBe "App"
                }
            }

            `when`("a component has @Controller annotation") {
                val source = metadata(MetadataConfig("WebController", annotations = listOf("Controller")))
                val components = listOf(classifyComponent(source))

                val entries = findEntryPoints(components)

                then("it returns the controller") {
                    entries.first().source.simpleName shouldBe "WebController"
                }
            }

            `when`("no entry points exist") {
                val source = metadata(MetadataConfig("InternalService"))
                val components = listOf(classifyComponent(source))

                val entries = findEntryPoints(components)

                then("it returns empty list") {
                    entries shouldBe emptyList()
                }
            }

            `when`("no explicit entry points but graph has roots") {
                val srcA = metadata(MetadataConfig("Orchestrator", packageName = "com.example"))
                val srcB = metadata(MetadataConfig("Worker", packageName = "com.example"))
                val compA = classifyComponent(srcA)
                val compB = classifyComponent(srcB)
                val components = listOf(compA, compB)
                val edges = listOf(ClassDependency(compA, compB))

                val entries = findEntryPoints(components, edges)

                then("it falls back to graph roots") {
                    entries.size shouldBe 1
                    entries.first().source.simpleName shouldBe "Orchestrator"
                }
            }

            `when`("no explicit entry points and edges with only data classes as roots") {
                val srcA = metadata(MetadataConfig("Config", packageName = "com.example", isDataClass = true))
                val srcB = metadata(MetadataConfig("Worker", packageName = "com.example"))
                val compA = classifyComponent(srcA)
                val compB = classifyComponent(srcB)
                val components = listOf(compA, compB)
                val edges = listOf(ClassDependency(compA, compB))

                val entries = findEntryPoints(components, edges)

                then("it excludes data classes from roots and returns empty") {
                    entries shouldBe emptyList()
                }
            }
        }

        given("detectLayer") {
            `when`("package ends with controller") {
                then("layer is PRESENTATION") {
                    detectLayer("com.example.controller", isTest = false) shouldBe ArchitecturalLayer.PRESENTATION
                }
            }

            `when`("package ends with task") {
                then("layer is PRESENTATION") {
                    detectLayer("com.example.task", isTest = false) shouldBe ArchitecturalLayer.PRESENTATION
                }
            }

            `when`("package ends with view") {
                then("layer is PRESENTATION") {
                    detectLayer("com.example.view", isTest = false) shouldBe ArchitecturalLayer.PRESENTATION
                }
            }

            `when`("package ends with service") {
                then("layer is DOMAIN") {
                    detectLayer("com.example.service", isTest = false) shouldBe ArchitecturalLayer.DOMAIN
                }
            }

            `when`("package ends with usecase") {
                then("layer is DOMAIN") {
                    detectLayer("com.example.usecase", isTest = false) shouldBe ArchitecturalLayer.DOMAIN
                }
            }

            `when`("package ends with workflow") {
                then("layer is DOMAIN") {
                    detectLayer("com.example.workflow", isTest = false) shouldBe ArchitecturalLayer.DOMAIN
                }
            }

            `when`("package ends with repository") {
                then("layer is DATA") {
                    detectLayer("com.example.repository", isTest = false) shouldBe ArchitecturalLayer.DATA
                }
            }

            `when`("package ends with api") {
                then("layer is DATA") {
                    detectLayer("com.example.api", isTest = false) shouldBe ArchitecturalLayer.DATA
                }
            }

            `when`("package ends with cache") {
                then("layer is DATA") {
                    detectLayer("com.example.cache", isTest = false) shouldBe ArchitecturalLayer.DATA
                }
            }

            `when`("package ends with model") {
                then("layer is MODEL") {
                    detectLayer("com.example.model", isTest = false) shouldBe ArchitecturalLayer.MODEL
                }
            }

            `when`("package ends with entity") {
                then("layer is MODEL") {
                    detectLayer("com.example.entity", isTest = false) shouldBe ArchitecturalLayer.MODEL
                }
            }

            `when`("package ends with dto") {
                then("layer is MODEL") {
                    detectLayer("com.example.dto", isTest = false) shouldBe ArchitecturalLayer.MODEL
                }
            }

            `when`("package ends with config") {
                then("layer is INFRASTRUCTURE") {
                    detectLayer("com.example.config", isTest = false) shouldBe ArchitecturalLayer.INFRASTRUCTURE
                }
            }

            `when`("package ends with di") {
                then("layer is INFRASTRUCTURE") {
                    detectLayer("com.example.di", isTest = false) shouldBe ArchitecturalLayer.INFRASTRUCTURE
                }
            }

            `when`("package ends with plugin") {
                then("layer is INFRASTRUCTURE") {
                    detectLayer("com.example.plugin", isTest = false) shouldBe ArchitecturalLayer.INFRASTRUCTURE
                }
            }

            `when`("isTest flag is true") {
                then("layer is TEST regardless of package name") {
                    detectLayer("com.example.service", isTest = true) shouldBe ArchitecturalLayer.TEST
                }
            }

            `when`("package ends with unrecognized segment") {
                then("layer is OTHER") {
                    detectLayer("com.example.unknown", isTest = false) shouldBe ArchitecturalLayer.OTHER
                }
            }

            `when`("package is empty") {
                then("layer is OTHER") {
                    detectLayer("", isTest = false) shouldBe ArchitecturalLayer.OTHER
                }
            }
        }

        given("detectLayers") {
            `when`("components span multiple packages") {
                val sources =
                    listOf(
                        metadata(MetadataConfig("UserController", packageName = "com.example.controller")),
                        metadata(MetadataConfig("UserService", packageName = "com.example.service")),
                        metadata(MetadataConfig("UserRepo", packageName = "com.example.repository")),
                        metadata(MetadataConfig("User", packageName = "com.example.model")),
                    )
                val components = sources.map { classifyComponent(it) }

                val layers = detectLayers(components)

                then("each package maps to its expected layer") {
                    layers["com.example.controller"] shouldBe ArchitecturalLayer.PRESENTATION
                    layers["com.example.service"] shouldBe ArchitecturalLayer.DOMAIN
                    layers["com.example.repository"] shouldBe ArchitecturalLayer.DATA
                    layers["com.example.model"] shouldBe ArchitecturalLayer.MODEL
                }
            }
        }

        given("detectLayer additional branches") {
            `when`("package ends with ui") {
                then("layer is PRESENTATION") {
                    detectLayer("com.example.ui", isTest = false) shouldBe ArchitecturalLayer.PRESENTATION
                }
            }

            `when`("package ends with screen") {
                then("layer is PRESENTATION") {
                    detectLayer("com.example.screen", isTest = false) shouldBe ArchitecturalLayer.PRESENTATION
                }
            }

            `when`("package ends with route") {
                then("layer is PRESENTATION") {
                    detectLayer("com.example.route", isTest = false) shouldBe ArchitecturalLayer.PRESENTATION
                }
            }

            `when`("package ends with activity") {
                then("layer is PRESENTATION") {
                    detectLayer("com.example.activity", isTest = false) shouldBe ArchitecturalLayer.PRESENTATION
                }
            }

            `when`("package ends with fragment") {
                then("layer is PRESENTATION") {
                    detectLayer("com.example.fragment", isTest = false) shouldBe ArchitecturalLayer.PRESENTATION
                }
            }

            `when`("package ends with interactor") {
                then("layer is DOMAIN") {
                    detectLayer("com.example.interactor", isTest = false) shouldBe ArchitecturalLayer.DOMAIN
                }
            }

            `when`("package ends with datasource") {
                then("layer is DATA") {
                    detectLayer("com.example.datasource", isTest = false) shouldBe ArchitecturalLayer.DATA
                }
            }

            `when`("package ends with db") {
                then("layer is DATA") {
                    detectLayer("com.example.db", isTest = false) shouldBe ArchitecturalLayer.DATA
                }
            }

            `when`("package ends with dao") {
                then("layer is DATA") {
                    detectLayer("com.example.dao", isTest = false) shouldBe ArchitecturalLayer.DATA
                }
            }

            `when`("package ends with entity") {
                then("layer is MODEL") {
                    detectLayer("com.example.entity", isTest = false) shouldBe ArchitecturalLayer.MODEL
                }
            }

            `when`("package ends with domain") {
                then("layer is MODEL") {
                    detectLayer("com.example.domain", isTest = false) shouldBe ArchitecturalLayer.MODEL
                }
            }

            `when`("package ends with configuration") {
                then("layer is INFRASTRUCTURE") {
                    detectLayer("com.example.configuration", isTest = false) shouldBe ArchitecturalLayer.INFRASTRUCTURE
                }
            }

            `when`("package ends with injection") {
                then("layer is INFRASTRUCTURE") {
                    detectLayer("com.example.injection", isTest = false) shouldBe ArchitecturalLayer.INFRASTRUCTURE
                }
            }

            `when`("package ends with test") {
                then("layer is TEST") {
                    detectLayer("com.example.test", isTest = false) shouldBe ArchitecturalLayer.TEST
                }
            }

            `when`("package ends with mock") {
                then("layer is TEST") {
                    detectLayer("com.example.mock", isTest = false) shouldBe ArchitecturalLayer.TEST
                }
            }

            `when`("package ends with fake") {
                then("layer is TEST") {
                    detectLayer("com.example.fake", isTest = false) shouldBe ArchitecturalLayer.TEST
                }
            }

            `when`("package ends with stub") {
                then("layer is TEST") {
                    detectLayer("com.example.stub", isTest = false) shouldBe ArchitecturalLayer.TEST
                }
            }

            `when`("package ends with fixture") {
                then("layer is TEST") {
                    detectLayer("com.example.fixture", isTest = false) shouldBe ArchitecturalLayer.TEST
                }
            }
        }

        given("classifyEntryPoints") {
            `when`("component has main() method") {
                val source = metadata(MetadataConfig("App", methods = listOf("main")))
                val components = listOf(classifyComponent(source))

                val entries = classifyEntryPoints(components)

                then("it classifies as APP entry point") {
                    entries.size shouldBe 1
                    entries.first().kind shouldBe EntryPointKind.APP
                    entries
                        .first()
                        .component
                        .source
                        .simpleName shouldBe "App"
                }
            }

            `when`("component is a controller") {
                val source = metadata(MetadataConfig("WebController", annotations = listOf("Controller")))
                val components = listOf(classifyComponent(source))

                val entries = classifyEntryPoints(components)

                then("it classifies as APP entry point") {
                    entries.first().kind shouldBe EntryPointKind.APP
                }
            }

            `when`("file path contains /test/") {
                val source =
                    SourceFileMetadata(
                        file = File("/project/src/test/kotlin/UserServiceTest.kt"),
                        packageName = "com.example.service",
                        qualifiedName = "com.example.service.UserServiceTest",
                        simpleName = "UserServiceTest",
                        imports = emptyList(),
                        annotations = emptyList(),
                        supertypes = emptyList(),
                        isInterface = false,
                        isAbstract = false,
                        isObject = false,
                        isDataClass = false,
                        language = SourceFileMetadata.Language.KOTLIN,
                        lineCount = 30,
                        methods = listOf("testSomething"),
                    )
                val components = listOf(classifyComponent(source))

                val entries = classifyEntryPoints(components)

                then("it classifies as TEST entry point") {
                    entries.size shouldBe 1
                    entries.first().kind shouldBe EntryPointKind.TEST
                }
            }

            `when`("class name ends with Test") {
                val source = metadata(MetadataConfig("UserServiceTest", methods = listOf("testCreate")))
                val components = listOf(classifyComponent(source))

                val entries = classifyEntryPoints(components)

                then("it classifies as TEST entry point") {
                    entries.first().kind shouldBe EntryPointKind.TEST
                }
            }

            `when`("class name ends with Spec") {
                val source = metadata(MetadataConfig("UserServiceSpec", methods = listOf("testCreate")))
                val components = listOf(classifyComponent(source))

                val entries = classifyEntryPoints(components)

                then("it classifies as TEST entry point") {
                    entries.first().kind shouldBe EntryPointKind.TEST
                }
            }

            `when`("class name contains Mock") {
                val source = metadata(MetadataConfig("MockUserRepository"))
                val components = listOf(classifyComponent(source))

                val entries = classifyEntryPoints(components)

                then("it classifies as MOCK entry point") {
                    entries.first().kind shouldBe EntryPointKind.MOCK
                }
            }

            `when`("class name contains Fake") {
                val source = metadata(MetadataConfig("FakeDatabase"))
                val components = listOf(classifyComponent(source))

                val entries = classifyEntryPoints(components)

                then("it classifies as MOCK entry point") {
                    entries.first().kind shouldBe EntryPointKind.MOCK
                }
            }

            `when`("class name contains Stub") {
                val source = metadata(MetadataConfig("StubApiClient"))
                val components = listOf(classifyComponent(source))

                val entries = classifyEntryPoints(components)

                then("it classifies as MOCK entry point") {
                    entries.first().kind shouldBe EntryPointKind.MOCK
                }
            }

            `when`("no entry points detected") {
                val source = metadata(MetadataConfig("InternalHelper"))
                val components = listOf(classifyComponent(source))

                val entries = classifyEntryPoints(components)

                then("it returns empty list") {
                    entries shouldBe emptyList()
                }
            }

            `when`("class is a Gradle plugin with apply method") {
                val source =
                    SourceFileMetadata(
                        file = File("/tmp/MyPlugin.kt"),
                        packageName = "com.example",
                        qualifiedName = "com.example.MyPlugin",
                        simpleName = "MyPlugin",
                        imports = emptyList(),
                        annotations = emptyList(),
                        supertypes = listOf("org.gradle.api.Plugin"),
                        isInterface = false,
                        isAbstract = false,
                        isObject = false,
                        isDataClass = false,
                        language = SourceFileMetadata.Language.KOTLIN,
                        lineCount = 20,
                        methods = listOf("apply"),
                    )
                val components = listOf(classifyComponent(source))

                val entries = classifyEntryPoints(components)

                then("it classifies as APP entry point") {
                    entries.size shouldBe 1
                    entries.first().kind shouldBe EntryPointKind.APP
                }
            }

            `when`("no classifiable entry points but graph has roots") {
                val srcA = metadata(MetadataConfig("Launcher", packageName = "com.example"))
                val srcB = metadata(MetadataConfig("Worker", packageName = "com.example"))
                val compA = classifyComponent(srcA)
                val compB = classifyComponent(srcB)
                val components = listOf(compA, compB)
                val edges = listOf(ClassDependency(compA, compB))

                val entries = classifyEntryPoints(components, edges)

                then("it falls back to graph roots as APP") {
                    entries.size shouldBe 1
                    entries.first().kind shouldBe EntryPointKind.APP
                    entries
                        .first()
                        .component.source.simpleName shouldBe "Launcher"
                }
            }
        }
    })
