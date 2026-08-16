package zone.clanker.docx.web.atlas.projection

import zone.clanker.report.model.SourceFileSnapshot
import zone.clanker.report.model.SymbolKind
import zone.clanker.report.model.SymbolSnapshot

internal fun AtlasProjectionContext.searchMatches(
    file: SourceFileSnapshot,
    symbols: List<SymbolSnapshot>,
    request: AtlasProjectFrameRequest,
): Boolean {
    val query = request.query.trim().lowercase()
    if (query.isEmpty()) return true
    val targets = request.searchTargets.ifEmpty { ALL_SEARCH_TARGETS }
    val ownership = ownership(file)
    return targets.any { target ->
        when (target) {
            AtlasSearchTarget.BUILD ->
                fieldsMatch(
                    query,
                    ownership.build.id,
                    ownership.build.name,
                    ownership.build.relativePath,
                )

            AtlasSearchTarget.PROJECT ->
                fieldsMatch(
                    query,
                    ownership.project.id,
                    ownership.project.path,
                    ownership.project.buildFile,
                )

            AtlasSearchTarget.PACKAGE -> symbols.any { symbol -> fieldsMatch(query, symbol.packageName) }
            AtlasSearchTarget.FILE -> fieldsMatch(query, file.projectRelativePath)
            AtlasSearchTarget.CLASS -> symbols.filter(SymbolSnapshot::isClass).matchesSymbolFields(query)
            AtlasSearchTarget.SYMBOL -> symbols.matchesSymbolFields(query)
            AtlasSearchTarget.METHOD ->
                symbols
                    .filter { symbol -> symbol.kind == SymbolKind.FUNCTION }
                    .matchesSymbolFields(query)

            AtlasSearchTarget.EXTENSION -> file.extensionMatches(query)
        }
    }
}

private fun List<SymbolSnapshot>.matchesSymbolFields(query: String): Boolean =
    any { symbol -> fieldsMatch(query, symbol.name, symbol.qualifiedName) }

private fun SymbolSnapshot.isClass(): Boolean = kind == SymbolKind.CLASS || kind == SymbolKind.DATA_CLASS

private fun SourceFileSnapshot.extensionMatches(query: String): Boolean {
    val expected = query.removePrefix("*").removePrefix(".")
    val extension = projectRelativePath.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return expected.isNotEmpty() && extension == expected
}

private fun fieldsMatch(
    query: String,
    vararg values: String,
): Boolean = values.any { value -> value.lowercase().contains(query) }

private val ALL_SEARCH_TARGETS = AtlasSearchTarget.entries.toSet()
