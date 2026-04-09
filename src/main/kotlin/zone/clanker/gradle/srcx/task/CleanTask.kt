package zone.clanker.gradle.srcx.task

import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import zone.clanker.gradle.srcx.Srcx
import java.io.File

/**
 * Deletes the `.srcx` output directory for the root project and all included builds.
 *
 * ```bash
 * ./gradlew srcx-clean
 * ```
 */
@org.gradle.work.DisableCachingByDefault(because = "Deletes output directories")
abstract class CleanTask : DefaultTask() {
    /** Output directory relative to the root project (e.g. `.srcx`). */
    @get:Input
    abstract val outputDir: Property<String>

    init {
        group = Srcx.GROUP
        description = "Delete the srcx output directory"
    }

    @TaskAction
    fun clean() {
        val rootProject = project.rootProject
        cleanSafe(rootProject.projectDir)
        for (build in rootProject.gradle.includedBuilds) {
            cleanSafe(build.projectDir)
        }
    }

    private fun cleanSafe(baseDir: File) {
        val dir = File(baseDir, outputDir.get())
        require(dir.canonicalPath.startsWith(baseDir.canonicalPath)) {
            "outputDir '${outputDir.get()}' escapes project directory"
        }
        Srcx.cleanOutputDir(dir)
    }
}
