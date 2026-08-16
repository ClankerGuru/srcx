package zone.clanker.gradle.docx.task

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.LocalState
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import zone.clanker.gradle.docx.Docx
import zone.clanker.gradle.docx.DocxAnalysisPlanJson
import zone.clanker.gradle.docx.requireSupportedOutputFormats
import zone.clanker.gradle.docx.site.DocxSiteInstaller

/** Installs and atomically publishes a verified static DOCX report. */
@DisableCachingByDefault(because = "Atomic publication retains state from the previous valid generation")
abstract class DocxSiteTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val snapshotFile: RegularFileProperty

    @get:Input
    abstract val planJson: Property<String>

    @get:Input
    abstract val distributionResource: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:LocalState
    abstract val lockFile: RegularFileProperty

    init {
        group = Docx.GROUP
        description = "Install and verify the static DOCX workspace report"
    }

    @TaskAction
    fun buildSite() {
        val plan = DocxAnalysisPlanJson.decode(planJson.get())
        requireSupportedOutputFormats(plan.output.formats)
        val resource = distributionResource.get()
        val distribution =
            requireNotNull(javaClass.classLoader.getResourceAsStream(resource)) {
                "Packaged DOCX viewer distribution is missing: $resource"
            }.use { it.readBytes() }
        val output = outputDirectory.get().asFile.toPath()
        runCatching {
            DocxSiteInstaller().install(
                snapshotBytes = snapshotFile.get().asFile.readBytes(),
                distributionBytes = distribution,
                plan = plan,
                outputDirectory = output,
                lockFile = lockFile.get().asFile.toPath(),
            )
        }.onSuccess { result ->
            result.warnings.forEach { warning -> logger.warn("docx: $warning") }
            logger.lifecycle("docx: installed generation ${result.manifest.generationId} at $output")
        }.onFailure { error ->
            logger.error("docx: site generation failed; the last valid report was preserved", error)
        }.getOrThrow()
    }
}
