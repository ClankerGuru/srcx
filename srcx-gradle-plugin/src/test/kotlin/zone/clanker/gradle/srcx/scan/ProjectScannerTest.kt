package zone.clanker.gradle.srcx.scan

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.gradle.api.GradleException
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.testfixtures.ProjectBuilder
import zone.clanker.gradle.srcx.model.SourceSetName
import java.io.File

class ProjectScannerTest :
    BehaviorSpec({
        given("a configured Java project with custom source sets") {
            val projectDirectory = tempDirectory("srcx-java-layout")
            val production = projectDirectory.resolve("code/java").apply { mkdirs() }
            production.resolve("Application.java").writeText("package sample; public final class Application {}")
            val checks = projectDirectory.resolve("checks").apply { mkdirs() }
            checks.resolve("ApplicationCheck.java").writeText("package sample; final class ApplicationCheck {}")
            val generated = projectDirectory.resolve("build/generated/sources").apply { mkdirs() }
            generated.resolve("Generated.java").writeText("package sample; final class Generated {}")
            val project = ProjectBuilder.builder().withProjectDir(projectDirectory).build()
            project.pluginManager.apply("java-library")
            val java = project.extensions.getByType(JavaPluginExtension::class.java)
            java.sourceSets
                .getByName("main")
                .java
                .setSrcDirs(listOf(production, generated))
            java.sourceSets
                .create("integrationTest")
                .java
                .setSrcDirs(listOf(checks))

            `when`("the configured source model is captured") {
                val layout = ProjectScanner.discoverSourceLayout(project)
                val files = ProjectScanner.collectSourceFiles(projectDirectory, layout)

                then("custom roots retain their source-set ownership") {
                    files.map { source -> source.sourceSet.value to source.file.name } shouldContainExactly
                        listOf(
                            "main" to "Application.java",
                            "integrationTest" to "ApplicationCheck.java",
                        )
                }

                then("configured build output roots are not scanned") {
                    files.none { source -> source.file.name == "Generated.java" } shouldBe true
                    layout.sourceSets.flatMap { sourceSet -> sourceSet.directories }.none { directory ->
                        directory.startsWith(projectDirectory.resolve("build"))
                    } shouldBe true
                }

                then("the ownership fingerprint is deterministic and workspace-relative") {
                    val fingerprint =
                        ProjectScanner.sourceLayoutFingerprint(
                            build = "workspace",
                            projectPath = ":app",
                            projectDir = projectDirectory,
                            layout = layout,
                        )
                    fingerprint shouldBe fingerprint.sorted()
                    fingerprint.any { entry -> entry.contains("sourceSet=main|directory=code/java") } shouldBe true
                    fingerprint.none { entry -> entry.contains(projectDirectory.absolutePath) } shouldBe true
                }
            }
        }

        given("a reflected Kotlin Multiplatform source model") {
            val projectDirectory = tempDirectory("srcx-kmp-layout")
            val common = projectDirectory.resolve("src").apply { mkdirs() }
            common.resolve("Shared.kt").writeText("package sample\nclass Shared")
            val jvm = projectDirectory.resolve("src@jvm").apply { mkdirs() }
            jvm.resolve("JvmBridge.kt").writeText("package sample\nclass JvmBridge")
            val project = ProjectBuilder.builder().withProjectDir(projectDirectory).build()
            project.extensions.add(
                "kotlin",
                FakeKotlinExtension(
                    listOf(
                        FakeKotlinSourceSet("jvmMain", FakeKotlinSources(setOf(jvm))),
                        FakeKotlinSourceSet("commonMain", FakeKotlinSources(setOf(common))),
                    ),
                ),
            )

            `when`("the Kotlin extension is inspected without a compile-time KGP dependency") {
                val layout = ProjectScanner.discoverSourceLayout(project)

                then("KMP source sets and custom directories are retained deterministically") {
                    layout.sourceSets.map { sourceSet -> sourceSet.name.value } shouldContainExactly
                        listOf("commonMain", "jvmMain")
                    ProjectScanner
                        .collectSourceFiles(projectDirectory, layout)
                        .map { source -> source.sourceSet.value to source.file.name }
                        .shouldContainExactly(
                            listOf(
                                "commonMain" to "Shared.kt",
                                "jvmMain" to "JvmBridge.kt",
                            ),
                        )
                }
            }
        }

        given("a project without a configured language model") {
            val projectDirectory = tempDirectory("srcx-conventional-layout")
            projectDirectory.resolve("src/commonMain/kotlin/Shared.kt").apply {
                parentFile.mkdirs()
                writeText("class Shared")
            }
            projectDirectory.resolve("src/jvmMain/java/JvmApi.java").apply {
                parentFile.mkdirs()
                writeText("final class JvmApi {}")
            }

            `when`("the conventional KMP fallback is used") {
                val layout = ProjectScanner.discoverConventionalSourceLayout(projectDirectory)

                then("source-set names and files remain owned and ordered") {
                    layout.sourceSets.map { sourceSet -> sourceSet.name.value } shouldContainExactly
                        listOf("commonMain", "jvmMain")
                    ProjectScanner
                        .collectSourceFiles(projectDirectory, layout)
                        .map { source -> source.sourceSet.value to source.file.name }
                        .shouldContainExactly(
                            listOf(
                                "commonMain" to "Shared.kt",
                                "jvmMain" to "JvmApi.java",
                            ),
                        )
                }
            }
        }

        given("a present Kotlin model that cannot be inspected") {
            val projectDirectory = tempDirectory("srcx-broken-kotlin-layout")
            val project = ProjectBuilder.builder().withProjectDir(projectDirectory).build()
            project.extensions.add("kotlin", BrokenKotlinExtension())

            `when`("configured source-set reflection fails") {
                val failure = shouldThrow<GradleException> { ProjectScanner.discoverSourceLayout(project) }

                then("the project and extension are reported instead of silently under-scanning") {
                    failure.message.orEmpty() shouldContain "project ':'"
                    failure.message.orEmpty() shouldContain BrokenKotlinExtension::class.java.name
                }
            }
        }

        given("an empty but authoritative Kotlin model") {
            val projectDirectory = tempDirectory("srcx-empty-kotlin-layout")
            projectDirectory.resolve("src/main/kotlin/Stale.kt").apply {
                parentFile.mkdirs()
                writeText("class Stale")
            }
            val project = ProjectBuilder.builder().withProjectDir(projectDirectory).build()
            project.extensions.add("kotlin", FakeKotlinExtension(emptyList()))

            `when`("the configured model has no source sets") {
                then("filesystem fallback does not invent ownership") {
                    ProjectScanner.discoverSourceLayout(project).sourceSets.isEmpty() shouldBe true
                }
            }
        }

        given("an overlapping root and nested-project source tree") {
            val projectDirectory = tempDirectory("srcx-project-ownership")
            projectDirectory.resolve("Owned.kt").writeText("class Owned")
            val nestedProject = projectDirectory.resolve("nested").apply { mkdirs() }
            nestedProject.resolve("Nested.kt").writeText("class Nested")
            val buildOutput = projectDirectory.resolve("build/generated").apply { mkdirs() }
            buildOutput.resolve("Generated.kt").writeText("class Generated")
            val layout =
                ProjectScanner.excludeDirectories(
                    ProjectSourceLayout(
                        sourceSets =
                            listOf(
                                ConfiguredSourceSet(SourceSetName("main"), listOf(projectDirectory)),
                            ),
                        excludedDirectories = listOf(projectDirectory.resolve("build")),
                    ),
                    listOf(nestedProject),
                )

            `when`("owned files are collected") {
                then("nested projects and build outputs do not leak into the parent") {
                    ProjectScanner
                        .collectSourceFiles(projectDirectory, layout)
                        .map { source -> source.file.name } shouldContainExactly listOf("Owned.kt")
                }
            }
        }
    })

private data class FakeKotlinExtension(
    val sourceSets: List<FakeKotlinSourceSet>,
)

private data class FakeKotlinSourceSet(
    val name: String,
    val kotlin: FakeKotlinSources,
)

private data class FakeKotlinSources(
    val srcDirs: Set<File>,
)

private class BrokenKotlinExtension

private fun tempDirectory(prefix: String): File =
    File.createTempFile(prefix, "").apply {
        delete()
        mkdirs()
        deleteOnExit()
    }
