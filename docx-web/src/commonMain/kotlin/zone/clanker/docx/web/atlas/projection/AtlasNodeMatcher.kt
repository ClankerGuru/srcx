package zone.clanker.docx.web.atlas.projection

import zone.clanker.report.model.SourceFileSnapshot
import zone.clanker.report.model.SymbolKind
import zone.clanker.report.model.SymbolSnapshot

internal fun AtlasProjectionContext.mixedNodeMatches(
    nodeId: String,
    request: AtlasProjectFrameRequest,
): Boolean =
    when {
        nodeId in filesById ->
            request.symbolKinds == ALL_SYMBOL_KINDS &&
                request.forMixedNode(MIXED_FILE_SEARCH_TARGETS)?.let {
                    sourceFileMatches(filesById.getValue(nodeId), it)
                } == true

        nodeId in symbolsById ->
            request.forMixedNode(MIXED_SYMBOL_SEARCH_TARGETS)?.let {
                symbolMatches(symbolsById.getValue(nodeId), it)
            } == true

        else -> false
    }

internal fun AtlasProjectionContext.sourceFileMatches(
    file: SourceFileSnapshot,
    request: AtlasProjectFrameRequest,
): Boolean =
    sourceSetMatches(file, request) &&
        fileDeclarationKindsMatch(file, request.symbolKinds) &&
        searchMatches(file, symbolsByFileId[file.id].orEmpty(), request)

internal fun AtlasProjectionContext.symbolMatches(
    symbol: SymbolSnapshot,
    request: AtlasProjectFrameRequest,
): Boolean {
    val file = filesById.getValue(symbol.fileId)
    return symbol.kind in request.symbolKinds &&
        sourceSetMatches(file, request) &&
        searchMatches(file, listOf(symbol), request)
}

private fun AtlasProjectionContext.sourceSetMatches(
    file: SourceFileSnapshot,
    request: AtlasProjectFrameRequest,
): Boolean = request.sourceSetIds.isEmpty() || file.sourceSetId in request.sourceSetIds

private fun AtlasProjectionContext.fileDeclarationKindsMatch(
    file: SourceFileSnapshot,
    symbolKinds: Set<SymbolKind>,
): Boolean =
    symbolKinds == ALL_SYMBOL_KINDS ||
        symbolsByFileId[file.id].orEmpty().any { symbol -> symbol.kind in symbolKinds }

private fun AtlasProjectFrameRequest.forMixedNode(
    supportedTargets: Set<AtlasSearchTarget>,
): AtlasProjectFrameRequest? {
    if (searchTargets.isEmpty()) return this
    val relevantTargets = searchTargets.intersect(supportedTargets)
    return relevantTargets.takeIf { targets -> targets.isNotEmpty() }?.let { copy(searchTargets = it) }
}

private val ALL_SYMBOL_KINDS = SymbolKind.entries.toSet()
private val MIXED_FILE_SEARCH_TARGETS =
    setOf(
        AtlasSearchTarget.BUILD,
        AtlasSearchTarget.PROJECT,
        AtlasSearchTarget.PACKAGE,
        AtlasSearchTarget.FILE,
        AtlasSearchTarget.EXTENSION,
    )
private val MIXED_SYMBOL_SEARCH_TARGETS =
    setOf(
        AtlasSearchTarget.BUILD,
        AtlasSearchTarget.PROJECT,
        AtlasSearchTarget.PACKAGE,
        AtlasSearchTarget.CLASS,
        AtlasSearchTarget.SYMBOL,
        AtlasSearchTarget.METHOD,
    )
