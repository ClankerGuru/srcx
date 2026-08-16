package zone.clanker.gradle.srcx.task

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import zone.clanker.gradle.srcx.model.SourceSetName
import zone.clanker.gradle.srcx.scan.ConfiguredSourceSet
import zone.clanker.gradle.srcx.scan.ProjectSourceLayout
import java.io.File

class ProjectScanPlanTest :
    BehaviorSpec({
        given("root and included build projects") {
            val workspace = tempDirectory("srcx-project-plan")
            val rootDirectory = File(workspace, "root").apply { mkdirs() }
            val firstIncludedDirectory = File(workspace, "first-included").apply { mkdirs() }
            val secondIncludedDirectory = File(workspace, "second-included").apply { mkdirs() }
            val rootLayout = sourceLayout(File(rootDirectory, "custom-root"))
            val includedLayout = sourceLayout(File(firstIncludedDirectory, "custom-included"))
            val root =
                RootBuildScanInput(
                    name = "workspace",
                    directory = rootDirectory,
                    projects =
                        mapOf(
                            ":zeta" to File(rootDirectory, "zeta"),
                            ":" to rootDirectory,
                        ),
                    subprojects = listOf(":zeta"),
                    dependencies = emptyMap(),
                    sourceLayouts = mapOf(":" to rootLayout),
                )
            val includedBuilds =
                listOf(
                    IncludedBuildInfo(
                        name = "first",
                        dir = firstIncludedDirectory,
                        relPath = "../first-included",
                        projects =
                            listOf(
                                ":tools" to File(firstIncludedDirectory, "tools"),
                                ":" to firstIncludedDirectory,
                            ),
                        sourceLayouts = mapOf(":tools" to includedLayout),
                    ),
                    IncludedBuildInfo(
                        name = "second",
                        dir = secondIncludedDirectory,
                        relPath = "../second-included",
                        projects = listOf(":" to secondIncludedDirectory),
                    ),
                )

            `when`("the shared scan plan is built") {
                val requests = buildProjectScanRequests(root, includedBuilds)

                then("root and included projects share one stable sequence") {
                    requests
                        .map { request -> Triple(request.scope, request.build, request.projectPath) }
                        .shouldContainExactly(
                            listOf(
                                Triple(ProjectScanScope.ROOT, "workspace", ":"),
                                Triple(ProjectScanScope.ROOT, "workspace", ":zeta"),
                                Triple(ProjectScanScope.INCLUDED, "first", ":"),
                                Triple(ProjectScanScope.INCLUDED, "first", ":tools"),
                                Triple(ProjectScanScope.INCLUDED, "second", ":"),
                            ),
                        )
                }

                then("only the root project receives the root subproject list") {
                    requests
                        .single { request -> request.build == "workspace" && request.projectPath == ":" }
                        .subprojects shouldBe listOf(":zeta")
                    requests
                        .filter { request -> request.projectPath != ":" }
                        .all { request -> request.subprojects.isEmpty() } shouldBe true
                }

                then("each request owns a distinct report file") {
                    validateDistinctProjectReportTargets(requests, ".srcx")
                }

                then("captured source layouts stay with their owning build and project") {
                    requests
                        .single { request -> request.build == "workspace" && request.projectPath == ":" }
                        .sourceLayout shouldBe rootLayout
                    requests
                        .single { request -> request.build == "first" && request.projectPath == ":tools" }
                        .sourceLayout shouldBe includedLayout
                }
            }
        }

        given("two requests targeting the same project report") {
            val workspace = tempDirectory("srcx-project-collision")
            val reportRoot = File(workspace, "build").apply { mkdirs() }
            val duplicateRequests =
                listOf(
                    request(reportRoot, File(workspace, "one")),
                    request(reportRoot, File(workspace, "two")),
                )

            `when`("the report targets are validated") {
                then("the collision is rejected before parallel writes start") {
                    shouldThrow<IllegalArgumentException> {
                        validateDistinctProjectReportTargets(duplicateRequests, ".srcx")
                    }
                }
            }
        }
    })

private fun request(
    reportRoot: File,
    projectDirectory: File,
): ProjectScanRequest =
    ProjectScanRequest(
        scope = ProjectScanScope.INCLUDED,
        build = "included",
        reportRoot = reportRoot,
        projectPath = ":duplicate",
        projectDirectory = projectDirectory,
    )

private fun tempDirectory(prefix: String): File =
    File.createTempFile(prefix, "").apply {
        delete()
        mkdirs()
        deleteOnExit()
    }

private fun sourceLayout(directory: File): ProjectSourceLayout =
    ProjectSourceLayout(
        sourceSets = listOf(ConfiguredSourceSet(SourceSetName("main"), listOf(directory))),
        excludedDirectories = emptyList(),
    )
