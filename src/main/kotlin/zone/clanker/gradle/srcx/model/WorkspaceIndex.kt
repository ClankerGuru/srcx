package zone.clanker.gradle.srcx.model

/** How strongly source facts support a workspace reference or relationship. */
enum class ReferenceEvidence(
    val label: String,
) {
    DIRECT("direct"),
    DERIVED("derived"),
    HEURISTIC("heuristic"),
}

/** Stable identity for a declaration within its workspace scope. */
data class WorkspaceSymbolIdentity(
    val build: String,
    val project: String,
    val sourceSet: String,
    val qualifiedName: String,
    val projectRelativeFile: String,
    val declarationLine: Int,
) {
    val value: String
        get() = "$build::$project::$sourceSet::$qualifiedName@$projectRelativeFile:$declarationLine"
}

/** Report-safe declaration with full workspace ownership and source provenance. */
@Suppress("LongParameterList")
data class WorkspaceSymbol(
    val build: String,
    val project: String,
    val sourceSet: String,
    val name: String,
    val qualifiedName: String,
    val kind: SymbolDetailKind,
    val projectRelativeFile: String,
    val declarationLine: Int,
) {
    val identity: WorkspaceSymbolIdentity
        get() =
            WorkspaceSymbolIdentity(
                build,
                project,
                sourceSet,
                qualifiedName,
                projectRelativeFile,
                declarationLine,
            )
}

/** Report-safe reference fact, including its owning project and source declaration when known. */
@Suppress("LongParameterList")
data class WorkspaceReference(
    val build: String,
    val project: String,
    val sourceSet: String,
    val sourceSymbol: WorkspaceSymbol?,
    val targetName: String,
    val targetQualifiedName: String?,
    val kind: ReferenceKind,
    val projectRelativeFile: String,
    val line: Int,
    val context: String,
    val evidence: ReferenceEvidence,
) {
    val sourceIdentity: WorkspaceSymbolIdentity?
        get() = sourceSymbol?.identity
}

/** Explicit semantic kind for a resolved workspace relationship. */
enum class WorkspaceRelationshipKind(
    val label: String,
) {
    IMPORT("imports"),
    CALL("calls"),
    CONSTRUCTOR("constructs"),
    NAME_REFERENCE("references"),
    TYPE_REFERENCE("uses type"),
    PROPERTY_TYPE("property type"),
    PARAMETER_TYPE("parameter type"),
    RETURN_TYPE("return type"),
    EXTENDS("extends"),
    IMPLEMENTS("implements"),
}

/** A reference resolved to a declaration without losing its original source evidence. */
data class WorkspaceRelationship(
    val source: WorkspaceSymbol?,
    val target: WorkspaceSymbol,
    val kind: WorkspaceRelationshipKind,
    val sourceEvidence: WorkspaceReference,
    val evidence: ReferenceEvidence,
) {
    val sourceIdentity: WorkspaceSymbolIdentity?
        get() = source?.identity

    val targetIdentity: WorkspaceSymbolIdentity
        get() = target.identity
}

/** Cumulative usage of one symbol across local projects and included builds. */
data class WorkspaceSymbolUsage(
    val symbol: WorkspaceSymbol,
    val incoming: List<WorkspaceRelationship>,
    val outgoing: List<WorkspaceRelationship>,
) {
    val localInbound: Int
        get() =
            incoming.count { relationship ->
                relationship.kind != WorkspaceRelationshipKind.IMPORT &&
                    relationship.sourceBuild == symbol.build &&
                    relationship.sourceProject == symbol.project
            }

    val workspaceInbound: Int
        get() = incoming.count { it.kind != WorkspaceRelationshipKind.IMPORT }

    val crossBuildInbound: Int
        get() =
            incoming.count { relationship ->
                relationship.kind != WorkspaceRelationshipKind.IMPORT && relationship.sourceBuild != symbol.build
            }

    val isWorkspaceUsed: Boolean
        get() = workspaceInbound > 0

    private val WorkspaceRelationship.sourceBuild: String
        get() = source?.build ?: sourceEvidence.build

    private val WorkspaceRelationship.sourceProject: String
        get() = source?.project ?: sourceEvidence.project
}

/** Deterministic cumulative declaration, reference, relationship, and usage index. */
data class WorkspaceIndex(
    val symbols: List<WorkspaceSymbol> = emptyList(),
    val references: List<WorkspaceReference> = emptyList(),
    val relationships: List<WorkspaceRelationship> = emptyList(),
    val usages: List<WorkspaceSymbolUsage> = emptyList(),
)
