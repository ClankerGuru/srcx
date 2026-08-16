package zone.clanker.docx.web.atlas

import zone.clanker.report.model.AtlasLens

internal enum class GraphLens(
    val label: String,
    val attributeValue: String,
) {
    FILES("Files", "files"),
    SYMBOLS("Symbols", "symbols"),
    PROBLEMS("Problems", "problems"),
    CYCLES("Cycles", "cycles"),
}

internal val GraphLens.atlasLens: AtlasLens
    get() =
        when (this) {
            GraphLens.FILES -> AtlasLens.FILES
            GraphLens.SYMBOLS -> AtlasLens.SYMBOLS
            GraphLens.PROBLEMS -> AtlasLens.PROBLEMS
            GraphLens.CYCLES -> AtlasLens.CYCLES
        }
