package zone.clanker.gradle.srcx.model

import java.io.File

/**
 * The simple name of a source symbol (e.g. "MyService").
 *
 * @property value the symbol name, must not be blank
 */
@JvmInline
value class SymbolName(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "SymbolName must not be blank" }
    }

    override fun toString(): String = value
}

/**
 * A fully-qualified package name (e.g. "com.example.core").
 *
 * @property value the package name, must not be blank
 */
@JvmInline
value class PackageName(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "PackageName must not be blank" }
    }

    override fun toString(): String = value
}

/**
 * A relative file path to a source file (e.g. "com/example/MyService.kt").
 *
 * @property value the file path, must not be blank
 */
@JvmInline
value class FilePath(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "FilePath must not be blank" }
    }

    override fun toString(): String = value
}

/**
 * A single symbol extracted from source code.
 *
 * Represents a class, function, or property declaration found during
 * source scanning. Used by the report renderer to produce per-project
 * symbol tables.
 *
 * @property name the simple name of the symbol (e.g. "MyService")
 * @property kind the type of symbol: CLASS, FUNCTION, or PROPERTY
 * @property packageName the package declaration this symbol belongs to
 * @property filePath the relative path to the source file
 * @property lineNumber the line number where the symbol is declared (must be > 0)
 */
data class SymbolEntry(
    val name: SymbolName,
    val kind: SymbolKind,
    val packageName: PackageName,
    val filePath: FilePath,
    val lineNumber: Int,
) {
    init {
        require(lineNumber > 0) { "lineNumber must be > 0, was $lineNumber" }
    }
}

/**
 * The kind of source symbol extracted by the basic extractor.
 */
enum class SymbolKind {
    CLASS,
    FUNCTION,
    PROPERTY,
}

/**
 * A resolved symbol declaration from PSI analysis.
 *
 * Richer than [SymbolEntry]: includes qualified names, file references,
 * and fine-grained kind discrimination (data class, enum, interface, object).
 *
 * @property name the simple (or member-qualified) name
 * @property qualifiedName the fully qualified name including package
 * @property kind the fine-grained symbol kind
 * @property file the source file containing this symbol
 * @property line the 1-based line number of the declaration
 * @property packageName the package this symbol belongs to
 */
data class Symbol(
    val name: String,
    val qualifiedName: String,
    val kind: SymbolDetailKind,
    val file: File,
    val line: Int,
    val packageName: String,
)

/**
 * Fine-grained kind of a source symbol, distinguishing data classes,
 * enums, interfaces, objects, functions, and properties.
 *
 * @property label human-readable label for display
 */
enum class SymbolDetailKind(
    val label: String,
) {
    CLASS("class"),
    INTERFACE("interface"),
    ENUM("enum"),
    DATA_CLASS("data class"),
    OBJECT("object"),
    FUNCTION("fun"),
    PROPERTY("val/var"),
}

/**
 * A reference from one location to a named symbol.
 *
 * @property targetName the simple name of the referenced symbol
 * @property targetQualifiedName the fully qualified name, if known from imports
 * @property kind the type of reference (import, call, type-ref, etc.)
 * @property file the file containing this reference
 * @property line the 1-based line number
 * @property context a snippet of the source line for display
 */
data class Reference(
    val targetName: String,
    val targetQualifiedName: String?,
    val kind: ReferenceKind,
    val file: File,
    val line: Int,
    val context: String,
)

/**
 * The kind of reference between symbols.
 *
 * @property label human-readable label for display
 */
enum class ReferenceKind(
    val label: String,
) {
    IMPORT("import"),
    CALL("call"),
    NAME_REF("reference"),
    SUPERTYPE("extends/implements"),
    TYPE_REF("type"),
    CONSTRUCTOR("constructor"),
}

/**
 * A method-level call edge between two function symbols.
 *
 * @property caller the function making the call
 * @property target the function being called
 * @property file the file where the call occurs
 * @property line the line number of the call site
 */
data class MethodCall(
    val caller: Symbol,
    val target: Symbol,
    val file: File,
    val line: Int,
)

/**
 * A machine-checkable assertion declared on a task via `> verify:` syntax.
 *
 * @property type assertion type: symbol-exists, symbol-not-in, file-exists, etc.
 * @property argument the argument for the assertion (e.g., symbol name, file path)
 */
data class VerifyAssertion(
    val type: String,
    val argument: String,
)
