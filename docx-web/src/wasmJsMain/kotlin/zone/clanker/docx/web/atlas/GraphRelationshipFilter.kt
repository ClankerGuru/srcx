package zone.clanker.docx.web.atlas

import zone.clanker.report.model.RelationshipKind

internal enum class GraphRelationshipFilter(
    val label: String,
    val allowedKinds: Set<RelationshipKind>,
) {
    ALL("All edges", RelationshipKind.entries.toSet()),
    INHERITANCE("Inheritance", setOf(RelationshipKind.EXTENDS, RelationshipKind.IMPLEMENTS)),
    CALLS("Calls", setOf(RelationshipKind.CALL, RelationshipKind.CONSTRUCTOR)),
    REFERENCES(
        "References",
        setOf(
            RelationshipKind.NAME_REFERENCE,
            RelationshipKind.TYPE_REFERENCE,
            RelationshipKind.PROPERTY_TYPE,
            RelationshipKind.PARAMETER_TYPE,
            RelationshipKind.RETURN_TYPE,
        ),
    ),
    IMPORTS("Imports", setOf(RelationshipKind.IMPORT)),
}

internal val selectableRelationshipFilters: Set<GraphRelationshipFilter> =
    setOf(
        GraphRelationshipFilter.INHERITANCE,
        GraphRelationshipFilter.CALLS,
        GraphRelationshipFilter.REFERENCES,
    )
