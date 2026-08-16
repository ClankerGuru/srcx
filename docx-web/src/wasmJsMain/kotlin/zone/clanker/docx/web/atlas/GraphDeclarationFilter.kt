package zone.clanker.docx.web.atlas

import zone.clanker.report.model.SymbolKind

internal enum class GraphDeclarationFilter(
    val label: String,
    val symbolKinds: Set<SymbolKind>,
) {
    CLASSES("Classes", setOf(SymbolKind.CLASS, SymbolKind.DATA_CLASS)),
    INTERFACES("Interfaces", setOf(SymbolKind.INTERFACE)),
    OBJECTS("Objects", setOf(SymbolKind.OBJECT)),
    ENUMS("Enums", setOf(SymbolKind.ENUM)),
    FUNCTIONS("Functions & methods", setOf(SymbolKind.FUNCTION)),
    PROPERTIES("Properties", setOf(SymbolKind.PROPERTY)),
}
