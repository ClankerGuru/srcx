package zone.clanker.docx.web.fixture

internal data class DocxScaleDimensions(
    val profileName: String = "test",
    val workspaceName: String = "DOCX Test Scale Workspace",
    val buildCount: Int,
    val projectsPerBuild: Int,
    val filesPerProject: Int,
    val symbolsPerFile: Int,
    val linesPerFile: Int,
    val logicalLinesPerFile: Int = linesPerFile,
    val projectFileOverrides: Map<Int, Int> = emptyMap(),
    val buildNames: List<String> = emptyList(),
    val includeProjectDependencies: Boolean = false,
) {
    val projectCount: Int = buildCount * projectsPerBuild
    val projectFileCounts: List<Int> =
        (0 until projectCount).map { projectIndex -> projectFileOverrides[projectIndex] ?: filesPerProject }
    val fileCount: Int = projectFileCounts.sum()
    val symbolCount: Int = fileCount * symbolsPerFile
    val projectDependencyCount: Int = if (includeProjectDependencies) projectCount - 1 else 0
    val relationshipCount: Int = symbolCount + projectDependencyCount
    val logicalLineCount: Long = fileCount.toLong() * logicalLinesPerFile
    val materializedLineCount: Long = fileCount.toLong() * linesPerFile
    val linesPerSymbol: Int = linesPerFile / symbolsPerFile

    fun filesInProject(
        buildIndex: Int,
        projectIndex: Int,
    ): Int = projectFileCounts[buildIndex * projectsPerBuild + projectIndex]

    fun symbolsInProject(
        buildIndex: Int,
        projectIndex: Int,
    ): Int = filesInProject(buildIndex, projectIndex) * symbolsPerFile

    fun mainFilesInProject(
        buildIndex: Int,
        projectIndex: Int,
    ): Int {
        val count = filesInProject(buildIndex, projectIndex)
        return (count * MAIN_SOURCE_PERCENT / PERCENT).coerceIn(1, count - 1)
    }

    fun buildName(index: Int): String =
        buildNames.getOrNull(index) ?: if (index == 0) "scale-root" else "scale-${padded(index, BUILD_WIDTH)}"

    fun buildId(index: Int): String = "build:scale:$profileName:${padded(index, BUILD_WIDTH)}"

    init {
        require(buildCount > 0 && projectsPerBuild > 0) { "Scale build and project counts must be positive" }
        require(filesPerProject >= 2 && symbolsPerFile > 0) { "Scale file and symbol counts must be positive" }
        require(linesPerFile % symbolsPerFile == 0) { "Scale lines must divide evenly across symbols" }
        require(logicalLinesPerFile >= linesPerFile) {
            "Scale logical lines must include every materialized source line"
        }
        require(linesPerSymbol >= MINIMUM_SYMBOL_LINES) { "Scale symbols require at least $MINIMUM_SYMBOL_LINES lines" }
        require(profileName.isNotBlank() && workspaceName.isNotBlank()) { "Scale profile identity must not be blank" }
        require(projectFileOverrides.keys.all { it in 0 until projectCount }) {
            "Scale project override is out of range"
        }
        require(projectFileCounts.all { it >= 2 }) { "Every scale project needs at least two files" }
        require(buildNames.isEmpty() || buildNames.size == buildCount) { "Scale build names must cover every build" }
        require(buildNames.all(String::isNotBlank)) { "Scale build names must not be blank" }
    }

    private companion object {
        const val MINIMUM_SYMBOL_LINES = 4
        const val MAIN_SOURCE_PERCENT = 80
        const val PERCENT = 100
    }
}
