package zone.clanker.gradle.srcx.snapshot

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.file.shouldExist
import io.kotest.matchers.shouldBe
import zone.clanker.gradle.srcx.Srcx
import zone.clanker.report.model.WorkspaceSnapshotJson
import java.io.File
import java.io.IOException

class WorkspaceSnapshotWriterTest :
    BehaviorSpec({
        given("the current SRCX context report flow") {
            val root =
                File.createTempFile("srcx-workspace-snapshot", "").apply {
                    delete()
                    mkdirs()
                    deleteOnExit()
                }
            val output = File(root, Srcx.OUTPUT_DIR)
            val report = completeWorkspaceReport()

            `when`("the typed snapshot writer publishes the report") {
                val destination = WorkspaceSnapshotWriter.write(output, root.canonicalPath, report)

                then("it emits the exact stable context-flow path") {
                    destination.shouldExist()
                    destination shouldBe File(output, Srcx.WORKSPACE_SNAPSHOT_FILE)
                }

                then("the file contains only the canonical typed serialization") {
                    val expected =
                        WorkspaceSnapshotMapper.map(
                            report,
                            WorkspaceSnapshotMapper.workspaceId(root.canonicalPath, report.name),
                        )
                    WorkspaceSnapshotJson.decode(destination.readText()) shouldBe expected
                    destination.readText() shouldBe WorkspaceSnapshotJson.encode(expected)
                }
            }
        }

        given("a previously published canonical snapshot") {
            val directory =
                File.createTempFile("srcx-atomic-snapshot", "").apply {
                    delete()
                    mkdirs()
                    deleteOnExit()
                }
            val destination = File(directory, Srcx.WORKSPACE_SNAPSHOT_FILE).apply { writeText("last-valid\n") }

            `when`("replacement fails before publication") {
                then("the last valid bytes remain and the temporary file is removed") {
                    shouldThrow<IOException> {
                        AtomicSnapshotPublisher.publish(
                            destination = destination,
                            content = "replacement\n",
                            move = SnapshotFileMove { _, _ -> throw IOException("simulated move failure") },
                        )
                    }
                    destination.readText() shouldBe "last-valid\n"
                    directory.listFiles().orEmpty().map { it.name } shouldBe listOf(Srcx.WORKSPACE_SNAPSHOT_FILE)
                }
            }

            `when`("replacement succeeds") {
                then("only the complete replacement bytes become visible") {
                    AtomicSnapshotPublisher.publish(destination, "replacement\n")
                    destination.readBytes().toList() shouldBe "replacement\n".toByteArray().toList()
                    directory.listFiles().orEmpty().map { it.name } shouldBe listOf(Srcx.WORKSPACE_SNAPSHOT_FILE)
                }
            }
        }
    })
