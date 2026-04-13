package zone.clanker.gradle.srcx.scan

import org.gradle.api.Project
import org.jetbrains.kotlin.com.intellij.testFramework.LightVirtualFile
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import zone.clanker.gradle.srcx.Srcx
import zone.clanker.gradle.srcx.analysis.analyzeProject
import zone.clanker.gradle.srcx.model.ArtifactGroup
import zone.clanker.gradle.srcx.model.ArtifactName
import zone.clanker.gradle.srcx.model.ArtifactVersion
import zone.clanker.gradle.srcx.model.DependencyEntry
import zone.clanker.gradle.srcx.model.FilePath
import zone.clanker.gradle.srcx.model.PackageName
import zone.clanker.gradle.srcx.model.ProjectPath
import zone.clanker.gradle.srcx.model.ProjectSummary
import zone.clanker.gradle.srcx.model.SourceSetSummary
import zone.clanker.gradle.srcx.model.Symbol
import zone.clanker.gradle.srcx.model.SymbolDetailKind
import zone.clanker.gradle.srcx.model.SymbolEntry
import zone.clanker.gradle.srcx.model.SymbolKind
import zone.clanker.gradle.srcx.model.SymbolName
import zone.clanker.gradle.srcx.parse.PsiEnvironment
import zone.clanker.gradle.srcx.parse.PsiParser
import java.io.File

/**
 * Symbol and dependency extraction utilities.
 *
 * Extracts symbols (classes, functions, properties) from source directories
 * and dependencies from build files or the Gradle configuration API.
 */
object SymbolExtractor {
    private val logger =
        org.gradle.api.logging.Logging
            .getLogger(SymbolExtractor::class.java)

    /** Dependency scopes excluded from scanning by default. */
    internal val DEFAULT_EXCLUDED_DEP_SCOPES =
        Srcx.DEFAULT_EXCLUDED_DEP_SCOPES

    internal fun handleAnalysisFailure(e: Throwable, projectName: String): Nothing? {
        when (e) {
            is OutOfMemoryError -> {
                logger.error(
                    "srcx: Out of memory analyzing '$projectName'. " +
                        "Increase heap with org.gradle.jvmargs=-Xmx8g in gradle.properties",
                )
                throw e
            }
            else -> {
                logger.warn("srcx: Analysis failed for '$projectName': ${e.message}")
                return null
            }
        }
    }

    /** Minimum number of colon-separated parts in a Maven coordinate (group:artifact:version). */
    private const val MIN_COORDINATE_PARTS = 3

    /** Extract symbols from source directories using PSI parsing. */
    internal fun extractSymbolsFromDirs(dirs: List<File>): List<SymbolEntry> {
        val sourceFiles =
            dirs
                .filter { it.exists() }
                .flatMap { dir ->
                    dir
                        .walkTopDown()
                        .filter { it.isFile && it.extension in SUPPORTED_EXTENSIONS }
                        .toList()
                }
        if (sourceFiles.isEmpty()) return emptyList()

        val env = PsiEnvironment.shared() ?: return emptyList()
        return synchronized(env) {
            val parser = PsiParser(env)
            sourceFiles.flatMap { file ->
                val sourceDir = dirs.first { file.startsWith(it) }
                runCatching {
                    parser.extractDeclarations(file).map { symbol ->
                        symbol.toEntry(sourceDir)
                    }
                }.getOrDefault(emptyList())
            }
        }
    }

    private val SUPPORTED_EXTENSIONS = setOf("kt", "java", "kts")

    /** Convert a PSI [Symbol] to a [SymbolEntry] for the report pipeline. */
    private fun Symbol.toEntry(sourceDir: File): SymbolEntry =
        SymbolEntry(
            name = SymbolName(name.substringAfterLast(".")),
            kind =
                when (kind) {
                    SymbolDetailKind.FUNCTION -> SymbolKind.FUNCTION
                    SymbolDetailKind.PROPERTY -> SymbolKind.PROPERTY
                    else -> SymbolKind.CLASS
                },
            packageName = PackageName(packageName.ifEmpty { "_root_" }),
            filePath = FilePath(file.relativeTo(sourceDir).path),
            lineNumber = line,
        )

    /** Extract a project summary using the Gradle Project API (has access to configurations). */
    internal fun extractProjectSummary(
        project: Project,
        rootProject: Project,
    ): ProjectSummary {
        val sourceSets = ProjectScanner.discoverSourceSets(project.projectDir)
        val allSymbols = mutableListOf<SymbolEntry>()
        val sourceSetSummaries = mutableListOf<SourceSetSummary>()

        for (sourceSetName in sourceSets) {
            val dirs = ProjectScanner.sourceSetDirs(project.projectDir, sourceSetName.value)
            val symbols = extractSymbolsFromDirs(dirs)
            allSymbols.addAll(symbols)
            val dirNames = dirs.filter { it.exists() }.map { it.relativeTo(project.projectDir).path }
            sourceSetSummaries.add(SourceSetSummary(sourceSetName, symbols, dirNames))
        }

        val allSourceDirNames =
            sourceSets.flatMap { ssName ->
                ProjectScanner
                    .sourceSetDirs(project.projectDir, ssName.value)
                    .filter { it.exists() }
                    .map { it.relativeTo(project.projectDir).path }
            }

        val dependencies = extractDependenciesFromProject(project)
        val buildFileName = ProjectScanner.buildFileName(project)
        val subprojectPaths =
            if (project == rootProject) {
                rootProject.subprojects.map { it.path }
            } else {
                emptyList()
            }
        val allDirs = sourceSets.flatMap { ProjectScanner.sourceSetDirs(project.projectDir, it.value) }
        val projectAnalysis =
            runCatching {
                analyzeProject(allDirs, project.projectDir).toSummary()
            }.getOrElse { e -> handleAnalysisFailure(e, project.name) }

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

    /** Extract a project summary from pre-computed data (no Gradle Project access). */
    internal fun extractProjectSummaryFromData(
        projectDir: File,
        projectPath: String,
        subprojectPaths: List<String>,
        dependencies: List<DependencyEntry>,
    ): ProjectSummary {
        val sourceSets = ProjectScanner.discoverSourceSets(projectDir)
        val allSymbols = mutableListOf<SymbolEntry>()
        val sourceSetSummaries = mutableListOf<SourceSetSummary>()

        for (sourceSetName in sourceSets) {
            val dirs = ProjectScanner.sourceSetDirs(projectDir, sourceSetName.value)
            val symbols = extractSymbolsFromDirs(dirs)
            allSymbols.addAll(symbols)
            val dirNames = dirs.filter { it.exists() }.map { it.relativeTo(projectDir).path }
            sourceSetSummaries.add(SourceSetSummary(sourceSetName, symbols, dirNames))
        }

        val allSourceDirNames =
            sourceSets.flatMap { ssName ->
                ProjectScanner
                    .sourceSetDirs(projectDir, ssName.value)
                    .filter { it.exists() }
                    .map { it.relativeTo(projectDir).path }
            }

        val buildFileName = buildFileNameFromDir(projectDir)
        val allDirs = sourceSets.flatMap { ProjectScanner.sourceSetDirs(projectDir, it.value) }
        val projectAnalysis =
            runCatching {
                analyzeProject(allDirs, projectDir).toSummary()
            }.getOrElse { e -> handleAnalysisFailure(e, projectDir.name) }

        return ProjectSummary(
            projectPath = ProjectPath(projectPath),
            symbols = allSymbols,
            dependencies = dependencies,
            buildFile = buildFileName,
            sourceDirs = allSourceDirNames,
            subprojects = subprojectPaths,
            sourceSets = sourceSetSummaries,
            analysis = projectAnalysis,
        )
    }

    private fun buildFileNameFromDir(projectDir: File): String =
        when {
            File(projectDir, "build.gradle.kts").exists() -> "build.gradle.kts"
            File(projectDir, "build.gradle").exists() -> "build.gradle"
            else -> "none"
        }

    /** Extract a project summary from a standalone directory (not backed by Gradle Project API). */
    internal fun extractStandaloneProjectSummary(
        projectDir: File,
        projectPath: String,
    ): ProjectSummary {
        val sourceSets = ProjectScanner.discoverSourceSets(projectDir)
        val allSymbols = mutableListOf<SymbolEntry>()
        val sourceSetSummaries = mutableListOf<SourceSetSummary>()

        for (sourceSetName in sourceSets) {
            val dirs = ProjectScanner.sourceSetDirs(projectDir, sourceSetName.value)
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
                ProjectScanner
                    .sourceSetDirs(projectDir, ssName.value)
                    .filter { it.exists() }
                    .map { it.relativeTo(projectDir).path }
            }

        // Subprojects are discovered by the caller via Gradle API
        val subprojects = emptyList<String>()

        val dependencies = extractDependenciesFromBuildFile(projectDir)

        val allDirs = sourceSets.flatMap { ProjectScanner.sourceSetDirs(projectDir, it.value) }
        val projectAnalysis =
            runCatching {
                analyzeProject(allDirs, projectDir).toSummary()
            }.getOrElse { e -> handleAnalysisFailure(e, projectDir.name) }

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

    /** Call expressions in build files that are not dependency declarations. */
    private val PSI_SKIP_CALLS =
        setOf(
            "plugins", "kotlin", "id", "version", "apply",
            "repositories", "mavenCentral", "google", "gradlePluginPortal", "mavenLocal",
            "java", "tasks", "register", "named", "configure",
            "sourceSets", "dependencies", "buildscript", "allprojects", "subprojects",
            "project", "files", "fileTree", "exclude", "include",
            "create", "getting", "creating", "withType", "matching",
            "println", "print", "error", "require", "check",
            "listOf", "setOf", "mapOf", "mutableListOf", "mutableSetOf",
            "buildList", "buildString", "buildMap", "run", "let", "also", "apply", "with",
        )

    /** Extract dependencies from a build file by parsing dependency declarations with PSI. */
    internal fun extractDependenciesFromBuildFile(
        projectDir: File,
        excludeScopes: Set<String> = DEFAULT_EXCLUDED_DEP_SCOPES,
    ): List<DependencyEntry> {
        val buildFile =
            File(projectDir, "build.gradle.kts").takeIf { it.exists() }
                ?: File(projectDir, "build.gradle").takeIf { it.exists() }
                ?: return emptyList()

        val env = PsiEnvironment.shared() ?: return emptyList()
        return synchronized(env) {
            val vf = LightVirtualFile(buildFile.name, KotlinFileType.INSTANCE, buildFile.readText())
            val ktFile = env.psiManager.findFile(vf) as? KtFile ?: return@synchronized emptyList()

            ktFile
                .collectDescendantsOfType<KtCallExpression>()
                .filter { call ->
                    val name = call.calleeExpression?.text ?: return@filter false
                    name !in excludeScopes && name !in PSI_SKIP_CALLS
                }.mapNotNull { call ->
                    val scope = call.calleeExpression?.text ?: return@mapNotNull null
                    val arg =
                        call.valueArguments
                            .firstOrNull()
                            ?.getArgumentExpression() as? KtStringTemplateExpression
                            ?: return@mapNotNull null
                    val coordinate = arg.entries.joinToString("") { it.text }
                    val parts = coordinate.split(":")
                    if (parts.size < MIN_COORDINATE_PARTS) return@mapNotNull null
                    DependencyEntry(
                        group = ArtifactGroup(parts[0]),
                        artifact = ArtifactName(parts[1]),
                        version = ArtifactVersion(parts[2]),
                        scope = scope,
                    )
                }
        }
    }

    /** Extract dependencies from all project configurations, excluding specified scopes. */
    internal fun extractDependenciesFromProject(
        project: Project,
        excludeScopes: Set<String> = DEFAULT_EXCLUDED_DEP_SCOPES,
    ): List<DependencyEntry> {
        val results = mutableListOf<DependencyEntry>()
        for (config in project.configurations) {
            if (config.name in excludeScopes) continue
            config.dependencies.forEach { dep ->
                if (dep.group != null) {
                    results.add(
                        DependencyEntry(
                            group = ArtifactGroup(dep.group.orEmpty()),
                            artifact = ArtifactName(dep.name),
                            version = ArtifactVersion(dep.version ?: "unspecified"),
                            scope = config.name,
                        ),
                    )
                }
            }
        }
        return results.distinctBy { "${it.scope}:${it.group}:${it.artifact}" }
    }
}
