package zone.clanker.docx.web.atlas.session

internal fun reduceAtlasInteraction(
    session: AtlasSession,
    event: AtlasInteractionEvent,
): AtlasSession =
    when (event) {
        is AtlasOverlayChanged -> session.copy(overlay = event.overlay)
        is AtlasGestureChanged -> session.copy(gesture = event.gesture)
        is AtlasHoverChanged -> session.copy(inspection = session.inspection.copy(hovered = event.target))
        is AtlasCalculatedLayoutChanged -> session.copy(layout = session.layout.copy(calculated = event.calculated))
        is AtlasSemanticExpansionChanged -> reduceSemanticExpansion(session, event)
    }

private fun reduceSemanticExpansion(
    session: AtlasSession,
    event: AtlasSemanticExpansionChanged,
): AtlasSession {
    if (!session.hierarchy.contains(event.id)) return session
    val ids =
        if (event.expanded) {
            session.focus.automaticExpandedIds + event.id
        } else {
            session.focus.automaticExpandedIds - event.id
        }
    return session.copy(
        focus = session.focus.copy(automaticExpandedIds = canonicalIds(ids)),
    )
}
