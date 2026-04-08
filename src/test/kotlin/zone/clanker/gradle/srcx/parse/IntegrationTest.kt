package zone.clanker.gradle.srcx.parse

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.shouldBe
import zone.clanker.gradle.srcx.model.ReferenceKind
import zone.clanker.gradle.srcx.model.SymbolDetailKind
import java.io.File

class IntegrationTest :
    BehaviorSpec({

        fun tempDir(): File =
            File.createTempFile("integration", "").apply {
                delete()
                mkdirs()
                deleteOnExit()
            }

        given("multi-file Kotlin project") {
            val dir = tempDir()

            dir.resolve("Service.kt").writeText(
                """
                package com.example

                class UserService(private val repo: UserRepository) {
                    fun findUser(id: String): String = repo.find(id)
                    fun saveUser(name: String) { repo.save(name) }
                }
                """.trimIndent(),
            )
            dir.resolve("Repository.kt").writeText(
                """
                package com.example

                interface UserRepository {
                    fun find(id: String): String
                    fun save(name: String)
                }
                """.trimIndent(),
            )
            dir.resolve("Impl.kt").writeText(
                """
                package com.example

                import com.example.UserRepository

                class InMemoryRepository : UserRepository {
                    private val data = mutableMapOf<String, String>()
                    override fun find(id: String): String = data[id] ?: ""
                    override fun save(name: String) { data[name] = name }
                }
                """.trimIndent(),
            )
            dir.resolve("App.kt").writeText(
                """
                package com.example

                import com.example.UserService
                import com.example.InMemoryRepository

                fun main() {
                    val svc = UserService(InMemoryRepository())
                    svc.saveUser("Alice")
                    println(svc.findUser("Alice"))
                }
                """.trimIndent(),
            )

            val sourceFiles = SourceScanner.collectSourceFiles(listOf(dir))
            val index = SymbolIndex.build(sourceFiles)

            `when`("building symbol index") {
                then("all classes are found") {
                    val classNames =
                        index.symbols
                            .filter {
                                it.kind in
                                    setOf(
                                        SymbolDetailKind.CLASS,
                                        SymbolDetailKind.INTERFACE,
                                    )
                            }.map { it.name }
                    classNames shouldHaveAtLeastSize 2
                }
            }

            `when`("finding usages of UserRepository") {
                val usages = index.findUsagesByName("UserRepository")

                then("it finds the interface and its usages") {
                    usages.isNotEmpty() shouldBe true
                }
            }

            `when`("building call graph") {
                val calls = index.callGraph()

                then("it finds method calls") {
                    calls.isNotEmpty() shouldBe true
                }
            }

            `when`("getting usage counts") {
                val counts = index.usageCounts()

                then("it returns sorted counts") {
                    if (counts.size > 1) {
                        val first = counts.first().second
                        val last = counts.last().second
                        (first >= last) shouldBe true
                    }
                }
            }
        }

        given("mixed Kotlin and Java project") {
            val dir = tempDir()

            dir.resolve("Base.java").writeText(
                """
                package com.example;

                public class Base {
                    public String getName() { return "base"; }
                }
                """.trimIndent(),
            )
            dir.resolve("Child.kt").writeText(
                """
                package com.example

                import com.example.Base

                class Child : Base() {
                    fun greet(): String = getName()
                }
                """.trimIndent(),
            )

            val sourceFiles = SourceScanner.collectSourceFiles(listOf(dir))
            val index = SymbolIndex.build(sourceFiles)

            `when`("building from mixed sources") {
                then("it finds both Java and Kotlin symbols") {
                    val names = index.symbols.map { it.name }
                    names.any { it == "Base" } shouldBe true
                    names.any { it == "Child" } shouldBe true
                }
            }

            `when`("checking cross-language references") {
                val imports = index.references.filter { it.kind == ReferenceKind.IMPORT }

                then("Kotlin file imports Java class") {
                    imports.any { it.targetName == "Base" } shouldBe true
                }
            }
        }

        given("SymbolIndex with receiver-based call resolution") {
            val dir = tempDir()

            dir.resolve("Api.kt").writeText(
                """
                package com.example

                class Api {
                    fun fetch(): String = "data"
                }

                class Client(private val api: Api) {
                    fun load(): String = api.fetch()
                }
                """.trimIndent(),
            )

            val sourceFiles = SourceScanner.collectSourceFiles(listOf(dir))
            val index = SymbolIndex.build(sourceFiles)
            val calls = index.callGraph()

            `when`("resolving receiver-qualified calls") {
                then("it connects Client.load to Api.fetch") {
                    val fetchCalls =
                        calls.filter {
                            it.target.name.contains("fetch")
                        }
                    fetchCalls.isNotEmpty() shouldBe true
                }
            }
        }
    })
