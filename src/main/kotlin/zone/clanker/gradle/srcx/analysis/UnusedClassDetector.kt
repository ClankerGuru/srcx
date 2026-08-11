package zone.clanker.gradle.srcx.analysis

import zone.clanker.gradle.srcx.model.Symbol
import zone.clanker.gradle.srcx.model.SymbolDetailKind
import zone.clanker.gradle.srcx.model.UnusedClass
import zone.clanker.gradle.srcx.parse.SymbolIndex
import java.io.File

/** Finds production class-like declarations without resolvable inbound references. */
internal object UnusedClassDetector {
    data class ProjectScope(
        val buildName: String,
        val projectPath: String,
        val directory: File,
    )

    private val classKinds =
        setOf(
            SymbolDetailKind.CLASS,
            SymbolDetailKind.INTERFACE,
            SymbolDetailKind.ENUM,
            SymbolDetailKind.DATA_CLASS,
            SymbolDetailKind.OBJECT,
        )

    fun detect(
        sourceFiles: List<File>,
        workspaceRoot: File,
        scopes: List<ProjectScope>,
    ): List<UnusedClass> {
        val sources = sourceFiles.filter { it.extension == "kt" || it.extension == "java" }
        val index = SymbolIndex.build(sources)
        val usedQualifiedNames = index.usedQualifiedNames()
        return index.symbols
            .asSequence()
            .filter { it.kind in classKinds }
            .mapNotNull { symbol -> candidate(symbol, index, usedQualifiedNames, workspaceRoot, scopes) }
            .sortedWith(compareBy(UnusedClass::buildName, UnusedClass::projectPath, UnusedClass::qualifiedName))
            .toList()
    }

    @Suppress("ReturnCount")
    private fun candidate(
        symbol: Symbol,
        index: SymbolIndex,
        usedQualifiedNames: Set<String>,
        workspaceRoot: File,
        scopes: List<ProjectScope>,
    ): UnusedClass? {
        val scope = scopeFor(symbol.file, scopes) ?: return null
        val sourceSet = sourceSet(symbol.file, scope.directory) ?: return null
        if (sourceSet.contains("test", ignoreCase = true)) return null
        if (symbol.qualifiedName in usedQualifiedNames) return null
        if (isRecognizedEntryPoint(symbol, index)) return null

        return UnusedClass(
            name = symbol.name,
            qualifiedName = symbol.qualifiedName,
            kind = symbol.kind,
            buildName = scope.buildName,
            projectPath = scope.projectPath,
            sourceSet = sourceSet,
            filePath =
                symbol.file.relativeToOrNull(workspaceRoot)?.invariantSeparatorsPath
                    ?: symbol.file.absolutePath,
            line = symbol.line,
        )
    }

    private fun scopeFor(file: File, scopes: List<ProjectScope>): ProjectScope? {
        val canonicalFile = file.canonicalFile.toPath()
        return scopes
            .filter { canonicalFile.startsWith(it.directory.canonicalFile.toPath()) }
            .maxByOrNull { it.directory.canonicalPath.length }
    }

    private fun sourceSet(file: File, projectDirectory: File): String? {
        val relative = file.relativeTo(projectDirectory).invariantSeparatorsPath
        val parts = relative.split('/')
        val srcIndex = parts.indexOf("src")
        return parts.getOrNull(srcIndex + 1)
    }

    private fun isRecognizedEntryPoint(symbol: Symbol, index: SymbolIndex): Boolean {
        val memberPrefix = "${symbol.qualifiedName}."
        val hasMain =
            index.symbols.any {
                it.file == symbol.file && it.qualifiedName == "${memberPrefix}main"
            }
        val text = symbol.file.readText()
        return hasMain ||
            Regex("""\bfun\s+main\s*\(""").containsMatchIn(text) ||
            Regex("""\bstatic\s+void\s+main\s*\(""").containsMatchIn(text) ||
            entryPointMarkers.any(text::contains)
    }

    private val entryPointMarkers =
        setOf(
            "@SpringBootApplication",
            "@Configuration",
            "@Component",
            "@Service",
            "@Repository",
            "@Controller",
            "@RestController",
            "@Entity",
            "Plugin<",
        )
}
