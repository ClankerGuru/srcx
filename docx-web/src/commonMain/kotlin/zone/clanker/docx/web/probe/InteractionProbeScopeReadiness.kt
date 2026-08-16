package zone.clanker.docx.web.probe

internal data class InteractionProbeScopeReadiness(
    val kind: Kind,
    val atlasOverviewAvailable: Boolean,
    val canonicalGraphSettled: Boolean,
) {
    fun isSettled(): Boolean =
        when (kind) {
            Kind.OVERVIEW -> atlasOverviewAvailable
            Kind.BOUNDED_GRAPH -> canonicalGraphSettled
        }

    enum class Kind {
        OVERVIEW,
        BOUNDED_GRAPH,
    }
}
