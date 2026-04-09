package zone.clanker.gradle.srcx

import org.gradle.api.Action
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.initialization.Settings
import org.gradle.api.provider.Property
import zone.clanker.gradle.srcx.task.CleanTask
import zone.clanker.gradle.srcx.task.ContextTask
import java.io.File
import javax.inject.Inject

/**
 * Root identity object for the srcx source symbol extraction plugin.
 *
 * Contains all constants, the [SettingsExtension] DSL, the [SettingsPlugin] entry point,
 * and the [cleanOutputDir] utility used by [CleanTask].
 *
 * All reusable logic for project scanning, symbol extraction, and report generation
 * lives in [zone.clanker.gradle.srcx.scan.ProjectScanner],
 * [zone.clanker.gradle.srcx.scan.SymbolExtractor], and
 * [zone.clanker.gradle.srcx.report.ReportWriter].
 */
data object Srcx {
    /** Gradle task group name for all srcx tasks. */
    const val GROUP = "srcx"

    /** Name of the DSL extension registered on Settings. */
    const val EXTENSION_NAME = "srcx"

    /** Output directory for generated reports. */
    const val OUTPUT_DIR = ".srcx"

    /** Task: generate comprehensive context report with symbols, analysis, and diagrams. */
    const val TASK_CONTEXT = "srcx-context"

    /** Task: delete the .srcx output directory. */
    const val TASK_CLEAN = "srcx-clean"

    /** Delete an output directory, printing what was removed. */
    fun cleanOutputDir(dir: File) {
        if (dir.exists()) {
            val count = dir.walkTopDown().filter { it.isFile }.count()
            dir.deleteRecursively()
            println("srcx: deleted ${dir.name}/ ($count files)")
        } else {
            println("srcx: nothing to clean")
        }
    }

    /**
     * DSL extension registered as `srcx { }` on the Settings object.
     *
     * Controls the output directory and auto-generation behavior.
     *
     * ```kotlin
     * srcx {
     *     outputDir.set(".srcx")
     *     autoGenerate.set(true)
     * }
     * ```
     *
     * @see SettingsPlugin
     */
    @Suppress("UnnecessaryAbstractClass")
    abstract class SettingsExtension
        @Inject
        constructor() {
            /** Output directory relative to the root project. */
            abstract val outputDir: Property<String>

            /** When true, compileKotlin/compileJava tasks will finalize with srcx-context. */
            abstract val autoGenerate: Property<Boolean>
        }

    /**
     * Settings plugin entry point: `id("zone.clanker.gradle.srcx")`.
     *
     * Thin lifecycle class that registers tasks and wires conventions.
     * All reusable logic lives on [zone.clanker.gradle.srcx.scan.ProjectScanner],
     * [zone.clanker.gradle.srcx.scan.SymbolExtractor], and
     * [zone.clanker.gradle.srcx.report.ReportWriter].
     *
     * Sequence:
     * 1. Register the [SettingsExtension]
     * 2. Use `rootProject` callback to wire tasks after DSL runs
     * 3. Register [ContextTask] and [CleanTask]
     * 4. Wire extension properties into task `@Input` properties via conventions
     * 5. Optionally wire compile tasks to finalize with srcx-context
     */
    class SettingsPlugin : Plugin<Settings> {
        override fun apply(settings: Settings) {
            val extension = settings.extensions.create(EXTENSION_NAME, SettingsExtension::class.java)
            extension.outputDir.convention(OUTPUT_DIR)
            extension.autoGenerate.convention(false)

            settings.gradle.rootProject(
                Action { rootProject ->
                    registerTasks(rootProject, extension)
                },
            )
        }

        internal fun registerTasks(
            rootProject: Project,
            extension: SettingsExtension,
        ) {
            rootProject.tasks.register(TASK_CONTEXT, ContextTask::class.java).configure { task ->
                task.outputDir.convention(extension.outputDir)
                task.outputDirectory.set(
                    rootProject.layout.projectDirectory.dir(extension.outputDir),
                )
                task.sourceFiles.from(
                    rootProject.provider { collectSourceTrees(rootProject) },
                )
            }
            val cleanTask =
                rootProject.tasks.register(TASK_CLEAN, CleanTask::class.java).apply {
                    configure { it.outputDir.convention(extension.outputDir) }
                }
            rootProject.plugins.withType(
                org.gradle.language.base.plugins.LifecycleBasePlugin::class.java,
            ) {
                rootProject.tasks.named("clean").configure { it.dependsOn(cleanTask) }
            }
            if (extension.autoGenerate.get()) {
                wireAutoGenerate(rootProject)
            }
        }

        private fun collectSourceTrees(rootProject: Project): List<Any> {
            val trees = mutableListOf<Any>()
            rootProject.allprojects.forEach { project ->
                trees.add(
                    project.fileTree("src").matching {
                        it.include("**/*.kt", "**/*.java", "**/*.kts")
                    },
                )
                if (project.buildFile.exists()) trees.add(project.buildFile)
            }
            rootProject.gradle.includedBuilds.forEach { build ->
                trees.add(
                    rootProject.fileTree(build.projectDir).matching {
                        it.include("**/src/**/*.kt", "**/src/**/*.java")
                        it.include("**/build.gradle.kts", "**/build.gradle")
                    },
                )
            }
            return trees
        }

        private fun wireAutoGenerate(rootProject: Project) {
            rootProject.allprojects { project ->
                project.tasks.whenTaskAdded { task ->
                    if (task.name.startsWith("compile") &&
                        (task.name.endsWith("Kotlin") || task.name.endsWith("Java"))
                    ) {
                        task.finalizedBy(TASK_CONTEXT)
                    }
                }
            }
        }
    }
}
