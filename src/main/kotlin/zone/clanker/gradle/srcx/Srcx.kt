package zone.clanker.gradle.srcx

import org.gradle.api.Action
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.initialization.Settings
import zone.clanker.gradle.srcx.analysis.analyzeProject
import zone.clanker.gradle.srcx.model.ArtifactGroup
import zone.clanker.gradle.srcx.model.ArtifactName
import zone.clanker.gradle.srcx.model.ArtifactVersion
import zone.clanker.gradle.srcx.model.DependencyEntry
import zone.clanker.gradle.srcx.model.FilePath
import zone.clanker.gradle.srcx.model.PackageName
import zone.clanker.gradle.srcx.model.ProjectPath
import zone.clanker.gradle.srcx.model.ProjectSummary
import zone.clanker.gradle.srcx.model.SourceSetName
import zone.clanker.gradle.srcx.model.SourceSetSummary
import zone.clanker.gradle.srcx.model.SymbolEntry
import zone.clanker.gradle.srcx.model.SymbolKind
import zone.clanker.gradle.srcx.model.SymbolName
import zone.clanker.gradle.srcx.report.DashboardRenderer
import zone.clanker.gradle.srcx.report.IncludedBuildRenderer
import zone.clanker.gradle.srcx.report.ProjectReportRenderer
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Root identity object for the srcx source symbol extraction plugin.
 *
 * Contains all constants, the [SettingsExtension] DSL, and the [SettingsPlugin] entry point.
 * Tasks and reports reference [Srcx.GROUP], [Srcx.TASK_CONTEXT], etc.
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

    private const val THREAD_POOL_SIZE = 4

    /** Regex group indices for dependency pattern in [SettingsPlugin.extractDependenciesFromBuildFile]. */
    private const val DEP_GROUP_SCOPE = 1
    private const val DEP_GROUP_GROUP = 2
    private const val DEP_GROUP_ARTIFACT = 3
    private const val DEP_GROUP_VERSION = 4

    /**
     * DSL extension registered as `srcx { }` on the Settings object.
     *
     * Controls the output directory and auto-generation behavior.
     *
     * ```kotlin
     * srcx {
     *     outputDir = ".srcx"
     *     autoGenerate = true
     * }
     * ```
     *
     * @see SettingsPlugin
     */
    open class SettingsExtension {
        /** Output directory relative to the root project. */
        var outputDir: String = OUTPUT_DIR

        /** When true, compileKotlin/compileJava tasks will finalize with srcx-symbols. */
        var autoGenerate: Boolean = false
    }

    /**
     * Settings plugin entry point: `id("zone.clanker.gradle.srcx")`.
     *
     * Sequence:
     * 1. Register the [SettingsExtension]
     * 2. Use `rootProject` callback to wire tasks after DSL runs
     * 3. Register [TASK_CONTEXT] — generates symbols, analysis, and context report
     * 4. Register [TASK_CLEAN] — deletes .srcx output
     * 5. Optionally wire compile tasks to finalize with srcx-context
     */
    @Suppress("TooManyFunctions")
    class SettingsPlugin : Plugin<Settings> {
        override fun apply(settings: Settings) {
            val extension = settings.extensions.create(EXTENSION_NAME, SettingsExtension::class.java)

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
            registerContextTask(rootProject, extension)
            registerCleanTask(rootProject, extension)
            if (extension.autoGenerate) {
                wireAutoGenerate(rootProject)
            }
        }

        internal fun collectProjects(rootProject: Project): List<Project> =
            listOf(rootProject) + rootProject.subprojects

        internal fun registerContextTask(
            rootProject: Project,
            extension: SettingsExtension,
        ) {
            rootProject.tasks.register(TASK_CONTEXT).configure { task ->
                task.group = GROUP
                task.description = "Generate comprehensive context report"
                task.doLast {
                    val projects = collectProjects(rootProject)

                    // Per-project symbol reports
                    runParallel(projects) { project ->
                        val summary = extractProjectSummary(project, rootProject)
                        writeProjectReport(rootProject, summary, extension)
                        "OK ${project.path}"
                    }
                    val includedBuilds = rootProject.gradle.includedBuilds
                    generateIncludedBuildReports(includedBuilds, extension)

                    // Context report
                    val summaries = projects.map { extractProjectSummary(it, rootProject) }
                    val includedBuildRefs =
                        includedBuilds.map { build ->
                            val relPath = build.projectDir.relativeTo(rootProject.projectDir).path
                            DashboardRenderer.IncludedBuildRef(build.name, relPath)
                        }
                    val includedBuildSummaries = collectIncludedBuildSummaries(includedBuilds)
                    val buildPairs = includedBuilds.map { it.name to it.projectDir }
                    val buildEdges = computeBuildEdges(buildPairs, includedBuildSummaries)
                    val classDiagram = generateClassDiagram(rootProject)
                    val renderer =
                        DashboardRenderer(
                            rootName = rootProject.name,
                            summaries = summaries,
                            includedBuilds = includedBuildRefs,
                            includedBuildSummaries = includedBuildSummaries,
                            buildEdges = buildEdges,
                            classDiagram = classDiagram,
                        )
                    val outputDir = File(rootProject.projectDir, extension.outputDir)
                    outputDir.mkdirs()
                    File(outputDir, "context.md").writeText(renderer.render())
                    writeGitignore(rootProject, extension)
                    println("srcx: context written to ${extension.outputDir}/context.md")
                }
            }
        }

        internal fun computeBuildEdges(
            builds: List<Pair<String, File>>,
            buildSummaries: Map<String, List<ProjectSummary>>,
        ): List<DashboardRenderer.BuildEdge> {
            val buildNames = builds.map { it.first }.toSet()
            return buildSummaries
                .flatMap { (name, projects) ->
                    projects.flatMap { summary ->
                        summary.dependencies
                            .map { it.artifact.value }
                            .filter { it in buildNames && it != name }
                            .map { DashboardRenderer.BuildEdge(name, it) }
                    }
                }.distinct()
        }

        internal fun generateClassDiagram(rootProject: Project): String {
            val allDirs =
                collectProjects(rootProject)
                    .flatMap { project ->
                        discoverSourceSets(project.projectDir).flatMap { ss ->
                            sourceSetDirs(project.projectDir, ss.value)
                        }
                    }.filter { it.exists() }
            if (allDirs.isEmpty()) return ""
            return runCatching {
                val sources =
                    zone.clanker.gradle.srcx.analysis
                        .scanSources(allDirs)
                val components =
                    zone.clanker.gradle.srcx.analysis
                        .classifyAll(sources)
                val depEdges =
                    zone.clanker.gradle.srcx.analysis
                        .buildDependencyGraph(components)
                zone.clanker.gradle.srcx.analysis
                    .generateDependencyDiagram(components, depEdges)
            }.getOrDefault("")
        }

        internal fun registerCleanTask(
            rootProject: Project,
            extension: SettingsExtension,
        ) {
            rootProject.tasks.register(TASK_CLEAN).configure { task ->
                task.group = GROUP
                task.description = "Delete the srcx output directory"
                task.doLast {
                    val outputDir = File(rootProject.projectDir, extension.outputDir)
                    cleanOutputDir(outputDir)
                    for (build in rootProject.gradle.includedBuilds) {
                        val buildOutputDir = File(build.projectDir, extension.outputDir)
                        cleanOutputDir(buildOutputDir)
                    }
                }
            }
        }

        internal fun cleanOutputDir(outputDir: File) {
            if (outputDir.exists()) {
                val count = outputDir.walkTopDown().filter { it.isFile }.count()
                outputDir.deleteRecursively()
                println("srcx: deleted ${outputDir.name}/ ($count files)")
            } else {
                println("srcx: nothing to clean")
            }
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

        internal fun generateIncludedBuildReports(
            builds: Collection<org.gradle.api.initialization.IncludedBuild>,
            extension: SettingsExtension,
        ) {
            for (build in builds) {
                val buildDir = build.projectDir
                val buildOutputDir = File(buildDir, extension.outputDir)
                buildOutputDir.mkdirs()

                val projectDirs =
                    discoverIncludedBuildProjects(build).map { (path, dir) ->
                        dir to path
                    }

                val summaries =
                    projectDirs.map { (dir, path) ->
                        extractStandaloneProjectSummary(dir, path)
                    }

                for (summary in summaries) {
                    val renderer = ProjectReportRenderer(summary)
                    val sanitized =
                        summary.projectPath.value
                            .replace(":", "/")
                            .trimStart('/')
                            .ifEmpty { "root" }
                    val reportDir = File(buildOutputDir, sanitized)
                    reportDir.mkdirs()
                    File(reportDir, "symbols.md").writeText(renderer.render())
                }

                val buildRenderer = IncludedBuildRenderer(build.name, summaries)
                File(buildOutputDir, "context.md").writeText(buildRenderer.render())
                writeGitignoreAt(buildOutputDir)
            }
            if (builds.isNotEmpty()) {
                println("srcx: generated reports for ${builds.size} included build(s)")
            }
        }

        private fun writeGitignoreAt(outputDir: File) {
            outputDir.mkdirs()
            File(outputDir, ".gitignore").writeText("*\n")
        }

        internal fun collectIncludedBuildSummaries(
            builds: Collection<org.gradle.api.initialization.IncludedBuild>,
        ): Map<String, List<ProjectSummary>> {
            val result = mutableMapOf<String, List<ProjectSummary>>()
            for (build in builds) {
                val projectEntries = discoverIncludedBuildProjects(build)
                result[build.name] =
                    projectEntries.map { (path, dir) ->
                        extractStandaloneProjectSummary(dir, path)
                    }
            }
            return result
        }

        internal fun extractStandaloneProjectSummary(
            projectDir: File,
            projectPath: String,
        ): ProjectSummary {
            val sourceSets = discoverSourceSets(projectDir)
            val allSymbols = mutableListOf<SymbolEntry>()
            val sourceSetSummaries = mutableListOf<SourceSetSummary>()

            for (sourceSetName in sourceSets) {
                val dirs = sourceSetDirs(projectDir, sourceSetName.value)
                val symbols = extractSymbolsFromDirs(dirs)
                allSymbols.addAll(symbols)
                val dirNames = dirs.filter { it.exists() }.map { it.relativeTo(projectDir).path }
                sourceSetSummaries.add(SourceSetSummary(sourceSetName, symbols, dirNames))
            }

            val buildFileName =
                when {
                    File(projectDir, "build.gradle.kts").exists() -> "build.gradle.kts"
                    File(projectDir, "build.gradle").exists() -> "build.gradle"
                    else -> "none"
                }

            val allSourceDirNames =
                sourceSets.flatMap { ssName ->
                    sourceSetDirs(projectDir, ssName.value)
                        .filter { it.exists() }
                        .map { it.relativeTo(projectDir).path }
                }

            // Subprojects are discovered by the caller via Gradle API
            val subprojects = emptyList<String>()

            val dependencies = extractDependenciesFromBuildFile(projectDir)

            val allDirs = sourceSets.flatMap { sourceSetDirs(projectDir, it.value) }
            val projectAnalysis =
                runCatching {
                    analyzeProject(allDirs, projectDir).toSummary()
                }.getOrNull()

            return ProjectSummary(
                projectPath = ProjectPath(projectPath),
                symbols = allSymbols,
                dependencies = dependencies,
                buildFile = buildFileName,
                sourceDirs = allSourceDirNames,
                subprojects = subprojects,
                sourceSets = sourceSetSummaries,
                analysis = projectAnalysis,
            )
        }

        internal fun extractProjectSummary(
            project: Project,
            rootProject: Project,
        ): ProjectSummary {
            val sourceSets = discoverSourceSets(project.projectDir)
            val allSymbols = mutableListOf<SymbolEntry>()
            val sourceSetSummaries = mutableListOf<SourceSetSummary>()

            for (sourceSetName in sourceSets) {
                val dirs = sourceSetDirs(project.projectDir, sourceSetName.value)
                val symbols = extractSymbolsFromDirs(dirs)
                allSymbols.addAll(symbols)
                val dirNames = dirs.filter { it.exists() }.map { it.relativeTo(project.projectDir).path }
                sourceSetSummaries.add(SourceSetSummary(sourceSetName, symbols, dirNames))
            }

            val allSourceDirNames =
                sourceSets.flatMap { ssName ->
                    sourceSetDirs(project.projectDir, ssName.value)
                        .filter { it.exists() }
                        .map { it.relativeTo(project.projectDir).path }
                }

            val dependencies = extractDependencies(project)
            val buildFileName = buildFileName(project)
            val subprojectPaths =
                if (project == rootProject) {
                    rootProject.subprojects.map { it.path }
                } else {
                    emptyList()
                }
            val allDirs = sourceSets.flatMap { sourceSetDirs(project.projectDir, it.value) }
            val projectAnalysis =
                runCatching {
                    analyzeProject(allDirs, project.projectDir).toSummary()
                }.getOrNull()

            return ProjectSummary(
                projectPath = ProjectPath(project.path),
                symbols = allSymbols,
                dependencies = dependencies,
                buildFile = buildFileName,
                sourceDirs = allSourceDirNames,
                subprojects = subprojectPaths,
                sourceSets = sourceSetSummaries,
                analysis = projectAnalysis,
            )
        }

        internal fun discoverSourceSets(projectDir: File): List<SourceSetName> {
            val srcDir = File(projectDir, "src")
            if (!srcDir.exists() || !srcDir.isDirectory) return emptyList()
            val children = srcDir.listFiles() ?: return emptyList()
            return children
                .filter { it.isDirectory }
                .filter { dir ->
                    dir.walkTopDown().any { f ->
                        f.isFile &&
                            (
                                f.extension == "kt" ||
                                    f.extension == "java" ||
                                    f.extension == "groovy" ||
                                    f.extension == "scala"
                            )
                    }
                }.map { SourceSetName(it.name) }
                .sortedWith(sourceSetComparator())
        }

        private fun sourceSetComparator(): Comparator<SourceSetName> {
            val priority = mapOf("main" to 0, "test" to 1, "androidTest" to 2, "commonMain" to 3, "commonTest" to 4)
            return Comparator { a, b ->
                val pa = priority[a.value] ?: Int.MAX_VALUE
                val pb = priority[b.value] ?: Int.MAX_VALUE
                if (pa != pb) pa.compareTo(pb) else a.value.compareTo(b.value)
            }
        }

        internal fun sourceSetDirs(projectDir: File, sourceSetName: String): List<File> =
            listOf(
                File(projectDir, "src/$sourceSetName/kotlin"),
                File(projectDir, "src/$sourceSetName/java"),
            )

        @Suppress("LongMethod")
        internal fun extractSymbolsFromDirs(dirs: List<File>): List<SymbolEntry> {
            val results = mutableListOf<SymbolEntry>()
            val modifiers = "public|private|internal|protected"
            val classModifiers = "$modifiers|abstract|open|sealed|data"
            val funModifiers = "$modifiers|override|open|abstract|suspend"
            val propModifiers = "$modifiers|override|open|abstract|const"

            val classPattern =
                Regex("""^\s*(?:(?:$classModifiers)\s+)*(?:class|interface|object|enum\s+class)\s+(\w+)""")
            val functionPattern =
                Regex("""^\s*(?:(?:$funModifiers)\s+)*fun\s+(?:<[^>]+>\s+)?(\w+)""")
            val propertyPattern =
                Regex("""^\s*(?:(?:$propModifiers)\s+)*(?:val|var)\s+(\w+)""")
            val packagePattern = Regex("""^\s*package\s+([\w.]+)""")

            for (sourceDir in dirs) {
                if (sourceDir.exists()) {
                    sourceDir
                        .walkTopDown()
                        .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
                        .forEach { file ->
                            val relativePath = file.relativeTo(sourceDir).path
                            val lines = file.readLines()
                            var currentPackage = ""

                            lines.forEachIndexed { index, line ->
                                packagePattern.find(line)?.let { match ->
                                    currentPackage = match.groupValues[1]
                                }
                                classPattern.find(line)?.let { match ->
                                    results.add(
                                        SymbolEntry(
                                            SymbolName(match.groupValues[1]),
                                            SymbolKind.CLASS,
                                            PackageName(currentPackage.ifEmpty { "_root_" }),
                                            FilePath(relativePath),
                                            index + 1,
                                        ),
                                    )
                                }
                                functionPattern.find(line)?.let { match ->
                                    results.add(
                                        SymbolEntry(
                                            SymbolName(match.groupValues[1]),
                                            SymbolKind.FUNCTION,
                                            PackageName(currentPackage.ifEmpty { "_root_" }),
                                            FilePath(relativePath),
                                            index + 1,
                                        ),
                                    )
                                }
                                if (!line.trimStart().startsWith("//")) {
                                    propertyPattern.find(line)?.let { match ->
                                        results.add(
                                            SymbolEntry(
                                                SymbolName(match.groupValues[1]),
                                                SymbolKind.PROPERTY,
                                                PackageName(currentPackage.ifEmpty { "_root_" }),
                                                FilePath(relativePath),
                                                index + 1,
                                            ),
                                        )
                                    }
                                }
                            }
                        }
                }
            }
            return results
        }

        private fun extractDependencies(project: Project): List<DependencyEntry> {
            val results = mutableListOf<DependencyEntry>()
            val scopes = listOf("api", "implementation", "compileOnly", "runtimeOnly", "testImplementation")
            for (scope in scopes) {
                val config = project.configurations.findByName(scope) ?: continue
                config.dependencies.forEach { dep ->
                    if (dep.group != null) {
                        results.add(
                            DependencyEntry(
                                group = ArtifactGroup(dep.group.orEmpty()),
                                artifact = ArtifactName(dep.name),
                                version = ArtifactVersion(dep.version ?: "unspecified"),
                                scope = scope,
                            ),
                        )
                    }
                }
            }
            return results
        }

        internal fun extractDependenciesFromBuildFile(projectDir: File): List<DependencyEntry> {
            val buildFile =
                File(projectDir, "build.gradle.kts").takeIf { it.exists() }
                    ?: File(projectDir, "build.gradle").takeIf { it.exists() }
                    ?: return emptyList()

            val results = mutableListOf<DependencyEntry>()
            val depPattern =
                Regex(
                    """(api|implementation|compileOnly|runtimeOnly|testImplementation)\("([^:]+):([^:]+):([^"]+)"\)""",
                )
            buildFile.readLines().forEach { line ->
                depPattern.find(line)?.let { match ->
                    results.add(
                        DependencyEntry(
                            group = ArtifactGroup(match.groupValues[DEP_GROUP_GROUP]),
                            artifact = ArtifactName(match.groupValues[DEP_GROUP_ARTIFACT]),
                            version = ArtifactVersion(match.groupValues[DEP_GROUP_VERSION]),
                            scope = match.groupValues[DEP_GROUP_SCOPE],
                        ),
                    )
                }
            }
            return results
        }

        @Suppress("SwallowedException")
        internal fun discoverIncludedBuildProjects(
            build: org.gradle.api.initialization.IncludedBuild,
        ): List<Pair<String, File>> =
            runCatching {
                val target = build.javaClass.getMethod("getTarget").invoke(build)
                val registry =
                    target!!.javaClass.getMethod("getProjects").let {
                        it.isAccessible = true
                        it.invoke(target)
                    }
                val allProjects =
                    registry!!.javaClass.getMethod("getAllProjects").let {
                        it.isAccessible = true
                        it.invoke(registry) as Set<*>
                    }
                allProjects.mapNotNull { ps ->
                    val path =
                        ps!!.javaClass.getMethod("getIdentityPath").let {
                            it.isAccessible = true
                            it.invoke(ps).toString()
                        }
                    val dir =
                        ps.javaClass.getMethod("getProjectDir").let {
                            it.isAccessible = true
                            it.invoke(ps) as File
                        }
                    // Strip the build prefix from the path to get project-relative path
                    val relativePath =
                        path
                            .removePrefix(":${build.name}")
                            .ifEmpty { ":" }
                    relativePath to dir
                }
            }.getOrDefault(listOf(":" to build.projectDir))

        internal fun buildFileName(project: Project): String =
            when {
                project.file("build.gradle.kts").exists() -> "build.gradle.kts"
                project.file("build.gradle").exists() -> "build.gradle"
                else -> "none"
            }

        internal fun writeProjectReport(
            rootProject: Project,
            summary: ProjectSummary,
            extension: SettingsExtension,
        ) {
            val renderer = ProjectReportRenderer(summary)
            val sanitized =
                summary.projectPath.value
                    .replace(":", "/")
                    .trimStart('/')
                    .ifEmpty { "root" }
            val reportDir = File(rootProject.projectDir, "${extension.outputDir}/$sanitized")
            reportDir.mkdirs()
            File(reportDir, "symbols.md").writeText(renderer.render())
        }

        internal fun writeGitignore(
            rootProject: Project,
            extension: SettingsExtension,
        ) {
            val outputDir = File(rootProject.projectDir, extension.outputDir)
            outputDir.mkdirs()
            File(outputDir, ".gitignore").writeText("*\n")
        }

        internal fun runParallel(
            projects: List<Project>,
            work: (Project) -> String,
        ) {
            if (projects.isEmpty()) {
                println("srcx: No projects to process.")
                return
            }
            val pool = Executors.newFixedThreadPool(THREAD_POOL_SIZE)
            val futures =
                projects.map { project ->
                    pool.submit(
                        Callable {
                            runCatching { work(project) }
                                .getOrElse { e -> "FAIL ${project.path}: ${e.message}" }
                        },
                    )
                }
            val results = futures.map { it.get() }
            pool.shutdown()
            pool.awaitTermination(Long.MAX_VALUE, TimeUnit.MILLISECONDS)
            results.forEach { println(it) }
            val failed = results.count { it.startsWith("FAIL") }
            println("srcx: symbols complete -- ${projects.size} projects, $failed failed")
            if (failed > 0) error("srcx: $failed project(s) failed during generation")
        }
    }
}
