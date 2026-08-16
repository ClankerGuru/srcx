package zone.clanker.gradle.docx.task

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.gradle.testfixtures.ProjectBuilder
import zone.clanker.gradle.docx.tempDirectory
import zone.clanker.report.model.WorkspaceSiteJson
import zone.clanker.report.model.WorkspaceSiteState
import zone.clanker.report.model.WorkspaceSiteStatus

class DocxStatusTaskTest :
    BehaviorSpec({
        given("a DOCX status task") {
            val project = ProjectBuilder.builder().build()
            val task = project.tasks.register("status-under-test", DocxStatusTask::class.java).get()

            `when`("no report has been generated") {
                then("the task reports the absent state truthfully") {
                    task.statusLine(null) shouldBe "docx: no generated report status is available"
                }
            }

            `when`("a typed stale status exists") {
                val file = tempDirectory("docx-status-").resolve("status.json")
                file.writeText(
                    WorkspaceSiteJson.encodeStatus(
                        WorkspaceSiteStatus(
                            state = WorkspaceSiteState.STALE,
                            generationId = "generation-a",
                            workspaceName = "fixture",
                            message = "A newer snapshot is waiting.",
                        ),
                    ),
                )

                then("the task prints its generation and workspace") {
                    task.statusLine(file) shouldBe
                        "docx: stale generation=generation-a workspace=fixture — A newer snapshot is waiting."
                }
            }
        }
    })
