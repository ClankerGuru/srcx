package zone.clanker.report.model

import kotlinx.serialization.Serializable

/** Pins every evidence lookup to the immutable report generation that supplied the graph. */
@Serializable
data class WorkspaceEvidenceTarget(
    val workspaceId: String,
    val generationId: String,
) {
    init {
        requireValidId(workspaceId, "Workspace evidence workspace")
        requireValidId(generationId, "Workspace evidence generation")
    }
}

@Serializable
data class WorkspaceDeclarationEvidenceRequest(
    val target: WorkspaceEvidenceTarget,
    val symbolId: String,
) {
    init {
        requireValidId(symbolId, "Workspace declaration-evidence symbol")
    }
}

@Serializable
data class WorkspaceReverseUsageRequest(
    val target: WorkspaceEvidenceTarget,
    val symbolId: String,
    val after: WorkspaceEvidenceCursor? = null,
    val before: WorkspaceEvidenceCursor? = null,
    val limit: Int = WorkspaceEvidenceLimits.DEFAULT_PAGE_SIZE,
) {
    init {
        requireValidId(symbolId, "Workspace reverse-usage symbol")
        require(after == null || before == null) { "Workspace reverse usages accept only one cursor direction" }
        WorkspaceEvidenceLimits.requirePageSize(limit)
    }
}

@Serializable
data class WorkspaceRelationshipOccurrenceRequest(
    val target: WorkspaceEvidenceTarget,
    val selector: WorkspaceRelationshipOccurrenceSelector,
    val after: WorkspaceEvidenceCursor? = null,
    val before: WorkspaceEvidenceCursor? = null,
    val limit: Int = WorkspaceEvidenceLimits.DEFAULT_PAGE_SIZE,
) {
    init {
        require(after == null || before == null) {
            "Workspace relationship occurrences accept only one cursor direction"
        }
        WorkspaceEvidenceLimits.requirePageSize(limit)
    }
}

/** The exact aggregate edge key already exposed by a workspace graph relation. */
@Serializable
data class WorkspaceRelationshipOccurrenceSelector(
    val kind: RelationshipKind,
    val sourceNodeId: String,
    val targetNodeId: String,
) {
    init {
        requireValidId(sourceNodeId, "Workspace occurrence source node")
        requireValidId(targetNodeId, "Workspace occurrence target node")
    }
}

/** Stable keyset boundary; it avoids increasingly expensive SQL OFFSET scans. */
@Serializable
data class WorkspaceEvidenceCursor(
    val filePath: String,
    val line: Int,
    val relationshipId: String,
) {
    init {
        require(filePath.isNotBlank()) { "Workspace evidence cursor file path must not be blank" }
        require(line > 0) { "Workspace evidence cursor line must be positive" }
        requireValidId(relationshipId, "Workspace evidence cursor relationship")
    }
}

@Serializable
data class WorkspaceDeclarationEvidencePage(
    val target: WorkspaceEvidenceTarget,
    val symbolId: String,
    val declaration: WorkspaceDeclarationEvidence? = null,
) {
    init {
        requireValidId(symbolId, "Workspace declaration-evidence page symbol")
        require(declaration == null || declaration.symbolId == symbolId) {
            "Workspace declaration evidence must match its requested symbol"
        }
    }
}

@Serializable
data class WorkspaceDeclarationEvidence(
    val symbolId: String,
    val ownerSymbolId: String? = null,
    val signature: String? = null,
    val name: String,
    val qualifiedName: String,
    val kind: SymbolKind,
    val semantic: DeclarationSemantic,
    val location: WorkspaceEvidenceLocation,
) {
    init {
        requireValidId(symbolId, "Workspace declaration-evidence symbol")
        ownerSymbolId?.let { requireValidId(it, "Workspace declaration-evidence owner") }
        require(signature == null || signature.isNotBlank()) {
            "Workspace declaration-evidence signature must not be blank"
        }
        require(name.isNotBlank()) { "Workspace declaration-evidence name must not be blank" }
        require(qualifiedName.isNotBlank()) { "Workspace declaration-evidence qualified name must not be blank" }
    }
}

@Serializable
data class WorkspaceReverseUsagePage(
    val target: WorkspaceEvidenceTarget,
    val symbolId: String,
    val totalCount: Long,
    val occurrences: List<WorkspaceRelationshipOccurrence>,
    val previousCursor: WorkspaceEvidenceCursor? = null,
    val nextCursor: WorkspaceEvidenceCursor? = null,
) {
    init {
        requireValidId(symbolId, "Workspace reverse-usage page symbol")
        require(totalCount >= occurrences.size) { "Workspace reverse-usage total cannot omit returned occurrences" }
        requireBoundedOccurrences(occurrences)
        require(occurrences.all { occurrence -> occurrence.targetSymbolId == symbolId }) {
            "Workspace reverse usages must target their requested symbol"
        }
        requireNextCursor(occurrences, nextCursor)
        requirePreviousCursor(occurrences, previousCursor)
    }
}

@Serializable
data class WorkspaceRelationshipOccurrencePage(
    val target: WorkspaceEvidenceTarget,
    val selector: WorkspaceRelationshipOccurrenceSelector,
    val totalCount: Long,
    val occurrences: List<WorkspaceRelationshipOccurrence>,
    val previousCursor: WorkspaceEvidenceCursor? = null,
    val nextCursor: WorkspaceEvidenceCursor? = null,
) {
    init {
        require(totalCount >= occurrences.size) {
            "Workspace relationship-occurrence total cannot omit returned occurrences"
        }
        requireBoundedOccurrences(occurrences)
        require(occurrences.all { occurrence -> occurrence.kind == selector.kind }) {
            "Workspace relationship occurrences must match their requested kind"
        }
        requireNextCursor(occurrences, nextCursor)
        requirePreviousCursor(occurrences, previousCursor)
    }
}

@Serializable
data class WorkspaceRelationshipOccurrence(
    val relationshipId: String,
    val referenceId: String,
    val sourceSymbolId: String? = null,
    val targetSymbolId: String,
    val kind: RelationshipKind,
    val evidence: RelationshipEvidence,
    val category: WorkspaceUsageCategory,
    val location: WorkspaceEvidenceLocation,
    val context: String,
) {
    init {
        requireValidId(relationshipId, "Workspace evidence relationship")
        requireValidId(referenceId, "Workspace evidence reference")
        sourceSymbolId?.let { requireValidId(it, "Workspace evidence source symbol") }
        requireValidId(targetSymbolId, "Workspace evidence target symbol")
        require(category == kind.usageCategory()) {
            "Workspace evidence category must truthfully describe its relationship kind"
        }
    }

    val cursor: WorkspaceEvidenceCursor
        get() = WorkspaceEvidenceCursor(location.filePath, location.line, relationshipId)
}

@Serializable
data class WorkspaceEvidenceLocation(
    val buildId: String,
    val projectId: String,
    val sourceSetId: String,
    val sourceSetName: String,
    val fileId: String,
    val filePath: String,
    val line: Int,
    val range: SourceRangeSnapshot? = null,
) {
    init {
        requireValidId(buildId, "Workspace evidence build")
        requireValidId(projectId, "Workspace evidence project")
        requireValidId(sourceSetId, "Workspace evidence source set")
        require(sourceSetName.isNotBlank()) { "Workspace evidence source-set name must not be blank" }
        requireValidId(fileId, "Workspace evidence file")
        require(filePath.isNotBlank()) { "Workspace evidence file path must not be blank" }
        require(line > 0) { "Workspace evidence line must be positive" }
    }
}

/** Categories are deliberately limited to facts captured by the current Kotlin analyzer. */
@Serializable
enum class WorkspaceUsageCategory {
    CALLER,
    CONSTRUCTOR_INVOCATION,
    OVERRIDE_OR_IMPLEMENTATION,
    TYPE_REFERENCE,
    REFERENCE,
}

data object WorkspaceEvidenceLimits {
    const val DEFAULT_PAGE_SIZE: Int = 50
    const val MAX_PAGE_SIZE: Int = 100

    internal fun requirePageSize(limit: Int) {
        require(limit in 1..MAX_PAGE_SIZE) {
            "Workspace evidence page size must be between 1 and $MAX_PAGE_SIZE"
        }
    }
}

fun RelationshipKind.usageCategory(): WorkspaceUsageCategory =
    when (this) {
        RelationshipKind.CALL -> WorkspaceUsageCategory.CALLER
        RelationshipKind.CONSTRUCTOR -> WorkspaceUsageCategory.CONSTRUCTOR_INVOCATION
        RelationshipKind.EXTENDS,
        RelationshipKind.IMPLEMENTS,
        -> WorkspaceUsageCategory.OVERRIDE_OR_IMPLEMENTATION

        RelationshipKind.PROPERTY_TYPE,
        RelationshipKind.PARAMETER_TYPE,
        RelationshipKind.RETURN_TYPE,
        RelationshipKind.TYPE_REFERENCE,
        -> WorkspaceUsageCategory.TYPE_REFERENCE

        RelationshipKind.IMPORT,
        RelationshipKind.NAME_REFERENCE,
        -> WorkspaceUsageCategory.REFERENCE
    }

private fun requireBoundedOccurrences(occurrences: List<WorkspaceRelationshipOccurrence>) {
    require(occurrences.size <= WorkspaceEvidenceLimits.MAX_PAGE_SIZE) {
        "Workspace evidence pages must remain bounded"
    }
    require(occurrences == occurrences.sortedWith(workspaceEvidenceOccurrenceComparator())) {
        "Workspace evidence occurrences must use deterministic source order"
    }
    require(occurrences.map(WorkspaceRelationshipOccurrence::relationshipId).distinct().size == occurrences.size) {
        "Workspace evidence occurrence relationships must be unique"
    }
}

private fun requireNextCursor(
    occurrences: List<WorkspaceRelationshipOccurrence>,
    nextCursor: WorkspaceEvidenceCursor?,
) {
    require(nextCursor == null || occurrences.lastOrNull()?.cursor == nextCursor) {
        "Workspace evidence next cursor must identify the last returned occurrence"
    }
}

private fun requirePreviousCursor(
    occurrences: List<WorkspaceRelationshipOccurrence>,
    previousCursor: WorkspaceEvidenceCursor?,
) {
    require(previousCursor == null || occurrences.firstOrNull()?.cursor == previousCursor) {
        "Workspace evidence previous cursor must identify the first returned occurrence"
    }
}

fun workspaceEvidenceOccurrenceComparator(): Comparator<WorkspaceRelationshipOccurrence> =
    compareBy(
        { occurrence -> occurrence.location.filePath },
        { occurrence -> occurrence.location.line },
        WorkspaceRelationshipOccurrence::relationshipId,
    )

fun workspaceEvidenceCursorComparator(): Comparator<WorkspaceEvidenceCursor> =
    compareBy(
        WorkspaceEvidenceCursor::filePath,
        WorkspaceEvidenceCursor::line,
        WorkspaceEvidenceCursor::relationshipId,
    )
