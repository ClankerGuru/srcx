package zone.clanker.gradle.docx.task

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import zone.clanker.gradle.docx.Docx

/** Writes the deterministic analysis request consumed by the future SRCX engine handoff. */
@CacheableTask
abstract class DocxPlanTask : DefaultTask() {
    @get:Input
    abstract val planJson: Property<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    init {
        group = Docx.GROUP
        description = "Write the scoped DOCX analysis plan"
    }

    @TaskAction
    fun writePlan() {
        writePlan(outputFile.get().asFile, planJson.get())
    }

    internal fun writePlan(
        destination: java.io.File,
        content: String,
    ) {
        destination.parentFile.mkdirs()
        destination.writeText(content)
        logger.lifecycle("docx: wrote ${destination.path}")
    }
}
