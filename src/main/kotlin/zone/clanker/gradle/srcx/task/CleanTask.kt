package zone.clanker.gradle.srcx.task

import org.gradle.api.DefaultTask
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import zone.clanker.gradle.srcx.Srcx
import java.io.File

/**
 * Deletes the `.srcx` output directory for the root project and all included builds.
 *
 * All directory paths are captured at configuration time via [baseDirs].
 * The task action operates only on pre-computed data — no
 * [org.gradle.api.Project] access at execution time.
 *
 * ```bash
 * ./gradlew srcx-clean
 * ```
 */
@org.gradle.work.DisableCachingByDefault(because = "Deletes output directories")
abstract class CleanTask : DefaultTask() {
    /** Output directory relative to each base directory (e.g. `.srcx`). */
    @get:Input
    abstract val outputDir: Property<String>

    /** Base directories to clean: root project dir + included build dirs. */
    @get:Internal
    abstract val baseDirs: ListProperty<File>

    init {
        group = Srcx.GROUP
        description = "Delete the srcx output directory"
    }

    @TaskAction
    fun clean() {
        for (baseDir in baseDirs.get()) {
            cleanSafe(baseDir)
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
