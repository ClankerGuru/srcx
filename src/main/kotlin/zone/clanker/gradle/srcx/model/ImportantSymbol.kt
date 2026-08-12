package zone.clanker.gradle.srcx.model

/** Explicit evidence that makes a workspace symbol important for generated context. */
enum class ImportantSymbolReason(
    val label: String,
) {
    CROSS_BUILD_INBOUND("Used from another build"),
    HIGH_WORKSPACE_INBOUND("High workspace inbound usage"),
    MULTIPLE_IMPLEMENTATIONS("Interface with multiple implementations"),
    ENTRY_POINT("Entry point"),
    DEPENDENCY_CYCLE("Dependency-cycle participant"),
    HIGH_WORKSPACE_OUTBOUND("High workspace outbound usage"),
    UNUSUAL_CONNECTIVITY("Unusually high workspace connectivity"),
    ANTI_PATTERN_INVOLVEMENT("Anti-pattern involvement"),
}

/** Exact, scoped analysis evidence supplied to important-symbol selection. */
data class ImportantSymbolSignals(
    val entryPoints: Set<WorkspaceSymbolIdentity> = emptySet(),
    val cycleParticipants: Set<WorkspaceSymbolIdentity> = emptySet(),
    val antiPatternSymbols: Set<WorkspaceSymbolIdentity> = emptySet(),
)

/** Report-safe selected declaration together with why it matters and its cumulative usage. */
data class ImportantSymbol(
    val symbol: WorkspaceSymbol,
    val reasons: List<ImportantSymbolReason>,
    val score: Int,
    val usage: WorkspaceSymbolUsage,
) {
    init {
        require(reasons.isNotEmpty()) { "reasons must not be empty" }
        require(reasons.distinct().size == reasons.size) { "reasons must not contain duplicates" }
        require(score > 0) { "score must be > 0" }
        require(usage.symbol.identity == symbol.identity) { "usage must describe the selected symbol" }
    }
}
