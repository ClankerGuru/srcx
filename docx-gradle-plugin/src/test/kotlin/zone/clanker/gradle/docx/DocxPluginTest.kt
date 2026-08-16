package zone.clanker.gradle.docx

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.file.shouldExist
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.gradle.testkit.runner.GradleRunner
import zone.clanker.report.model.WorkspaceSiteJson
import zone.clanker.report.model.WorkspaceSiteState
import zone.clanker.report.model.WorkspaceSnapshotJson
import java.io.File

class DocxPluginTest :
    BehaviorSpec({
        given("the published DOCX plugin runtime") {
            `when`("the packaged viewer resource is resolved") {
                then("consumer builds receive one precompiled distribution ZIP") {
                    DocxPluginTest::class.java.classLoader
                        .getResource(Docx.WEB_DISTRIBUTION_RESOURCE)
                        .shouldNotBeNull()
                }
            }
        }

        given("a root build with the DOCX settings plugin") {
            val projectDir = tempProject()
            projectDir.resolve("settings.gradle.kts").writeText(
                """
                plugins {
                    id("zone.clanker.gradle.docx")
                }

                rootProject.name = "docx-fixture"

                docx {
                    preset.set(zone.clanker.gradle.docx.DocxPreset.NONE)
                    output {
                        directory.set("build/docs-plan")
                    }
                    scope {
                        builds.include("mosaic")
                        projects.include(":runtime")
                        includeTests.set(false)
                    }
                    features {
                        findings {
                            enabled.set(true)
                            severities.set(
                                setOf(zone.clanker.gradle.docx.FindingSeveritySelection.WARNING),
                            )
                        }
                    }
                }
                """.trimIndent(),
            )
            projectDir.resolve("build.gradle.kts").writeText("plugins { base }")

            `when`("the deterministic plan task runs") {
                GradleRunner
                    .create()
                    .withProjectDir(projectDir)
                    .withPluginClasspath()
                    .withArguments(Docx.TASK_PLAN, "--stacktrace")
                    .build()

                then("it writes a Kotlin-serialized analysis request") {
                    val output = projectDir.resolve("build/docx/${Docx.ANALYSIS_PLAN_FILE}")
                    output.shouldExist()
                    val plan = DocxAnalysisPlanJson.decode(output.readText())
                    plan.preset shouldBe DocxPreset.NONE
                    plan.scope.builds.included shouldContainExactly listOf("mosaic")
                    plan.scope.projects.included shouldContainExactly listOf(":runtime")
                    plan.scope.includeTests shouldBe false
                    plan.features.single { it.feature == DocxFeature.FINDINGS }.enabled shouldBe true
                    plan.requiredCapabilities shouldContainExactly
                        listOf(AnalysisCapability.FINDINGS, AnalysisCapability.SOURCE_METADATA)
                    projectDir.deleteRecursively()
                }
            }
        }

        given("a reusable configuration-cache entry") {
            val projectDir = tempProject()
            projectDir.resolve("settings.gradle.kts").writeText(
                """
                plugins {
                    id("zone.clanker.gradle.docx")
                }

                rootProject.name = "docx-cache-fixture"
                """.trimIndent(),
            )
            projectDir.resolve("build.gradle.kts").writeText("plugins { base }")

            `when`("the plan task executes twice") {
                val runner =
                    GradleRunner
                        .create()
                        .withProjectDir(projectDir)
                        .withPluginClasspath()
                        .withArguments(Docx.TASK_PLAN, "--configuration-cache", "--stacktrace")
                runner.build()
                val second = runner.build()

                then("Gradle reuses configuration without capturing project state") {
                    second.output.contains("Reusing configuration cache") shouldBe true
                    projectDir.deleteRecursively()
                }
            }
        }

        given("a consumer workspace with a canonical SRCX snapshot") {
            val projectDir = tempProject()
            projectDir.resolve("settings.gradle.kts").writeText(
                """
                plugins {
                    id("zone.clanker.gradle.docx")
                }

                rootProject.name = "docx-site-fixture"
                """.trimIndent(),
            )
            projectDir.resolve("build.gradle.kts").writeText("plugins { base }")
            projectDir.resolve(".srcx").mkdirs()
            projectDir.resolve(Docx.DEFAULT_SNAPSHOT_FILE).writeText(
                WorkspaceSnapshotJson.encode(workspaceFixture()),
            )

            `when`("the static site task runs") {
                val runner =
                    GradleRunner
                        .create()
                        .withProjectDir(projectDir)
                        .withPluginClasspath()
                        .withArguments(Docx.TASK_SITE, "--configuration-cache", "--stacktrace")
                val result = runner.build()
                val second = runner.build()

                then("it installs the precompiled viewer and typed lazy data") {
                    result.output.contains(":docx-web:") shouldBe false
                    second.output.contains("Reusing configuration cache") shouldBe true
                    projectDir.resolve(".docx/index.html").shouldExist()
                    val manifest =
                        WorkspaceSiteJson.decodeManifest(
                            projectDir.resolve(".docx/data/manifest.json").readText(),
                        )
                    manifest.projectShards.single().projectId shouldBe "project:a"
                    projectDir.resolve(".docx/${manifest.projectShards.single().file}").shouldExist()
                    WorkspaceSiteJson
                        .decodeStatus(projectDir.resolve(".docx/status.json").readText())
                        .state shouldBe WorkspaceSiteState.CURRENT
                    projectDir.deleteRecursively()
                }
            }
        }

        given("a consumer workspace without a usable SRCX snapshot") {
            val projectDir = tempProject()
            projectDir.resolve("settings.gradle.kts").writeText(
                """
                plugins {
                    id("zone.clanker.gradle.docx")
                }

                rootProject.name = "docx-missing-snapshot"
                """.trimIndent(),
            )
            projectDir.resolve("build.gradle.kts").writeText("plugins { base }")

            `when`("docx-site is requested") {
                val result =
                    GradleRunner
                        .create()
                        .withProjectDir(projectDir)
                        .withPluginClasspath()
                        .withArguments(Docx.TASK_SITE, "--stacktrace")
                        .buildAndFail()

                then("Gradle identifies the missing typed input before execution") {
                    result.output.contains(Docx.DEFAULT_SNAPSHOT_FILE) shouldBe true
                    projectDir.deleteRecursively()
                }
            }
        }

        listOf(
            "Markdown-only" to
                "setOf(zone.clanker.gradle.docx.DocxOutputFormat.MARKDOWN)",
            "HTML-and-Markdown" to
                "zone.clanker.gradle.docx.DocxOutputFormat.entries.toSet()",
        ).forEach { (description, formatsExpression) ->
            given("a consumer with a $description output request") {
                val projectDir = tempProject()
                projectDir.resolve("settings.gradle.kts").writeText(
                    """
                    plugins {
                        id("zone.clanker.gradle.docx")
                    }

                    rootProject.name = "docx-unsupported-format"

                    docx {
                        output {
                            formats.set($formatsExpression)
                        }
                    }
                    """.trimIndent(),
                )
                projectDir.resolve("build.gradle.kts").writeText("plugins { base }")

                `when`("the DOCX lifecycle is configured") {
                    val result =
                        GradleRunner
                            .create()
                            .withProjectDir(projectDir)
                            .withPluginClasspath()
                            .withArguments(Docx.TASK_PLAN, "--stacktrace")
                            .buildAndFail()

                    then("Gradle reports that the requested artifact is not implemented") {
                        result.output shouldContain "DOCX output formats must be exactly [HTML]"
                        projectDir.deleteRecursively()
                    }
                }
            }
        }

        given("a consumer that places the site over the SRCX snapshot directory") {
            val projectDir = tempProject()
            projectDir.resolve("settings.gradle.kts").writeText(
                """
                plugins {
                    id("zone.clanker.gradle.docx")
                }

                rootProject.name = "docx-overlapping-output"

                docx {
                    output {
                        directory.set(".srcx")
                    }
                }
                """.trimIndent(),
            )
            projectDir.resolve("build.gradle.kts").writeText("plugins { base }")

            `when`("the DOCX lifecycle is configured") {
                val result =
                    GradleRunner
                        .create()
                        .withProjectDir(projectDir)
                        .withPluginClasspath()
                        .withArguments(Docx.TASK_PLAN, "--stacktrace")
                        .buildAndFail()

                then("Gradle rejects an output that would contain its producer input") {
                    result.output shouldContain "must not contain SRCX snapshot"
                    projectDir.deleteRecursively()
                }
            }
        }
    })

private fun tempProject(): File =
    File.createTempFile("docx-plugin", "").apply {
        delete()
        mkdirs()
        deleteOnExit()
    }
