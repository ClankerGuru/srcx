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

    private const val DEFAULT_BUILD_NAME = "root"

    /** Extract symbols from source directories using PSI parsing. */
    internal fun extractSymbolsFromDirs(dirs: List<File>): List<SymbolEntry> {
        val sourceFiles = collectSourceFiles(dirs)
        if (sourceFiles.isEmpty()) return emptyList()

        val env = PsiEnvironment.shared() ?: return emptyList()
        return synchronized(env) {
            val parser = PsiParser(env)
            sourceFiles.flatMap { file ->
                val sourceDir = dirs.first { file.startsWith(it) }
                extractFacts(parser, file).declarations.map { symbol -> symbol.toEntry(sourceDir) }
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

    private fun extractFacts(parser: PsiParser, file: File): FileFacts =
        runCatching { parser.extractFacts(file) }
            .onFailure { error ->
                logger.warn("srcx: Symbol extraction failed for '${file.name}': ${error.message}", error)
            }.getOrDefault(FileFacts(emptyList(), emptyList()))

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
            projectDir = project.projectDir,
            projectPath = project.path,
            subprojectPaths = subprojectPaths,
            dependencies = extractDependenciesFromProject(project),
            build = rootProject.name,
        )
    }

    /** Extract a project summary from pre-computed data (no Gradle Project access). */
    internal fun extractProjectSummaryFromData(
        projectDir: File,
        projectPath: String,
        subprojectPaths: List<String>,
        dependencies: List<DependencyEntry>,
    ): ProjectSummary =
        extractProjectScanFromData(projectDir, projectPath, subprojectPaths, dependencies).summary

    /** Extract raw project facts from configuration-time data for a root build project. */
    internal fun extractProjectScanFromData(
        projectDir: File,
        projectPath: String,
        subprojectPaths: List<String>,
        dependencies: List<DependencyEntry>,
        build: String = DEFAULT_BUILD_NAME,
    ): ProjectScan = scanProject(projectDir, projectPath, subprojectPaths, dependencies, build)

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
    ): ProjectScan =
        scanProject(
            projectDir = projectDir,
            projectPath = projectPath,
            subprojectPaths = emptyList(),
            dependencies = extractDependenciesFromBuildFile(projectDir),
            build = build,
        )

    private fun scanProject(
        projectDir: File,
        projectPath: String,
        subprojectPaths: List<String>,
        dependencies: List<DependencyEntry>,
        build: String,
    ): ProjectScan {
        val sourceSets = ProjectScanner.discoverSourceSets(projectDir)
        val files = scanProjectFiles(projectDir, sourceSets)
        val summary = buildProjectSummary(projectDir, projectPath, subprojectPaths, dependencies, sourceSets, files)
        return ProjectScan(
            build = build,
            projectPath = ProjectPath(projectPath),
            files = files,
            summary = summary,
        )
    }

    private fun scanProjectFiles(
        projectDir: File,
        sourceSets: List<SourceSetName>,
    ): List<ProjectFileScan> {
        val sourceFiles =
            sourceSets.flatMap { sourceSet ->
                collectSourceFiles(ProjectScanner.sourceSetDirs(projectDir, sourceSet.value)).map { sourceSet to it }
            }
        if (sourceFiles.isEmpty()) return emptyList()

        val env = PsiEnvironment.shared()
        val scans =
            if (env == null) {
                sourceFiles.map { (sourceSet, file) ->
                    scanSourceFile(
                        projectDir = projectDir,
                        sourceSet = sourceSet,
                        file = file,
                        parser = null,
                    )
                }
            } else {
                synchronized(env) {
                    val parser = PsiParser(env)
                    sourceFiles.map { (sourceSet, file) ->
                        scanSourceFile(
                            projectDir = projectDir,
                            sourceSet = sourceSet,
                            file = file,
                            parser = parser,
                        )
                    }
                }
            }
        return scans.sortedWith(
            compareBy<ProjectFileScan> { sourceSets.indexOf(it.sourceSet) }
                .thenBy { it.projectRelativeFile },
        )
    }

    private fun scanSourceFile(
        projectDir: File,
        sourceSet: SourceSetName,
        file: File,
        parser: PsiParser?,
    ): ProjectFileScan {
        val sourceText = file.readText(charset = Charsets.UTF_8)
        val facts =
            parser?.let { activeParser ->
                extractFacts(
                    parser = activeParser,
                    file = file,
                )
            } ?: FileFacts(
                declarations = emptyList(),
                references = emptyList(),
            )
        return ProjectFileScan(
            sourceSet = sourceSet,
            projectRelativeFile =
                file
                    .relativeTo(base = projectDir)
                    .path
                    .replace(
                        oldChar = File.separatorChar,
                        newChar = '/',
                    ),
            declarations = facts.declarations.sortedWith(declarationFactComparator),
            references = facts.references.sortedWith(referenceFactComparator),
            sourceText = sourceText,
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
        sourceSets: List<SourceSetName>,
        files: List<ProjectFileScan>,
    ): ProjectSummary {
        val sourceSetSummaries =
            sourceSets.map { sourceSet ->
                val dirs = ProjectScanner.sourceSetDirs(projectDir, sourceSet.value)
                val symbols =
                    files
                        .filter { it.sourceSet == sourceSet }
                        .flatMap { scan ->
                            scan.declarations.map { symbol ->
                                val sourceDir = dirs.firstOrNull { symbol.file.startsWith(it) } ?: projectDir
                                symbol.toEntry(sourceDir)
                            }
                        }
                val dirNames = dirs.filter { it.exists() }.map { it.relativeTo(projectDir).path }
                SourceSetSummary(sourceSet, symbols, dirNames)
            }
        val allDirs = sourceSets.flatMap { ProjectScanner.sourceSetDirs(projectDir, it.value) }
        val projectAnalysis =
            runCatching { analyzeProject(allDirs, projectDir).toSummary() }
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
                    val group = parts[0].trim()
                    val artifact = parts[1].trim()
                    val version = parts[2].trim()
                    if (group.isBlank() || artifact.isBlank() || version.isBlank()) return@mapNotNull null
                    if (group.any { it.isWhitespace() }) return@mapNotNull null
                    DependencyEntry(
                        group = ArtifactGroup(group),
                        artifact = ArtifactName(artifact),
                        version = ArtifactVersion(version),
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
                val group = dep.group?.trim().orEmpty()
                val artifact = dep.name.trim()
                if (group.isBlank() || artifact.isBlank() || group.any { it.isWhitespace() }) return@forEach
                results.add(
                    DependencyEntry(
                        group = ArtifactGroup(group),
                        artifact = ArtifactName(artifact),
                        version =
                            ArtifactVersion(
                                dep.version
                                    ?.trim()
                                    .orEmpty()
                                    .ifBlank { "unspecified" },
                            ),
                        scope = config.name,
                    ),
                )
            }
        }
        return results.distinctBy { "${it.scope}:${it.group}:${it.artifact}" }
    }
}
