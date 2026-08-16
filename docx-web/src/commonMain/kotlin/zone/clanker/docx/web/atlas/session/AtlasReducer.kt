package zone.clanker.docx.web.atlas.session

internal fun reduceAtlasSession(
    session: AtlasSession,
    event: AtlasEvent,
): AtlasSession =
    when (event) {
        is AtlasGenerationChanged -> reduceAtlasGeneration(session, event)
        is AtlasHierarchyExtended -> session.extendHierarchy(event.nodes)
        is AtlasNavigationEvent -> reduceAtlasNavigation(session, event)
        is AtlasInteractionEvent -> reduceAtlasInteraction(session, event)
        is AtlasRequestEvent -> reduceAtlasRequest(session, event)
    }

internal fun Iterable<AtlasEvent>.reduceAtlasSession(initial: AtlasSession): AtlasSession =
    fold(initial, ::reduceAtlasSession)
