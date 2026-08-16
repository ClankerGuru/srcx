package zone.clanker.gradle.srcx.scan

import org.gradle.api.Project
import org.jetbrains.kotlin.com.intellij.testFramework.LightVirtualFile
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import zone.clanker.gradle.srcx.Srcx
import zone.clanker.gradle.srcx.analysis.analyzeProjectFiles
import zone.clanker.gradle.srcx.model.ArtifactGroup
import zone.clanker.gradle.srcx.model.ArtifactName
import zone.clanker.gradle.srcx.model.ArtifactVersion
import zone.clanker.gradle.srcx.model.DependencyEntry
import zone.clanker.gradle.srcx.model.FileFacts
import zone.clanker.gradle.srcx.model.FilePath
import zone.clanker.gradle.srcx.model.PackageName
import zone.clanker.gradle.srcx.model.ProjectPath
import zone.clanker.gradle.srcx.model.ProjectSummary
import zone.clanker.gradle.srcx.model.Reference
import zone.clanker.gradle.srcx.model.SourceSetName
import zone.clanker.gradle.srcx.model.SourceSetSummary
import zone.clanker.gradle.srcx.model.Symbol
import zone.clanker.gradle.srcx.model.SymbolDetailKind
import zone.clanker.gradle.srcx.model.SymbolEntry
import zone.clanker.gradle.srcx.model.SymbolKind
import zone.clanker.gradle.srcx.model.SymbolName
import zone.clanker.gradle.srcx.parse.PsiEnvironment
import zone.clanker.gradle.srcx.parse.PsiParser
import java.io.File

private const val DEFAULT_BUILD_NAME = "root"

/** Immutable project facts consumed by one project extraction. */
internal data class ProjectExtractionRequest(
    val projectDir: File,
    val projectPath: String,
    val subprojectPaths: List<String>,
    val dependencies: List<DependencyEntry>,
    val build: String = DEFAULT_BUILD_NAME,
    val sourceLayout: ProjectSourceLayout? = null,
)

/**
 * Symbol and dependency extraction utilities.
 *
 * Extracts symbols (classes, functions, properties) from source directories
 * and dependencies from build files or the Gradle configuration API.
 */
@Suppress("TooManyFunctions")
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
                logger.warn("srcx: Analysis failed for '$projectName': ${e.message}", e)
                return null
            }
        }
    }

    /** Minimum number of colon-separated parts in a Maven coordinate (group:artifact:version). */
    private const val MIN_COORDINATE_PARTS = 3

    /** Extract symbols from source directories using PSI parsing. */
    internal fun extractSymbolsFromDirs(dirs: List<File>): List<SymbolEntry> {
        val sourceFiles = collectSourceFiles(dirs)
        if (sourceFiles.isEmpty()) return emptyList()
        val preparedSources =
            sourceFiles.map { file ->
                PreparedSource(
                    file = file,
                    sourceText = file.readText(charset = Charsets.UTF_8),
                )
            }

        val env = PsiEnvironment.shared() ?: return emptyList()
        return synchronized(env) {
            val parser = PsiParser(env)
            preparedSources.flatMap { source ->
                val sourceDir = dirs.first { source.file.startsWith(it) }
                extractFacts(parser, source.file, source.sourceText)
                    .declarations
                    .map { symbol -> symbol.toEntry(sourceDir) }
            }
        }
    }

    private val SUPPORTED_EXTENSIONS = setOf("kt", "java", "kts")

    private fun collectSourceFiles(dirs: List<File>): List<File> =
        dirs
            .filter { it.exists() }
            .flatMap { dir ->
                dir.walkTopDown().filter { it.isFile && it.extension in SUPPORTED_EXTENSIONS }.toList()
            }.distinctBy { it.absolutePath }
            .sortedBy { it.absolutePath }

    private fun extractFacts(
        parser: PsiParser,
        file: File,
        sourceText: String,
    ): FileFacts =
        runCatching { parser.extractFacts(file, sourceText) }
            .onFailure { error ->
                logger.warn("srcx: Symbol extraction failed for '${file.name}': ${error.message}", error)
            }.getOrDefault(FileFacts(emptyList(), emptyList()))

    private data class PreparedSource(
        val file: File,
        val sourceText: String,
    )

    private data class PreparedProjectSource(
        val sourceSet: SourceSetName,
        val file: File,
        val sourceText: String,
    )

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
            filePath = FilePath(ProjectScanner.relativeSourcePath(sourceDir, file)),
            lineNumber = line,
        )

    /** Extract a project summary using the Gradle Project API (has access to configurations). */
    internal fun extractProjectSummary(
        project: Project,
        rootProject: Project,
    ): ProjectSummary = extractProjectScan(project, rootProject).summary

    /** Extract raw project facts using the Gradle Project API. */
    internal fun extractProjectScan(
        project: Project,
        rootProject: Project,
    ): ProjectScan {
        val subprojectPaths =
            if (project == rootProject) {
                rootProject.subprojects.map { it.path }.sorted()
            } else {
                emptyList()
            }
        return scanProject(
            ProjectExtractionRequest(
                projectDir = project.projectDir,
                projectPath = project.path,
                subprojectPaths = subprojectPaths,
                dependencies = extractDependenciesFromProject(project),
                build = rootProject.name,
                sourceLayout = ProjectScanner.discoverSourceLayout(project),
            ),
        )
    }

    /** Extract a project summary from pre-computed data (no Gradle Project access). */
    internal fun extractProjectSummaryFromData(
        projectDir: File,
        projectPath: String,
        subprojectPaths: List<String>,
        dependencies: List<DependencyEntry>,
    ): ProjectSummary =
        extractProjectScanFromData(
            ProjectExtractionRequest(
                projectDir = projectDir,
                projectPath = projectPath,
                subprojectPaths = subprojectPaths,
                dependencies = dependencies,
            ),
        ).summary

    /** Extract raw project facts from configuration-time data for a root build project. */
    internal fun extractProjectScanFromData(request: ProjectExtractionRequest): ProjectScan = scanProject(request)

    /** Extract a project summary from a standalone directory (not backed by Gradle Project API). */
    internal fun extractStandaloneProjectSummary(
        projectDir: File,
        projectPath: String,
    ): ProjectSummary = extractStandaloneProjectScan(projectDir, projectPath).summary

    /** Extract raw project facts from a standalone included-build project. */
    internal fun extractStandaloneProjectScan(
        projectDir: File,
        projectPath: String,
        build: String = projectDir.name,
        sourceLayout: ProjectSourceLayout? = null,
    ): ProjectScan =
        scanProject(
            ProjectExtractionRequest(
                projectDir = projectDir,
                projectPath = projectPath,
                subprojectPaths = emptyList(),
                dependencies = extractDependenciesFromBuildFile(projectDir),
                build = build,
                sourceLayout = sourceLayout,
            ),
        )

    private fun scanProject(request: ProjectExtractionRequest): ProjectScan {
        val layout = ProjectScanner.resolveSourceLayout(request.projectDir, request.sourceLayout)
        val files = scanProjectFiles(request.projectDir, layout)
        val summary =
            buildProjectSummary(
                projectDir = request.projectDir,
                projectPath = request.projectPath,
                subprojectPaths = request.subprojectPaths,
                dependencies = request.dependencies,
                sourceLayout = layout,
                files = files,
            )
        return ProjectScan(
            build = request.build,
            projectPath = ProjectPath(request.projectPath),
            files = files,
            summary = summary,
        )
    }

    private fun scanProjectFiles(
        projectDir: File,
        sourceLayout: ProjectSourceLayout,
    ): List<ProjectFileScan> {
        val sourceFiles = ProjectScanner.collectSourceFiles(projectDir, sourceLayout)
        if (sourceFiles.isEmpty()) return emptyList()
        val preparedSources =
            sourceFiles.map { ownedSource ->
                PreparedProjectSource(
                    sourceSet = ownedSource.sourceSet,
                    file = ownedSource.file,
                    sourceText = ownedSource.file.readText(charset = Charsets.UTF_8),
                )
            }

        val env = PsiEnvironment.shared()
        val scans =
            if (env == null) {
                preparedSources.map { source ->
                    scanSourceFile(
                        projectDir = projectDir,
                        source = source,
                        parser = null,
                    )
                }
            } else {
                synchronized(env) {
                    val parser = PsiParser(env)
                    preparedSources.map { source ->
                        scanSourceFile(
                            projectDir = projectDir,
                            source = source,
                            parser = parser,
                        )
                    }
                }
            }
        return scans.sortedWith(
            compareBy<ProjectFileScan> { scan ->
                sourceLayout.sourceSets.indexOfFirst { sourceSet -> sourceSet.name == scan.sourceSet }
            }.thenBy { it.projectRelativeFile },
        )
    }

    private fun scanSourceFile(
        projectDir: File,
        source: PreparedProjectSource,
        parser: PsiParser?,
    ): ProjectFileScan {
        val facts =
            parser?.let { activeParser ->
                extractFacts(
                    parser = activeParser,
                    file = source.file,
                    sourceText = source.sourceText,
                )
            } ?: FileFacts(
                declarations = emptyList(),
                references = emptyList(),
            )
        return ProjectFileScan(
            sourceSet = source.sourceSet,
            projectRelativeFile = ProjectScanner.relativeSourcePath(projectDir, source.file),
            declarations = facts.declarations.sortedWith(declarationFactComparator),
            references = facts.references.sortedWith(referenceFactComparator),
            sourceText = source.sourceText,
        )
    }

    private val declarationFactComparator =
        compareBy<Symbol> { it.line }
            .thenBy { it.qualifiedName }
            .thenBy { it.kind.name }

    private val referenceFactComparator =
        compareBy<Reference> { it.line }
            .thenBy { it.kind.name }
            .thenBy { it.sourceQualifiedName.orEmpty() }
            .thenBy { it.targetQualifiedName.orEmpty() }
            .thenBy { it.targetName }
            .thenBy { it.context }

    @Suppress("LongParameterList")
    private fun buildProjectSummary(
        projectDir: File,
        projectPath: String,
        subprojectPaths: List<String>,
        dependencies: List<DependencyEntry>,
        sourceLayout: ProjectSourceLayout,
        files: List<ProjectFileScan>,
    ): ProjectSummary {
        val sourceSetSummaries =
            sourceLayout.sourceSets.map { configuredSourceSet ->
                val sourceSet = configuredSourceSet.name
                val dirs = configuredSourceSet.directories.sortedByDescending { directory -> directory.path.length }
                val symbols =
                    files
                        .filter { it.sourceSet == sourceSet }
                        .flatMap { scan ->
                            scan.declarations.map { symbol ->
                                val sourceDir =
                                    dirs.firstOrNull { directory ->
                                        ProjectScanner.containsSourceFile(directory, symbol.file)
                                    } ?: projectDir
                                symbol.toEntry(sourceDir)
                            }
                        }
                val dirNames =
                    dirs
                        .filter(File::exists)
                        .map { directory -> ProjectScanner.relativeSourcePath(projectDir, directory) }
                        .sorted()
                SourceSetSummary(sourceSet, symbols, dirNames)
            }
        val sourceFiles = ProjectScanner.collectSourceFiles(projectDir, sourceLayout).map { source -> source.file }
        val projectAnalysis =
            runCatching { analyzeProjectFiles(sourceFiles, projectDir).toSummary() }
                .getOrElse { error -> handleAnalysisFailure(error, projectDir.name) }
        return ProjectSummary(
            projectPath = ProjectPath(projectPath),
            symbols = sourceSetSummaries.flatMap { it.symbols },
            dependencies = dependencies,
            buildFile = buildFileNameFromDir(projectDir),
            sourceDirs = sourceSetSummaries.flatMap { it.sourceDirs },
            subprojects = subprojectPaths.sorted(),
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
    @Suppress("UnreachableCode")
    internal fun extractDependenciesFromBuildFile(
        projectDir: File,
        excludeScopes: Set<String> = DEFAULT_EXCLUDED_DEP_SCOPES,
    ): List<DependencyEntry> {
        val buildFile =
            File(projectDir, "build.gradle.kts").takeIf { it.exists() }
                ?: File(projectDir, "build.gradle").takeIf { it.exists() }
                ?: return emptyList()
        val sourceText = buildFile.readText(charset = Charsets.UTF_8)

        val env = PsiEnvironment.shared() ?: return emptyList()
        return synchronized(env) {
            val vf = LightVirtualFile(buildFile.name, KotlinFileType.INSTANCE, sourceText)
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
