package zone.clanker.gradle.srcx

import org.gradle.api.Action
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.initialization.Settings
import org.gradle.api.logging.Logging
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Internal
import org.gradle.language.base.plugins.LifecycleBasePlugin
import zone.clanker.gradle.srcx.scan.IncludedBuildProjectDiscovery
import zone.clanker.gradle.srcx.scan.ProjectScanner
import zone.clanker.gradle.srcx.scan.ProjectSourceLayout
import zone.clanker.gradle.srcx.scan.SymbolExtractor
import zone.clanker.gradle.srcx.task.CleanTask
import zone.clanker.gradle.srcx.task.ContextTask
import zone.clanker.gradle.srcx.task.IncludedBuildInfo
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
    private val logger = Logging.getLogger(Srcx::class.java)

    /** Gradle task group name for all srcx tasks. */
    const val GROUP = "srcx"

    /** Name of the DSL extension registered on Settings. */
    const val EXTENSION_NAME = "srcx"

    /** Output directory for generated reports. */
    const val OUTPUT_DIR = ".srcx"

    /** Canonical typed snapshot emitted for downstream renderers. */
    const val WORKSPACE_SNAPSHOT_FILE = "workspace-report.json"

    /** Task: generate comprehensive context report with symbols, analysis, and diagrams. */
    const val TASK_CONTEXT = "srcx-context"

    /** Task: delete the .srcx output directory. */
    const val TASK_CLEAN = "srcx-clean"

    /** Static documentation directory inside [OUTPUT_DIR]. */
    const val HTML_SITE_DIR = "site"

    /** Standalone static documentation entry point. */
    const val HTML_INDEX_FILE = "index.html"

    /** Scoped static documentation fragment for embedding in an existing page. */
    const val HTML_FRAGMENT_FILE = "report.html"

    /** Dependency scopes excluded from scanning by default. */
    val DEFAULT_EXCLUDED_DEP_SCOPES: Set<String> =
        setOf(
            "archives",
            "default",
            "kotlinBuildToolsApiClasspath",
            "kotlinCompilerClasspath",
            "kotlinCompilerPluginClasspath",
            "kotlinCompilerPluginClasspathMain",
            "kotlinCompilerPluginClasspathTest",
            "kotlinKlibCommonizerClasspath",
            "kotlinNativeCompilerPluginClasspath",
            "kotlinScriptDef",
            "kotlinScriptDefExtensions",
        )

    /** Conservative number of project jobs allowed to overlap around the serialized PSI phase. */
    const val DEFAULT_PROJECT_WORKERS = 4

    /** Hard bound that prevents project-local source buffers from growing with an arbitrary worker value. */
    const val MAX_PROJECT_WORKERS = 16

    /** Delete an output directory, printing what was removed. */
    fun cleanOutputDir(dir: File) {
        if (dir.exists()) {
            val count = dir.walkTopDown().filter { it.isFile }.count()
            dir.deleteRecursively()
            logger.lifecycle("srcx: deleted ${dir.name}/ ($count files)")
        } else {
            logger.lifecycle("srcx: nothing to clean")
        }
    }

    val DEFAULT_FORBIDDEN_PACKAGES: Set<String> =
        setOf("util", "utils", "helper", "helpers", "manager", "managers", "misc", "base")

    val DEFAULT_FORBIDDEN_CLASS_PATTERNS: Set<String> =
        setOf("Helper", "Manager", "Utils", "Util")

    /**
     * DSL extension registered as `srcx { }` on the Settings object.
     *
     * ```kotlin
     * srcx {
     *     outputDir.set(".srcx")
     *     autoGenerate.set(true)
     *     projectWorkers.set(4)
     *     forbiddenPackages("legacy", "internal", "compat")
     *     forbiddenClassPatterns("Base", "Impl", "Abstract")
     * }
     * ```
     *
     * @see SettingsPlugin
     */
    @Suppress("UnnecessaryAbstractClass")
    abstract class SettingsExtension
        @Inject
        constructor() {
            abstract val outputDir: Property<String>
            abstract val autoGenerate: Property<Boolean>
            abstract val projectWorkers: Property<Int>
            abstract val excludeDepScopes: SetProperty<String>

            @get:Internal
            abstract val forbiddenPackageNames: SetProperty<String>

            @get:Internal
            abstract val forbiddenClassNamePatterns: SetProperty<String>

            fun forbiddenPackages(vararg names: String) {
                forbiddenPackageNames.addAll(names.toList())
            }

            fun forbiddenClassPatterns(vararg patterns: String) {
                forbiddenClassNamePatterns.addAll(patterns.toList())
            }
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
     * 5. Optionally make the standard assemble lifecycle depend on srcx-context
     */
    class SettingsPlugin : Plugin<Settings> {
        override fun apply(settings: Settings) {
            val extension = settings.extensions.create(EXTENSION_NAME, SettingsExtension::class.java)
            extension.outputDir.convention(OUTPUT_DIR)
            extension.autoGenerate.convention(false)
            extension.projectWorkers.convention(DEFAULT_PROJECT_WORKERS)
            extension.excludeDepScopes.convention(DEFAULT_EXCLUDED_DEP_SCOPES)
            extension.forbiddenPackageNames.convention(DEFAULT_FORBIDDEN_PACKAGES)
            extension.forbiddenClassNamePatterns.convention(DEFAULT_FORBIDDEN_CLASS_PATTERNS)

            settings.gradle.rootProject(
                Action { rootProject ->
                    registerTasks(rootProject, extension)
                },
            )
        }

        @Suppress("LongMethod")
        internal fun registerTasks(
            rootProject: Project,
            extension: SettingsExtension,
        ) {
            val contextTask =
                rootProject.tasks.register(TASK_CONTEXT, ContextTask::class.java).apply {
                    configure { task ->
                        task.outputDir.convention(extension.outputDir)
                        task.outputDirectory.set(
                            rootProject.layout.projectDirectory.dir(extension.outputDir),
                        )
                        task.projectSourceLayouts.convention(emptyMap())
                        task.sourceLayoutFingerprint.convention(emptyList())
                        task.projectWorkers.convention(
                            extension.projectWorkers.orElse(DEFAULT_PROJECT_WORKERS),
                        )
                        task.rootName.set(rootProject.name)
                        task.rootDir.set(rootProject.projectDir)
                        task.projectDirs.convention(emptyMap())
                        task.subprojectPaths.convention(emptyList())
                        task.excludeDepScopes.convention(extension.excludeDepScopes)
                        task.projectDeps.convention(emptyMap())
                        task.includedBuildInfos.convention(emptyList())
                        task.includedBuildOutputDirectories.from(
                            task.includedBuildInfos.zip(task.outputDir) { builds, outputDir ->
                                zone.clanker.gradle.srcx.task
                                    .includedBuildReportDirectories(builds, outputDir)
                            },
                        )
                        task.includedBuildPaths.convention(emptyList())
                        task.forbiddenPackages.convention(extension.forbiddenPackageNames)
                        task.forbiddenClassSuffixes.convention(extension.forbiddenClassNamePatterns)
                    }
                }
            val cleanTask =
                rootProject.tasks.register(TASK_CLEAN, CleanTask::class.java).apply {
                    configure { task ->
                        task.outputDir.convention(extension.outputDir)
                        task.baseDirs.convention(listOf(rootProject.projectDir))
                    }
                }
            rootProject.gradle.projectsEvaluated(
                Action {
                    materializeTaskInputs(rootProject, extension, contextTask, cleanTask)
                },
            )
            rootProject.plugins.withType(
                LifecycleBasePlugin::class.java,
            ) {
                rootProject.tasks.named(LifecycleBasePlugin.CLEAN_TASK_NAME).configure { clean ->
                    clean.dependsOn(cleanTask)
                }
                contextTask.configure { context -> context.mustRunAfter(cleanTask) }
            }
            if (extension.autoGenerate.get()) {
                wireAutoGenerate(rootProject, contextTask)
            }
        }

        private fun materializeTaskInputs(
            rootProject: Project,
            extension: SettingsExtension,
            contextTask: org.gradle.api.tasks.TaskProvider<ContextTask>,
            cleanTask: org.gradle.api.tasks.TaskProvider<CleanTask>,
        ) {
            val projects = ProjectScanner.collectProjects(rootProject)
            val rootLayouts = collectRootProjectSourceLayouts(rootProject)
            val includedBuilds = collectIncludedBuildInfos(rootProject)
            val rootSourceFiles = collectRootSourceFiles(projects, rootLayouts)
            val includedSourceFiles = collectIncludedBuildSourceFiles(includedBuilds)
            val excludes = extension.excludeDepScopes.get()
            contextTask.configure { task ->
                task.projectSourceLayouts.set(rootLayouts)
                task.sourceLayoutFingerprint.set(
                    sourceLayoutFingerprint(rootProject.name, projects, rootLayouts, includedBuilds),
                )
                task.sourceFiles.setFrom(rootSourceFiles + includedSourceFiles)
                task.projectDirs.set(projects.associate { project -> project.path to project.projectDir })
                task.subprojectPaths.set(rootProject.subprojects.map { project -> project.path }.sorted())
                task.projectDeps.set(
                    projects.associate { project ->
                        project.path to SymbolExtractor.extractDependenciesFromProject(project, excludes)
                    },
                )
                task.includedBuildInfos.set(includedBuilds)
                task.includedBuildPaths.set(collectIncludedBuildPaths(rootProject))
            }
            cleanTask.configure { task ->
                task.baseDirs.set(listOf(rootProject.projectDir) + includedBuilds.map { build -> build.dir })
            }
        }

        private fun collectIncludedBuildInfos(rootProject: Project): List<IncludedBuildInfo> =
            rootProject.gradle.includedBuilds.map { build ->
                val projects = IncludedBuildProjectDiscovery.discover(build)
                val relPath =
                    build.projectDir.relativeToOrNull(rootProject.projectDir)?.path
                        ?: build.projectDir.absolutePath
                IncludedBuildInfo(
                    name = build.name,
                    dir = build.projectDir,
                    relPath = relPath,
                    projects = projects.map { project -> project.path to project.directory },
                    sourceLayouts = projects.associate { project -> project.path to project.sourceLayout },
                )
            }

        private fun collectIncludedBuildPaths(rootProject: Project): List<String> =
            rootProject.gradle.includedBuilds.map { build ->
                "${build.name}:${build.projectDir.canonicalPath}"
            }

        private fun collectRootProjectSourceLayouts(rootProject: Project): Map<String, ProjectSourceLayout> {
            val projects = ProjectScanner.collectProjects(rootProject)
            return projects.associate { project ->
                val nestedProjectDirectories =
                    projects
                        .asSequence()
                        .filterNot { candidate -> candidate == project }
                        .map { candidate -> candidate.projectDir }
                        .filter { directory -> directory.startsWith(project.projectDir) }
                        .toList()
                project.path to
                    ProjectScanner.excludeDirectories(
                        ProjectScanner.discoverSourceLayout(project),
                        nestedProjectDirectories,
                    )
            }
        }

        private fun collectRootSourceFiles(
            projects: List<Project>,
            layouts: Map<String, ProjectSourceLayout>,
        ): List<File> =
            projects.flatMap { project ->
                val sources =
                    layouts[project.path]
                        ?.let { layout -> ProjectScanner.collectSourceFiles(project.projectDir, layout) }
                        .orEmpty()
                        .map { source -> source.file }
                sources + listOfNotNull(project.buildFile.takeIf(File::exists))
            }

        private fun sourceLayoutFingerprint(
            rootBuild: String,
            rootProjects: List<Project>,
            rootLayouts: Map<String, ProjectSourceLayout>,
            includedBuilds: List<IncludedBuildInfo>,
        ): List<String> {
            val rootEntries =
                rootProjects.flatMap { project ->
                    rootLayouts[project.path]
                        ?.let { layout ->
                            ProjectScanner.sourceLayoutFingerprint(
                                build = rootBuild,
                                projectPath = project.path,
                                projectDir = project.projectDir,
                                layout = layout,
                            )
                        }.orEmpty()
                }
            val includedEntries =
                includedBuilds.flatMap { build ->
                    build.projects.flatMap { (path, projectDirectory) ->
                        build.sourceLayouts[path]
                            ?.let { layout ->
                                ProjectScanner.sourceLayoutFingerprint(
                                    build = build.name,
                                    projectPath = path,
                                    projectDir = projectDirectory,
                                    layout = layout,
                                )
                            }.orEmpty()
                    }
                }
            return (rootEntries + includedEntries).sorted()
        }

        private fun collectIncludedBuildSourceFiles(builds: List<IncludedBuildInfo>): List<File> =
            builds.flatMap { build ->
                build.projects.flatMap { (path, projectDirectory) ->
                    val sources =
                        build.sourceLayouts[path]
                            ?.let { layout -> ProjectScanner.collectSourceFiles(projectDirectory, layout) }
                            .orEmpty()
                            .map { source -> source.file }
                    val buildFiles =
                        listOf("build.gradle.kts", "build.gradle")
                            .map { name -> File(projectDirectory, name) }
                            .filter(File::exists)
                    sources + buildFiles
                }
            }

        private fun wireAutoGenerate(
            rootProject: Project,
            contextTask: org.gradle.api.tasks.TaskProvider<ContextTask>,
        ) {
            rootProject.plugins.withType(LifecycleBasePlugin::class.java) {
                rootProject.tasks.named(LifecycleBasePlugin.ASSEMBLE_TASK_NAME).configure { assemble ->
                    assemble.dependsOn(contextTask)
                }
            }
        }
    }
}
