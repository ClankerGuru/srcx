package zone.clanker.gradle.docx.task

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import zone.clanker.gradle.docx.Docx
import zone.clanker.report.model.WorkspaceSiteJson
import java.io.File

/** Prints the typed last-known publication state without generating a report. */
@DisableCachingByDefault(because = "This task only reports current local state")
abstract class DocxStatusTask : DefaultTask() {
    @get:Internal
    abstract val statusFile: RegularFileProperty

    init {
        group = Docx.GROUP
        description = "Print the current DOCX report status"
    }

    @TaskAction
    fun printStatus() {
        logger.lifecycle(statusLine(statusFile.orNull?.asFile))
    }

    internal fun statusLine(file: File?): String {
        if (file?.isFile != true) return "docx: no generated report status is available"
        val status = WorkspaceSiteJson.decodeStatus(file.readText())
        val generation = status.generationId?.let { " generation=$it" }.orEmpty()
        val workspace = status.workspaceName?.let { " workspace=$it" }.orEmpty()
        return "docx: ${status.state.name.lowercase()}$generation$workspace — ${status.message}"
    }
}
