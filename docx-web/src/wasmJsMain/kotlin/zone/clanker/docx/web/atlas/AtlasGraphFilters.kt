package zone.clanker.docx.web.atlas

import zone.clanker.docx.web.atlas.session.AtlasDeclarationKind
import zone.clanker.docx.web.atlas.session.AtlasEvent
import zone.clanker.docx.web.atlas.session.AtlasFilters
import zone.clanker.docx.web.atlas.session.AtlasFiltersChanged
import zone.clanker.docx.web.atlas.session.AtlasHierarchyLevel
import zone.clanker.docx.web.atlas.session.AtlasLayer
import zone.clanker.docx.web.atlas.session.AtlasLayersChanged
import zone.clanker.docx.web.atlas.session.AtlasRelationshipFilter
import zone.clanker.docx.web.atlas.session.AtlasScopeSelectionReplaced
import zone.clanker.docx.web.atlas.session.AtlasSemanticId
import zone.clanker.docx.web.atlas.session.AtlasSession
import zone.clanker.report.model.RelationshipKind
import zone.clanker.report.model.SymbolKind
import zone.clanker.report.model.WorkspaceGraphRelationKind

internal interface AtlasGraphFilters : AtlasGraphQueryFilters {
    val lens: GraphLens
    val relationshipFilters: Set<GraphRelationshipFilter>
    val declarationFilters: Set<GraphDeclarationFilter>
    val sourceSetIds: Set<String>
    val allowedRelationshipKinds: Set<RelationshipKind>
    val allowedSymbolKinds: Set<SymbolKind>
    val allRelationshipFiltersSelected: Boolean
    val allDeclarationFiltersSelected: Boolean

    fun updateLens(value: GraphLens)

    fun updateRelationshipFilter(value: GraphRelationshipFilter)

    fun toggleRelationshipFilter(value: GraphRelationshipFilter)

    fun selectAllRelationshipFilters()

    fun clearRelationshipFilters()

    fun updateSourceSet(value: String?)

    fun updateSourceSets(value: Set<String>)

    fun showAllDeclarations()

    fun toggleDeclarationFilter(value: GraphDeclarationFilter)
}

internal class AtlasGraphFilterController private constructor(
    private val session: () -> AtlasSession?,
    private val dispatch: (AtlasEvent) -> Unit,
    private val queryFilters: AtlasGraphQueryFilterController,
) : AtlasGraphFilters,
    AtlasGraphQueryFilters by queryFilters {
    constructor(
        session: () -> AtlasSession?,
        dispatch: (AtlasEvent) -> Unit,
    ) : this(session, dispatch, AtlasGraphQueryFilterController(session, dispatch))

    override val lens: GraphLens
        get() =
            GraphLens.entries.lastOrNull { candidate ->
                candidate.sessionLayer in session()?.layers?.enabled.orEmpty()
            } ?: GraphLens.FILES

    override val relationshipFilters: Set<GraphRelationshipFilter>
        get() {
            val selectedKinds = currentFilters.relationships.kinds.toSet()
            return selectableRelationshipFilters.filterTo(mutableSetOf()) { filter ->
                filter.allowedKinds.any { kind -> kind.workspaceKind in selectedKinds }
            }
        }

    override val declarationFilters: Set<GraphDeclarationFilter>
        get() {
            val selectedKinds = currentFilters.declarations.toSet()
            return GraphDeclarationFilter.entries.filterTo(mutableSetOf()) { filter ->
                filter.sessionKinds.any(selectedKinds::contains)
            }
        }

    override val sourceSetIds: Set<String>
        get() =
            session()
                ?.scope
                ?.selectedIds(AtlasHierarchyLevel.SOURCE_SET)
                .orEmpty()
                .mapTo(mutableSetOf(), AtlasSemanticId::value)

    override val allowedRelationshipKinds: Set<RelationshipKind>
        get() = relationshipFilters.flatMapTo(mutableSetOf(), GraphRelationshipFilter::allowedKinds)

    override val allowedSymbolKinds: Set<SymbolKind>
        get() = declarationFilters.flatMapTo(mutableSetOf(), GraphDeclarationFilter::symbolKinds)

    override val allRelationshipFiltersSelected: Boolean
        get() = relationshipFilters == selectableRelationshipFilters

    override val allDeclarationFiltersSelected: Boolean
        get() = declarationFilters.size == GraphDeclarationFilter.entries.size

    override fun updateLens(value: GraphLens) {
        val current = session() ?: return
        val enabled =
            (current.layers.enabled - GraphLens.entries.map(GraphLens::sessionLayer) + value.sessionLayer)
                .distinct()
                .sortedBy(AtlasLayer::ordinal)
        if (enabled != current.layers.enabled) dispatch(AtlasLayersChanged(enabled))
    }

    override fun updateRelationshipFilter(value: GraphRelationshipFilter) {
        updateRelationshipFilters(
            if (value == GraphRelationshipFilter.ALL) selectableRelationshipFilters else setOf(value),
        )
    }

    override fun toggleRelationshipFilter(value: GraphRelationshipFilter) {
        if (value == GraphRelationshipFilter.ALL) {
            selectAllRelationshipFilters()
            return
        }
        val next =
            when {
                allRelationshipFiltersSelected -> setOf(value)
                value in relationshipFilters -> relationshipFilters - value
                else -> relationshipFilters + value
            }
        updateRelationshipFilters(next)
    }

    override fun selectAllRelationshipFilters() {
        updateRelationshipFilters(selectableRelationshipFilters)
    }

    override fun clearRelationshipFilters() {
        updateRelationshipFilters(emptySet())
    }

    override fun updateSourceSet(value: String?) {
        updateSourceSets(setOfNotNull(value))
    }

    override fun updateSourceSets(value: Set<String>) {
        val selectedIds = value.sorted().map(::AtlasSemanticId)
        if (selectedIds != session()?.scope?.selectedIds(AtlasHierarchyLevel.SOURCE_SET)) {
            dispatch(AtlasScopeSelectionReplaced(AtlasHierarchyLevel.SOURCE_SET, selectedIds))
        }
    }

    override fun showAllDeclarations() {
        updateDeclarationFilters(GraphDeclarationFilter.entries.toSet())
    }

    override fun toggleDeclarationFilter(value: GraphDeclarationFilter) {
        val nextFilters =
            when {
                allDeclarationFiltersSelected -> setOf(value)
                value in declarationFilters -> declarationFilters - value
                else -> declarationFilters + value
            }
        updateDeclarationFilters(nextFilters)
    }

    fun focusScope(nextLens: GraphLens?) {
        nextLens?.let(::updateLens)
    }

    private val currentFilters: AtlasFilters
        get() = session()?.filters ?: atlasGraphDefaultFilters()

    private fun updateRelationshipFilters(value: Set<GraphRelationshipFilter>) {
        val kinds =
            value
                .intersect(selectableRelationshipFilters)
                .flatMap(GraphRelationshipFilter::allowedKinds)
                .map(RelationshipKind::workspaceKind)
                .distinct()
                .sortedBy(WorkspaceGraphRelationKind::name)
        updateFilters { filters -> filters.copy(relationships = filters.relationships.copy(kinds = kinds)) }
    }

    private fun updateDeclarationFilters(value: Set<GraphDeclarationFilter>) {
        val declarations =
            value
                .flatMap(GraphDeclarationFilter::sessionKinds)
                .distinct()
                .sortedBy(AtlasDeclarationKind::ordinal)
        updateFilters { filters -> filters.copy(declarations = declarations) }
    }

    private fun updateFilters(transform: (AtlasFilters) -> AtlasFilters) {
        val current = session()?.filters ?: return
        val updated = transform(current)
        if (updated != current) dispatch(AtlasFiltersChanged(updated, recordHistory = false))
    }
}

internal fun atlasGraphDefaultFilters(): AtlasFilters =
    AtlasFilters(
        declarations = GraphDeclarationFilter.entries.flatMap(GraphDeclarationFilter::sessionKinds),
        relationships =
            AtlasRelationshipFilter(
                kinds =
                    selectableRelationshipFilters
                        .flatMap(GraphRelationshipFilter::allowedKinds)
                        .map(RelationshipKind::workspaceKind)
                        .distinct()
                        .sortedBy(WorkspaceGraphRelationKind::name),
            ),
    )

private val GraphDeclarationFilter.sessionKinds: List<AtlasDeclarationKind>
    get() =
        when (this) {
            GraphDeclarationFilter.CLASSES -> listOf(AtlasDeclarationKind.CLASS)
            GraphDeclarationFilter.INTERFACES -> listOf(AtlasDeclarationKind.INTERFACE)
            GraphDeclarationFilter.OBJECTS -> listOf(AtlasDeclarationKind.OBJECT)
            GraphDeclarationFilter.ENUMS -> listOf(AtlasDeclarationKind.ENUM)
            GraphDeclarationFilter.FUNCTIONS -> listOf(AtlasDeclarationKind.FUNCTION, AtlasDeclarationKind.METHOD)
            GraphDeclarationFilter.PROPERTIES -> listOf(AtlasDeclarationKind.PROPERTY)
        }

private val RelationshipKind.workspaceKind: WorkspaceGraphRelationKind
    get() = WorkspaceGraphRelationKind.valueOf(name)

private val GraphLens.sessionLayer: AtlasLayer
    get() =
        when (this) {
            GraphLens.FILES -> AtlasLayer.FILES
            GraphLens.SYMBOLS -> AtlasLayer.SYMBOLS
            GraphLens.PROBLEMS -> AtlasLayer.PROBLEMS
            GraphLens.CYCLES -> AtlasLayer.CYCLES
        }
