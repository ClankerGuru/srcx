package zone.clanker.docx.web.atlas.session

internal fun reduceAtlasGeneration(
    session: AtlasSession,
    event: AtlasGenerationChanged,
): AtlasSession {
    if (session.generation == event.generation && session.hierarchy == event.hierarchy) return session
    val sameWorkspace = session.generation.workspaceId == event.generation.workspaceId
    return if (sameWorkspace) {
        session.copy(
            generation = event.generation,
            hierarchy = event.hierarchy,
            focus = normalizeAtlasFocus(session.focus, event.hierarchy),
            scope = normalizeAtlasScope(session.scope, event.hierarchy),
            inspection = normalizeAtlasInspection(session.inspection, event.hierarchy),
            overlay = AtlasOverlay.Closed,
            gesture = AtlasGesture.Idle,
            history = AtlasHistory(),
            layout = session.layout.copy(calculated = emptyMap()),
            request = AtlasRequestState.Idle(session.request.revision + 1),
        )
    } else {
        AtlasSession(
            generation = event.generation,
            hierarchy = event.hierarchy,
            layers = session.layers,
            filters = session.filters,
            request = AtlasRequestState.Idle(session.request.revision + 1),
        )
    }
}

internal fun normalizeAtlasFocus(
    focus: AtlasFocus,
    hierarchy: AtlasHierarchyIndex,
): AtlasFocus {
    val targetId = focus.targetId
    val path = targetId?.let(hierarchy::pathTo).orEmpty()
    val expandedIds = canonicalIds(focus.expandedIds.filter(hierarchy::contains))
    val automaticExpandedIds = canonicalIds(focus.automaticExpandedIds.filter(hierarchy::contains))
    return AtlasFocus(path, expandedIds, automaticExpandedIds)
}

internal fun normalizeAtlasInspection(
    inspection: AtlasInspection,
    hierarchy: AtlasHierarchyIndex,
): AtlasInspection {
    val primary = inspection.primary?.takeIf { target -> target.isKnownNode(hierarchy) }
    val secondary =
        canonicalInspectionTargets(
            inspection.secondary.filter { target -> target != primary && target.isKnownNode(hierarchy) },
        )
    return AtlasInspection(primary = primary, secondary = secondary)
}

private fun AtlasInspectionTarget.isKnownNode(hierarchy: AtlasHierarchyIndex): Boolean =
    kind == AtlasInspectionKind.NODE && hierarchy.contains(id)
