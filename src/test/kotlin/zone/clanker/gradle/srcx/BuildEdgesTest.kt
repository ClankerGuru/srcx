package zone.clanker.gradle.srcx

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import zone.clanker.gradle.srcx.analysis.buildDependencyGraph
import zone.clanker.gradle.srcx.analysis.classifyAll
import zone.clanker.gradle.srcx.analysis.generateDependencyDiagram
import zone.clanker.gradle.srcx.analysis.scanSources
import zone.clanker.gradle.srcx.model.ArtifactGroup
import zone.clanker.gradle.srcx.model.ArtifactName
import zone.clanker.gradle.srcx.model.ArtifactVersion
import zone.clanker.gradle.srcx.model.DependencyEntry
import zone.clanker.gradle.srcx.model.ProjectPath
import zone.clanker.gradle.srcx.model.ProjectSummary
import zone.clanker.gradle.srcx.report.DashboardRenderer
import zone.clanker.gradle.srcx.report.ReportWriter
import zone.clanker.gradle.srcx.scan.ProjectScanner
import java.io.File

class BuildEdgesTest :
    BehaviorSpec({

        fun tempDir(): File =
            File.createTempFile("srcx-be", "").apply {
                delete()
                mkdirs()
                deleteOnExit()
            }

        given("computeBuildEdges") {

            `when`("builds have cross-dependencies") {
                val builds =
                    listOf(
                        "api-client" to File("/tmp/api"),
                        "http-core" to File("/tmp/http"),
                        "shared-models" to File("/tmp/models"),
                    )
                val summaries =
                    mapOf(
                        "api-client" to
                            listOf(
                                ProjectSummary(
                                    projectPath = ProjectPath(":"),
                                    symbols = emptyList(),
                                    dependencies =
                                        listOf(
                                            DependencyEntry(
                                                ArtifactGroup("com.example"),
                                                ArtifactName("http-core"),
                                                ArtifactVersion("1.0.0"),
                                                "api",
                                            ),
                                            DependencyEntry(
                                                ArtifactGroup("com.example"),
                                                ArtifactName("shared-models"),
                                                ArtifactVersion("1.0.0"),
                                                "implementation",
                                            ),
                                        ),
                                    buildFile = "build.gradle.kts",
                                    sourceDirs = emptyList(),
                                    subprojects = emptyList(),
                                ),
                            ),
                        "http-core" to
                            listOf(
                                ProjectSummary(
                                    projectPath = ProjectPath(":"),
                                    symbols = emptyList(),
                                    dependencies =
                                        listOf(
                                            DependencyEntry(
                                                ArtifactGroup("com.example"),
                                                ArtifactName("shared-models"),
                                                ArtifactVersion("1.0.0"),
                                                "implementation",
                                            ),
                                        ),
                                    buildFile = "build.gradle.kts",
                                    sourceDirs = emptyList(),
                                    subprojects = emptyList(),
                                ),
                            ),
                        "shared-models" to
                            listOf(
                                ProjectSummary(
                                    projectPath = ProjectPath(":"),
                                    symbols = emptyList(),
                                    dependencies = emptyList(),
                                    buildFile = "build.gradle.kts",
                                    sourceDirs = emptyList(),
                                    subprojects = emptyList(),
                                ),
                            ),
                    )

                val edges = ReportWriter.computeBuildEdges(builds, summaries)

                then("it finds cross-build edges") {
                    edges.size shouldBe 3
                }

                then("api-client depends on http-core") {
                    edges.any { it.from == "api-client" && it.to == "http-core" } shouldBe true
                }

                then("api-client depends on shared-models") {
                    edges.any { it.from == "api-client" && it.to == "shared-models" } shouldBe true
                }

                then("http-core depends on shared-models") {
                    edges.any { it.from == "http-core" && it.to == "shared-models" } shouldBe true
                }
            }

            `when`("no cross-dependencies") {
                val builds = listOf("a" to File("/tmp/a"))
                val summaries =
                    mapOf(
                        "a" to
                            listOf(
                                ProjectSummary(
                                    projectPath = ProjectPath(":"),
                                    symbols = emptyList(),
                                    dependencies =
                                        listOf(
                                            DependencyEntry(
                                                ArtifactGroup("org.external"),
                                                ArtifactName("lib"),
                                                ArtifactVersion("1.0"),
                                                "implementation",
                                            ),
                                        ),
                                    buildFile = "build.gradle.kts",
                                    sourceDirs = emptyList(),
                                    subprojects = emptyList(),
                                ),
                            ),
                    )

                val edges = ReportWriter.computeBuildEdges(builds, summaries)

                then("returns empty") {
                    edges.shouldBeEmpty()
                }
            }
        }

        given("generateClassDiagram") {

            `when`("project has source files with dependencies") {
                val projectDir = tempDir()
                projectDir.resolve("build.gradle.kts").writeText("")
                val srcDir = File(projectDir, "src/main/kotlin/com/example")
                srcDir.mkdirs()
                srcDir.resolve("Service.kt").writeText(
                    """
                    package com.example
                    import com.example.Repository
                    class Service(private val repo: Repository) {
                        fun run() = repo.findAll()
                    }
                    """.trimIndent(),
                )
                srcDir.resolve("Repository.kt").writeText(
                    """
                    package com.example
                    interface Repository {
                        fun findAll(): List<String>
                    }
                    """.trimIndent(),
                )

                val srcDirs =
                    ProjectScanner
                        .discoverSourceSets(projectDir)
                        .flatMap { ss ->
                            ProjectScanner.sourceSetDirs(projectDir, ss.value)
                        }.filter { it.exists() }
                val sources = scanSources(srcDirs)
                val components = classifyAll(sources)
                val edges = buildDependencyGraph(components)
                val diagram = generateDependencyDiagram(components, edges)

                then("it produces a mermaid diagram") {
                    diagram shouldContain "Service"
                }
            }

            `when`("project has no source files") {
                val projectDir = tempDir()
                val srcDirs =
                    ProjectScanner
                        .discoverSourceSets(projectDir)
                        .flatMap { ss ->
                            ProjectScanner.sourceSetDirs(projectDir, ss.value)
                        }.filter { it.exists() }
                val sources = scanSources(srcDirs)
                val components = classifyAll(sources)
                val edges = buildDependencyGraph(components)
                val diagram = generateDependencyDiagram(components, edges)

                then("it returns empty diagram") {
                    diagram shouldBe ""
                }
            }
        }

        given("DashboardRenderer with build edges") {

            `when`("rendering with edges") {
                val edges =
                    listOf(
                        DashboardRenderer.BuildEdge("api-client", "http-core"),
                        DashboardRenderer.BuildEdge("http-core", "shared-models"),
                    )
                val renderer =
                    DashboardRenderer(
                        rootName = "workspace",
                        summaries = emptyList(),
                        includedBuilds = emptyList(),
                        buildEdges = edges,
                    )
                val output = renderer.render()

                then("it contains the mermaid diagram") {
                    output shouldContain "## Build Dependencies"
                    output shouldContain "```mermaid"
                    output shouldContain "flowchart TD"
                    output shouldContain "api_client --> http_core"
                    output shouldContain "http_core --> shared_models"
                }
            }
        }

        given("DashboardRenderer with class diagram") {

            `when`("rendering with a pre-computed diagram") {
                val diagram = "```mermaid\nflowchart TD\n    Service --> Repository\n```"
                val renderer =
                    DashboardRenderer(
                        rootName = "workspace",
                        summaries = emptyList(),
                        includedBuilds = emptyList(),
                        classDiagram = diagram,
                    )
                val output = renderer.render()

                then("it includes the class dependencies section") {
                    output shouldContain "## Class Dependencies"
                    output shouldContain "Service --> Repository"
                }
            }
        }

        given("DashboardRenderer problems section") {

            `when`("rendering with warnings from projects") {
                val summary =
                    ProjectSummary(
                        projectPath = ProjectPath(":app"),
                        symbols = emptyList(),
                        dependencies = emptyList(),
                        buildFile = "build.gradle.kts",
                        sourceDirs = emptyList(),
                        subprojects = emptyList(),
                        analysis =
                            zone.clanker.gradle.srcx.model.AnalysisSummary(
                                findings =
                                    listOf(
                                        zone.clanker.gradle.srcx.model.Finding(
                                            zone.clanker.gradle.srcx.model.FindingSeverity.WARNING,
                                            "`AppHelper` is a helper class",
                                            "Move methods closer",
                                        ),
                                        zone.clanker.gradle.srcx.model.Finding(
                                            zone.clanker.gradle.srcx.model.FindingSeverity.INFO,
                                            "`App` has no test",
                                            "Add AppTest",
                                        ),
                                    ),
                                hubs = emptyList(),
                                cycles = emptyList(),
                            ),
                    )
                val renderer =
                    DashboardRenderer(
                        rootName = "workspace",
                        summaries = listOf(summary),
                        includedBuilds = emptyList(),
                    )
                val output = renderer.render()

                then("it has a problems section") {
                    output shouldContain "## Problems"
                    output shouldContain "### Warnings"
                    output shouldContain "`AppHelper` is a helper class"
                    output shouldContain "### Notes"
                    output shouldContain "`App` has no test"
                }
            }
        }
    })
