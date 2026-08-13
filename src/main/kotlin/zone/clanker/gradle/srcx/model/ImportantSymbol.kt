package zone.clanker.gradle.srcx.model

/**
 * Evidence used to choose and order declarations in the bounded relationship report.
 *
 * A reason is a deterministic ranking signal, not a quality grade, risk level, confidence, or recommendation. A
 * declaration becomes eligible when at least one reason applies. Scores for every applicable reason are added, then
 * declarations are ordered by total score before the report limit is applied. The weights express reporting priority:
 * cross-build use must survive the limit, architectural roles rank next, and broader connectivity breaks lower-priority
 * ties. Cross-build use is worth more than every other reason combined for that reason.
 *
 * Relationship thresholds count resolved, non-import records. They do not count unique callers and do not
 * represent runtime calls. Test source sets are excluded by the default selection policy unless a caller requests them.
 *
 * @property label short explanation shown in generated documentation
 * @property score additive ranking weight used only to order bounded report output
 * @property description the qualifying evidence and why it is useful in an architecture report
 */
enum class ImportantSymbolReason(
    val label: String,
    val score: Int,
    val description: String,
) {
    CROSS_BUILD_INBOUND(
        "Used from another build",
        1_000,
        "Preserves a build-boundary contract: at least one resolved, non-import incoming relationship originates " +
            "in a different build.",
    ),
    HIGH_WORKSPACE_INBOUND(
        "High workspace inbound relationships",
        180,
        "Surfaces a shared dependency hub: at least five resolved, non-import relationship records target the symbol " +
            "across the workspace.",
    ),
    MULTIPLE_IMPLEMENTATIONS(
        "Interface with multiple implementations",
        160,
        "Keeps a demonstrated polymorphic contract: the interface has at least two resolved implements " +
            "relationships.",
    ),
    ENTRY_POINT(
        "Entry point",
        140,
        "Keeps a useful starting point for reading the system: exact project analysis identifies the declaration as " +
            "an explicit entry point.",
    ),
    DEPENDENCY_CYCLE(
        "Dependency-cycle participant",
        120,
        "Retains the declarations needed to explain a cycle: exact project analysis identifies this declaration as " +
            "a dependency-cycle participant.",
    ),
    HIGH_WORKSPACE_OUTBOUND(
        "High workspace outbound relationships",
        100,
        "Surfaces a coordinator with broad dependencies: the symbol has at least five resolved, non-import outgoing " +
            "relationship records.",
    ),
    UNUSUAL_CONNECTIVITY(
        "Unusually high workspace connectivity",
        80,
        "Retains a highly connected declaration that may shape many paths: resolved, non-import inbound and outbound " +
            "relationship records total at least eight.",
    ),
    ANTI_PATTERN_INVOLVEMENT(
        "Anti-pattern involvement",
        60,
        "Connects an actionable finding to architecture evidence: an exact analysis signal associates the " +
            "declaration with an anti-pattern.",
    ),
}

/** Exact, scoped analysis evidence supplied to important-symbol selection. */
data class ImportantSymbolSignals(
    val entryPoints: Set<WorkspaceSymbolIdentity> = emptySet(),
    val cycleParticipants: Set<WorkspaceSymbolIdentity> = emptySet(),
    val antiPatternSymbols: Set<WorkspaceSymbolIdentity> = emptySet(),
)

/** Report-safe selected declaration together with why it matters and its cumulative relationships. */
data class ImportantSymbol(
    val symbol: WorkspaceSymbol,
    val reasons: List<ImportantSymbolReason>,
    val score: Int,
    val usage: WorkspaceSymbolUsage,
) {
    init {
        require(reasons.isNotEmpty()) { "reasons must not be empty" }
        require(reasons.distinct().size == reasons.size) { "reasons must not contain duplicates" }
        require(score == reasons.sumOf { it.score }) { "score must equal the sum of reason scores" }
        require(usage.symbol.identity == symbol.identity) { "usage must describe the selected symbol" }
    }
}
