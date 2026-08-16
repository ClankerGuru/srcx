package zone.clanker.docx.web.fixture

import zone.clanker.report.model.DeclarationSemantic
import zone.clanker.report.model.SourceFileSnapshot
import zone.clanker.report.model.SourceLanguage
import zone.clanker.report.model.SourceSetSnapshot
import zone.clanker.report.model.SymbolKind
import zone.clanker.report.model.SymbolSnapshot

internal class DocxScaleSourceFactory(
    private val project: DocxScaleProject,
) {
    private val dimensions = project.dimensions

    fun sourceSet(name: String): SourceSetSnapshot =
        SourceSetSnapshot(
            id = project.sourceSetId(name),
            projectId = project.id,
            name = name,
            sourceDirectories = listOf("src/$name/kotlin"),
        )

    fun sourceFile(fileIndex: Int): SourceFileSnapshot {
        val sourceSetName = sourceSetName(fileIndex)
        return SourceFileSnapshot(
            id = project.fileId(fileIndex),
            sourceSetId = project.sourceSetId(sourceSetName),
            projectRelativePath =
                "src/$sourceSetName/kotlin/${project.packageName.replace('.', '/')}/" +
                    "Node${padded(fileIndex, FILE_WIDTH)}.kt",
            language = SourceLanguage.KOTLIN,
            content = sourceContent(fileIndex),
        )
    }

    fun symbol(symbolIndex: Int): SymbolSnapshot =
        SymbolSnapshot(
            id = project.symbolId(symbolIndex),
            fileId = project.fileId(symbolIndex / dimensions.symbolsPerFile),
            name = project.symbolName(symbolIndex),
            qualifiedName = "${project.packageName}.${project.symbolName(symbolIndex)}",
            packageName = project.packageName,
            kind = SymbolKind.CLASS,
            declarationSemantic = DeclarationSemantic.CONCRETE_CLASS,
            declarationLine = symbolLine(symbolIndex),
        )

    fun symbolLine(symbolIndex: Int): Int =
        (symbolIndex % dimensions.symbolsPerFile) * dimensions.linesPerSymbol + 1

    fun sourceSetName(fileIndex: Int): String =
        if (fileIndex < dimensions.mainFilesInProject(project.buildIndex, project.projectIndex)) "main" else "test"

    private fun sourceContent(fileIndex: Int): String =
        buildString {
            val firstSymbol = fileIndex * dimensions.symbolsPerFile
            repeat(dimensions.symbolsPerFile) { offset -> appendSymbol(firstSymbol + offset) }
        }

    private fun StringBuilder.appendSymbol(symbolIndex: Int) {
        val targetIndex = (symbolIndex + 1) % project.symbolCount
        appendLine("class ${project.symbolName(symbolIndex)}(")
        appendLine("    dependency: ${project.symbolName(targetIndex)},")
        appendLine(") {")
        repeat(dimensions.linesPerSymbol - SYMBOL_STRUCTURE_LINE_COUNT) { line ->
            appendLine("    // deterministic scale line ${padded(line + SYMBOL_STRUCTURE_LINE_COUNT, LINE_WIDTH)}")
        }
        appendLine("}")
    }
}
