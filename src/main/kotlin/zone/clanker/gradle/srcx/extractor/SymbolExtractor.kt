package zone.clanker.gradle.srcx.extractor

import zone.clanker.gradle.srcx.model.SymbolEntry
import zone.clanker.gradle.srcx.model.SymbolKind
import java.io.File

/**
 * Extracts symbol declarations from Kotlin and Java source files.
 *
 * Scans `src/main/kotlin/` and `src/main/java/` directories using
 * line-by-line pattern matching to find class, function, and property
 * declarations. No full AST parsing is needed -- simple regex patterns
 * are sufficient for symbol extraction.
 *
 * ```kotlin
 * val extractor = SymbolExtractor(projectDir)
 * val symbols = extractor.extract()
 * ```
 *
 * @property projectDir the root directory of the Gradle project to scan
 */
internal class SymbolExtractor(
    private val projectDir: File,
) {
    private val classPattern =
        Regex(
            """^\s*(?:public\s+|private\s+|internal\s+|protected\s+|abstract\s+|open\s+|sealed\s+|data\s+)*""" +
                """(?:class|interface|object|enum\s+class)\s+(\w+)""",
        )
    private val functionPattern =
        Regex(
            """^\s*(?:public\s+|private\s+|internal\s+|protected\s+|override\s+|open\s+|abstract\s+|suspend\s+)*""" +
                """fun\s+(?:<[^>]+>\s+)?(\w+)""",
        )
    private val propertyPattern =
        Regex(
            """^\s*(?:public\s+|private\s+|internal\s+|protected\s+|override\s+|open\s+|abstract\s+|const\s+)*""" +
                """(?:val|var)\s+(\w+)""",
        )
    private val packagePattern = Regex("""^\s*package\s+([\w.]+)""")

    /**
     * Extract all symbols from the project's source directories.
     *
     * Scans both Kotlin and Java source trees under `src/main/`.
     *
     * @return a list of all extracted symbol entries
     */
    fun extract(): List<SymbolEntry> {
        val results = mutableListOf<SymbolEntry>()
        for (sourceDir in sourceDirs()) {
            if (sourceDir.exists()) {
                sourceDir
                    .walkTopDown()
                    .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
                    .forEach { file -> results.addAll(extractFromFile(file, sourceDir)) }
            }
        }
        return results
    }

    /**
     * Return the list of source directories that exist for this project.
     *
     * @return the list of existing source directory names relative to projectDir
     */
    fun sourceDirNames(): List<String> =
        sourceDirs().filter { it.exists() }.map { it.relativeTo(projectDir).path }

    private fun sourceDirs(): List<File> =
        listOf(
            File(projectDir, "src/main/kotlin"),
            File(projectDir, "src/main/java"),
        )

    private fun extractFromFile(
        file: File,
        sourceDir: File,
    ): List<SymbolEntry> {
        val relativePath = file.relativeTo(sourceDir).path
        val lines = file.readLines()
        var currentPackage = ""
        val entries = mutableListOf<SymbolEntry>()

        lines.forEachIndexed { index, line ->
            packagePattern.find(line)?.let { match ->
                currentPackage = match.groupValues[1]
            }
            classPattern.find(line)?.let { match ->
                entries
                    .add(SymbolEntry(match.groupValues[1], SymbolKind.CLASS, currentPackage, relativePath, index + 1))
            }
            functionPattern.find(line)?.let { match ->
                entries.add(
                    SymbolEntry(match.groupValues[1], SymbolKind.FUNCTION, currentPackage, relativePath, index + 1),
                )
            }
            propertyPattern.find(line)?.let { match ->
                if (!line.trimStart().startsWith("//") && !isInsideFunctionBody(lines, index)) {
                    entries.add(
                        SymbolEntry(match.groupValues[1], SymbolKind.PROPERTY, currentPackage, relativePath, index + 1),
                    )
                }
            }
        }
        return entries
    }

    private fun isInsideFunctionBody(
        lines: List<String>,
        currentIndex: Int,
    ): Boolean {
        var braceDepth = 0
        for (i in 0 until currentIndex) {
            val line = lines[i]
            if (functionPattern.containsMatchIn(line)) braceDepth++
            braceDepth += line.count { it == '{' }
            braceDepth -= line.count { it == '}' }
        }
        return braceDepth > 1
    }
}
