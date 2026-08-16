package zone.clanker.gradle.docx.task

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import zone.clanker.gradle.docx.Docx
import zone.clanker.gradle.docx.preview.DocxPreviewServer
import java.net.URI
import javax.inject.Inject

/** Serves the completed static report over a task-scoped Java 17 loopback endpoint. */
@DisableCachingByDefault(because = "This interactive task owns a preview server until interrupted")
abstract class DocxOpenTask : DefaultTask() {
    @get:Inject
    abstract val execOperations: ExecOperations

    @get:InputDirectory
    abstract val siteDirectory: DirectoryProperty

    @get:Input
    abstract val launchBrowser: Property<Boolean>

    init {
        group = Docx.GROUP
        description = "Serve and open the static DOCX report on a loopback HTTP endpoint"
        launchBrowser.convention(true)
    }

    @TaskAction
    fun openReport() {
        serveReport { awaitInterruption() }
    }

    internal fun serveReport(waitForStop: (URI) -> Unit) {
        val server = DocxPreviewServer.start(siteDirectory.get().asFile.toPath())
        server.use {
            logger.lifecycle("docx: preview available at ${server.url}")
            logger.lifecycle("docx: this task-scoped HTTP preview runs until Ctrl-C")
            if (launchBrowser.get()) openBrowser(server.url)
            waitForStop(server.url)
        }
    }

    @Suppress("MagicNumber")
    private fun awaitInterruption() {
        while (true) Thread.sleep(60_000)
    }

    private fun openBrowser(url: URI) {
        runCatching {
            execOperations
                .exec { specification ->
                    specification.commandLine(DocxBrowserLauncher.command(System.getProperty("os.name").orEmpty(), url))
                }.assertNormalExitValue()
        }.onFailure { error ->
            logger.lifecycle("docx: browser was not launched (${error.message}); open $url manually")
        }
    }
}
