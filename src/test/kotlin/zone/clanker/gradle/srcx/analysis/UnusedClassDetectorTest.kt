package zone.clanker.gradle.srcx.analysis

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import java.io.File

class UnusedClassDetectorTest :
    BehaviorSpec({
        fun tempDir(): File =
            File.createTempFile("srcx-unused", "").apply {
                delete()
                mkdirs()
                deleteOnExit()
            }

        given("root and included build production sources") {
            val workspace = tempDir()
            val app = File(workspace, "app")
            val library = File(workspace, "library")
            val appFile = source(app, "main", "App.kt", appSourceText)
            val unusedApp = source(app, "main", "UnusedApp.kt", unusedAppSourceText)
            val testOnly = source(app, "test", "TestOnly.kt", testSourceText)
            val libraryFile = source(library, "main", "Library.kt", librarySourceText)
            val entryPoint = source(library, "main", "Main.kt", mainSourceText)
            val aliasedService = source(library, "main", "AliasedService.kt", aliasedServiceText)
            val aliasConsumer = source(app, "main", "AliasConsumer.kt", aliasConsumerText)

            `when`("unused classes are detected across all builds") {
                val result =
                    UnusedClassDetector.detect(
                        listOf(
                            appFile,
                            unusedApp,
                            testOnly,
                            libraryFile,
                            entryPoint,
                            aliasedService,
                            aliasConsumer,
                        ),
                        workspace,
                        listOf(
                            UnusedClassDetector.ProjectScope("workspace", ":app", app),
                            UnusedClassDetector.ProjectScope("library", ":", library),
                        ),
                    )

                then("only unreferenced production declarations are reported") {
                    result.map { it.qualifiedName } shouldContainExactly listOf("example.UnusedApp")
                }
            }

            workspace.deleteRecursively()
        }
    })

private fun source(project: File, sourceSet: String, name: String, content: String): File =
    File(project, "src/$sourceSet/kotlin/example/$name").apply {
        parentFile.mkdirs()
        writeText(content)
    }

private val appSourceText =
    """
    package example

    class App {
        private val library: Library = Library()
    }
    """.trimIndent()

private val unusedAppSourceText =
    """
    package example

    class UnusedApp
    """.trimIndent()

private val testSourceText =
    """
    package example

    class TestOnly
    """.trimIndent()

private val librarySourceText =
    """
    package example

    class Library
    """.trimIndent()

private val mainSourceText =
    """
    package example

    object Main {
        private val app = App()
        private val aliases = AliasConsumer()
        @JvmStatic fun main(args: Array<String>) = Unit
    }
    """.trimIndent()

private val aliasedServiceText =
    """
    package api

    class Service
    """.trimIndent()

private val aliasConsumerText =
    """
    package example

    import api.Service as ApiService

    class AliasConsumer {
        private val service = ApiService()
    }
    """.trimIndent()
