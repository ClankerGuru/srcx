package zone.clanker.gradle.srcx.model

/**
 * A single symbol extracted from source code.
 *
 * Represents a class, function, or property declaration found during
 * source scanning. Used by the report renderer to produce per-project
 * symbol tables.
 *
 * @property name the simple name of the symbol (e.g. "MyService")
 * @property kind the type of symbol: CLASS, FUNCTION, or PROPERTY
 * @property pkg the package declaration this symbol belongs to
 * @property file the relative path to the source file
 * @property line the line number where the symbol is declared
 */
data class SymbolEntry(
    val name: String,
    val kind: SymbolKind,
    val pkg: String,
    val file: String,
    val line: Int,
)

/**
 * The kind of source symbol extracted.
 */
enum class SymbolKind {
    CLASS,
    FUNCTION,
    PROPERTY,
}
