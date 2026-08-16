package zone.clanker.docx.web.atlas

import zone.clanker.docx.web.atlas.projection.AtlasRelationshipDirection
import zone.clanker.docx.web.atlas.projection.AtlasSearchTarget
import zone.clanker.docx.web.atlas.session.AtlasEvent
import zone.clanker.docx.web.atlas.session.AtlasFilters
import zone.clanker.docx.web.atlas.session.AtlasFiltersChanged
import zone.clanker.docx.web.atlas.session.AtlasSearchFilter
import zone.clanker.docx.web.atlas.session.AtlasSession
import zone.clanker.report.model.WorkspaceGraphFilter
import zone.clanker.docx.web.atlas.session.AtlasRelationshipDirection as SessionRelationshipDirection
import zone.clanker.docx.web.atlas.session.AtlasSearchTarget as SessionSearchTarget

internal interface AtlasGraphQueryFilters {
    val searchQuery: String
    val searchTargets: Set<AtlasSearchTarget>
    val relationshipCountMoreThan: Int?
    val relationshipDirection: AtlasRelationshipDirection

    fun updateSearch(value: String)

    fun selectAllSearchTargets()

    fun toggleSearchTarget(value: AtlasSearchTarget)

    fun updateRelationshipCountMoreThan(value: Int?)

    fun updateRelationshipDirection(value: AtlasRelationshipDirection)
}

internal class AtlasGraphQueryFilterController(
    private val session: () -> AtlasSession?,
    private val dispatch: (AtlasEvent) -> Unit,
) : AtlasGraphQueryFilters {
    override val searchQuery: String
        get() = currentFilters.search.query

    override val searchTargets: Set<AtlasSearchTarget>
        get() = currentFilters.search.targets.mapNotNullTo(mutableSetOf(), SessionSearchTarget::legacyTarget)

    override val relationshipCountMoreThan: Int?
        get() =
            currentFilters.relationships.minimumCountExclusive
                .takeIf { count -> count > 0 }
                ?.coerceAtMost(Int.MAX_VALUE.toLong())
                ?.toInt()

    override val relationshipDirection: AtlasRelationshipDirection
        get() = currentFilters.relationships.direction.legacyDirection

    override fun updateSearch(value: String) {
        updateFilters { filters ->
            filters.copy(search = filters.search.copy(query = value.take(WorkspaceGraphFilter.MAX_QUERY_LENGTH)))
        }
    }

    override fun selectAllSearchTargets() {
        updateFilters { filters -> filters.copy(search = AtlasSearchFilter(filters.search.query)) }
    }

    override fun toggleSearchTarget(value: AtlasSearchTarget) {
        val currentTargets = searchTargets
        val nextTargets =
            when {
                currentTargets.isEmpty() -> setOf(value)
                value in currentTargets -> currentTargets - value
                else -> currentTargets + value
            }
        updateFilters { filters ->
            filters.copy(
                search =
                    AtlasSearchFilter(
                        query = filters.search.query,
                        targets =
                            nextTargets
                                .map(AtlasSearchTarget::sessionTarget)
                                .sortedBy(SessionSearchTarget::ordinal),
                    ),
            )
        }
    }

    override fun updateRelationshipCountMoreThan(value: Int?) {
        updateFilters { filters ->
            filters.copy(
                relationships =
                    filters.relationships.copy(
                        minimumCountExclusive = value?.coerceAtLeast(0)?.toLong() ?: 0,
                    ),
            )
        }
    }

    override fun updateRelationshipDirection(value: AtlasRelationshipDirection) {
        updateFilters { filters ->
            filters.copy(relationships = filters.relationships.copy(direction = value.sessionDirection))
        }
    }

    private val currentFilters: AtlasFilters
        get() = session()?.filters ?: atlasGraphDefaultFilters()

    private fun updateFilters(transform: (AtlasFilters) -> AtlasFilters) {
        val current = session()?.filters ?: return
        val updated = transform(current)
        if (updated != current) dispatch(AtlasFiltersChanged(updated, recordHistory = false))
    }
}

private val AtlasSearchTarget.sessionTarget: SessionSearchTarget
    get() =
        when (this) {
            AtlasSearchTarget.BUILD -> SessionSearchTarget.BUILD
            AtlasSearchTarget.PROJECT -> SessionSearchTarget.PROJECT
            AtlasSearchTarget.PACKAGE -> SessionSearchTarget.PACKAGE
            AtlasSearchTarget.FILE -> SessionSearchTarget.FILE
            AtlasSearchTarget.CLASS -> SessionSearchTarget.CLASS
            AtlasSearchTarget.SYMBOL -> SessionSearchTarget.SYMBOL
            AtlasSearchTarget.METHOD -> SessionSearchTarget.METHOD
            AtlasSearchTarget.EXTENSION -> SessionSearchTarget.FILE_EXTENSION
        }

private val SessionSearchTarget.legacyTarget: AtlasSearchTarget?
    get() =
        when (this) {
            SessionSearchTarget.BUILD -> AtlasSearchTarget.BUILD
            SessionSearchTarget.PROJECT -> AtlasSearchTarget.PROJECT
            SessionSearchTarget.PACKAGE -> AtlasSearchTarget.PACKAGE
            SessionSearchTarget.FILE -> AtlasSearchTarget.FILE
            SessionSearchTarget.CLASS -> AtlasSearchTarget.CLASS
            SessionSearchTarget.METHOD, SessionSearchTarget.FUNCTION -> AtlasSearchTarget.METHOD
            SessionSearchTarget.FILE_EXTENSION -> AtlasSearchTarget.EXTENSION
            SessionSearchTarget.INTERFACE,
            SessionSearchTarget.OBJECT,
            SessionSearchTarget.ENUM,
            SessionSearchTarget.TYPE,
            SessionSearchTarget.PROPERTY,
            SessionSearchTarget.SYMBOL,
            -> AtlasSearchTarget.SYMBOL
            SessionSearchTarget.APPLICATION_GROUP,
            SessionSearchTarget.SOURCE_SET,
            SessionSearchTarget.TASK,
            SessionSearchTarget.DEPENDENCY,
            SessionSearchTarget.PROBLEM,
            SessionSearchTarget.CYCLE,
            -> null
        }

private val AtlasRelationshipDirection.sessionDirection: SessionRelationshipDirection
    get() =
        when (this) {
            AtlasRelationshipDirection.ANY -> SessionRelationshipDirection.ANY
            AtlasRelationshipDirection.INCOMING -> SessionRelationshipDirection.INCOMING
            AtlasRelationshipDirection.OUTGOING -> SessionRelationshipDirection.OUTGOING
        }

private val SessionRelationshipDirection.legacyDirection: AtlasRelationshipDirection
    get() =
        when (this) {
            SessionRelationshipDirection.ANY -> AtlasRelationshipDirection.ANY
            SessionRelationshipDirection.INCOMING -> AtlasRelationshipDirection.INCOMING
            SessionRelationshipDirection.OUTGOING -> AtlasRelationshipDirection.OUTGOING
        }
