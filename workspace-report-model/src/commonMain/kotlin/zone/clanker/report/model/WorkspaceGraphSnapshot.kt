@file:Suppress("LongParameterList")

package zone.clanker.report.model

import kotlinx.serialization.Serializable

/** Exact UTF-16 source offsets for one captured declaration. */
@Serializable
data class SourceRangeSnapshot(
    val startOffset: Int,
    val endOffsetExclusive: Int,
) {
    init {
        require(startOffset >= 0) { "Source range start offset must not be negative" }
        require(endOffsetExclusive > startOffset) { "Source range end offset must follow its start" }
    }
}

@Serializable
data class SymbolSnapshot(
    val id: String,
    val fileId: String,
    val name: String,
    val qualifiedName: String,
    val packageName: String,
    val kind: SymbolKind,
    val declarationSemantic: DeclarationSemantic,
    val declarationLine: Int,
    val ownerSymbolId: String? = null,
    val signature: String? = null,
    val declarationRange: SourceRangeSnapshot? = null,
) {
    init {
        requireValidId(id, "Symbol")
        requireValidId(fileId, "Symbol file")
        require(name.isNotBlank()) { "Symbol name must not be blank" }
        require(qualifiedName.isNotBlank()) { "Qualified symbol name must not be blank" }
        require(packageName.isNotBlank()) { "Symbol package name must not be blank" }
        require(declarationLine > 0) { "Symbol declaration line must be positive" }
        ownerSymbolId?.let { requireValidId(it, "Symbol owner") }
        require(signature == null || signature.isNotBlank()) { "Symbol signature must not be blank" }
    }
}

@Serializable
enum class SymbolKind(
    val label: String,
) {
    CLASS("Class"),
    DATA_CLASS("Data class"),
    INTERFACE("Interface"),
    OBJECT("Object"),
    ENUM("Enum"),
    FUNCTION("Function"),
    PROPERTY("Property"),
}

@Serializable
enum class DeclarationSemantic(
    val label: String,
) {
    INTERFACE("Interface"),
    ABSTRACT_CLASS("Abstract or sealed class"),
    CONCRETE_CLASS("Concrete class"),
    SINGLETON_OBJECT("Kotlin object"),
    ENUM("Enum"),
    OTHER("Other declaration"),
}

@Serializable
data class ReferenceSnapshot(
    val id: String,
    val sourceFileId: String,
    val sourceSymbolId: String? = null,
    val line: Int,
    val context: String,
    val targetName: String,
    val targetQualifiedName: String? = null,
    val kind: ReferenceKind,
    val evidence: RelationshipEvidence,
    val occurrenceRange: SourceRangeSnapshot? = null,
) {
    init {
        requireValidId(id, "Reference")
        requireValidId(sourceFileId, "Reference source file")
        sourceSymbolId?.let { requireValidId(it, "Reference source symbol") }
        require(line > 0) { "Reference line must be positive" }
        require(targetName.isNotBlank()) { "Reference target name must not be blank" }
        require(targetQualifiedName == null || targetQualifiedName.isNotBlank()) {
            "Reference qualified target must not be blank"
        }
    }
}

@Serializable
enum class ReferenceKind(
    val label: String,
) {
    IMPORT("Import"),
    CALL("Call"),
    NAME_REFERENCE("Name reference"),
    SUPERTYPE("Supertype"),
    TYPE_REFERENCE("Type reference"),
    CONSTRUCTOR("Constructor"),
    PROPERTY_TYPE("Property type"),
    PARAMETER_TYPE("Parameter type"),
    RETURN_TYPE("Return type"),
}

@Serializable
data class RelationshipSnapshot(
    val id: String,
    val referenceId: String,
    val sourceSymbolId: String? = null,
    val targetSymbolId: String,
    val kind: RelationshipKind,
    val resolutionEvidence: RelationshipEvidence,
) {
    init {
        requireValidId(id, "Relationship")
        requireValidId(referenceId, "Relationship reference")
        sourceSymbolId?.let { requireValidId(it, "Relationship source symbol") }
        requireValidId(targetSymbolId, "Relationship target symbol")
    }
}

/** Closed observed route through resolved symbol relationships. */
@Serializable
data class CycleSnapshot(
    val id: String,
    val symbolIds: List<String>,
    val relationshipIds: List<String>,
) {
    init {
        requireValidId(id, "Relationship cycle")
        requireClosedRoute(symbolIds, "Relationship-cycle route")
        require(relationshipIds.size == symbolIds.size - 1) {
            "Relationship-cycle evidence must identify every directed route step"
        }
        require(relationshipIds.distinct().size == relationshipIds.size) {
            "Relationship-cycle evidence IDs must not repeat"
        }
    }
}

@Serializable
enum class RelationshipKind(
    val label: String,
) {
    IMPORT("Import"),
    EXTENDS("Extends"),
    IMPLEMENTS("Implements"),
    CALL("Call"),
    CONSTRUCTOR("Constructor"),
    NAME_REFERENCE("Name reference"),
    TYPE_REFERENCE("Type reference"),
    PROPERTY_TYPE("Property type"),
    PARAMETER_TYPE("Parameter type"),
    RETURN_TYPE("Return type"),
}

@Serializable
enum class RelationshipEvidence(
    val label: String,
) {
    DIRECT("Direct"),
    DERIVED("Derived"),
    HEURISTIC("Heuristic"),
}
