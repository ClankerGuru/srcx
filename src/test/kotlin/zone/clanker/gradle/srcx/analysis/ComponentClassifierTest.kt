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
        }
    })
