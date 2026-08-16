package zone.clanker.gradle.docx.task

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.file.shouldExist
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.gradle.testfixtures.ProjectBuilder
import zone.clanker.gradle.docx.Docx
import zone.clanker.gradle.docx.DocxAnalysisPlanJson
import zone.clanker.gradle.docx.DocxSettingsExtension
import zone.clanker.gradle.docx.docxTestPlan
import zone.clanker.gradle.docx.tempDirectory
import zone.clanker.gradle.docx.workspaceFixture
import zone.clanker.report.model.WorkspaceSiteJson
import zone.clanker.report.model.WorkspaceSiteState
import zone.clanker.report.model.WorkspaceSnapshotJson
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class DocxTaskActionTest :
    BehaviorSpec({
        given("direct first-slice task actions") {
            val projectDirectory = tempDirectory("docx-task-actions-")
            val project = ProjectBuilder.builder().withProjectDir(projectDirectory).build()

            `when`("the plan and status actions run") {
                val planFile = projectDirectory.resolve("build/plan.json")
                val plan = project.tasks.create("plan-action", DocxPlanTask::class.java)
                plan.planJson.set(DocxAnalysisPlanJson.encode(docxTestPlan()))
                plan.outputFile.fileValue(planFile)
                plan.writePlan()

                val status = project.tasks.create("status-action", DocxStatusTask::class.java)
                status.statusFile.fileValue(projectDirectory.resolve("missing-status.json"))
                status.printStatus()

                then("the plan output is materialized") {
                    planFile.shouldExist()
                    DocxAnalysisPlanJson.decode(planFile.readText()) shouldBe docxTestPlan()
                }
            }

            `when`("the site action consumes the packaged viewer") {
                val snapshot = projectDirectory.resolve(".srcx/workspace-report.json")
                snapshot.parentFile.mkdirs()
                snapshot.writeText(WorkspaceSnapshotJson.encode(workspaceFixture()))
                val task = project.tasks.create("site-action", DocxSiteTask::class.java)
                task.snapshotFile.fileValue(snapshot)
                val fullPlan = project.objects.newInstance(DocxSettingsExtension::class.java).analysisPlan()
                task.planJson.set(DocxAnalysisPlanJson.encode(fullPlan))
                task.distributionResource.set(Docx.WEB_DISTRIBUTION_RESOURCE)
                task.outputDirectory.fileValue(projectDirectory.resolve(".docx"))
                task.lockFile.fileValue(projectDirectory.resolve("build/docx/site.lock"))
                task.buildSite()

                then("it publishes a current typed site") {
                    projectDirectory.resolve(".docx/index.html").shouldExist()
                    WorkspaceSiteJson
                        .decodeStatus(projectDirectory.resolve(".docx/status.json").readText())
                        .state shouldBe WorkspaceSiteState.CURRENT
                }
            }

            `when`("the site action receives an incompatible snapshot") {
                val snapshot = projectDirectory.resolve(".srcx/invalid-workspace-report.json")
                snapshot.parentFile.mkdirs()
                snapshot.writeText("{\"schemaVersion\":999}")
                val output = projectDirectory.resolve("failed-site")
                val task = project.tasks.create("site-invalid-snapshot", DocxSiteTask::class.java)
                task.snapshotFile.fileValue(snapshot)
                task.planJson.set(DocxAnalysisPlanJson.encode(docxTestPlan()))
                task.distributionResource.set(Docx.WEB_DISTRIBUTION_RESOURCE)
                task.outputDirectory.fileValue(output)
                task.lockFile.fileValue(projectDirectory.resolve("build/docx/failed-site.lock"))

                then("the action preserves a typed failure status") {
                    shouldThrow<Exception> { task.buildSite() }
                    WorkspaceSiteJson.decodeStatus(output.resolve("status.json").readText()).state shouldBe
                        WorkspaceSiteState.FAILED
                }
            }

            `when`("the site plan excludes HTML") {
                val task = project.tasks.create("site-without-html", DocxSiteTask::class.java)
                val unsupportedPlan =
                    DocxAnalysisPlanJson
                        .encode(docxTestPlan())
                        .replaceFirst("\"HTML\"", "\"MARKDOWN\"")
                task.planJson.set(unsupportedPlan)

                then("the action rejects the incompatible output") {
                    shouldThrow<IllegalArgumentException> { task.buildSite() }.message shouldContain "exactly [HTML]"
                }
            }

            `when`("the packaged viewer resource is absent") {
                val task = project.tasks.create("site-without-viewer", DocxSiteTask::class.java)
                task.planJson.set(DocxAnalysisPlanJson.encode(docxTestPlan()))
                task.distributionResource.set("missing/viewer.zip")

                then("the action reports the packaging error") {
                    shouldThrow<IllegalArgumentException> { task.buildSite() }.message shouldContain "missing"
                }
            }

            `when`("the open task serves a completed site") {
                val preview = projectDirectory.resolve("preview").apply { mkdirs() }
                preview.resolve("index.html").writeText("<h1>DOCX preview</h1>")
                val task = project.tasks.create("open-action", DocxOpenTask::class.java)
                task.siteDirectory.fileValue(preview)
                task.launchBrowser.set(false)
                var statusCode = 0
                task.serveReport { url ->
                    val request = HttpRequest.newBuilder(url).GET().build()
                    statusCode =
                        HttpClient
                            .newHttpClient()
                            .send(request, HttpResponse.BodyHandlers.ofString())
                            .statusCode()
                }

                then("the task-scoped endpoint serves the report and closes") {
                    statusCode shouldBe 200
                }
            }
        }
    })
