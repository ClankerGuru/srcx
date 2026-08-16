package zone.clanker.docx.web.atlas.session

internal fun reduceAtlasNavigation(
    session: AtlasSession,
    event: AtlasNavigationEvent,
): AtlasSession =
    when (event) {
        is AtlasCameraChanged -> session.recording(event.recordHistory, session.copy(camera = event.camera))
        is AtlasFocusChanged -> reduceFocusChange(session, event)
        is AtlasScopeSelectionReplaced -> reduceScopeChange(session, event)
        is AtlasPrimaryInspectionChanged -> reducePrimaryInspection(session, event)
        is AtlasSecondaryInspectionToggled -> reduceSecondaryInspection(session, event)
        is AtlasEvidenceChanged -> session.copy(inspection = session.inspection.copy(evidence = event.evidence))
        AtlasInspectionCleared -> session.recording(true, session.copy(inspection = AtlasInspection()))
        else -> reduceAtlasNavigationDetails(session, event)
    }

private fun reduceAtlasNavigationDetails(
    session: AtlasSession,
    event: AtlasNavigationEvent,
): AtlasSession =
    when (event) {
        is AtlasLayersChanged -> reduceLayerChange(session, event)
        is AtlasFiltersChanged -> session.recording(event.recordHistory, session.copy(filters = event.filters))
        is AtlasLayoutOverrideChanged -> reduceLayoutOverride(session, event)
        is AtlasFlyToRequested -> reduceFlyTo(session, event)
        AtlasNavigationCheckpoint -> recordAtlasCheckpoint(session)
        AtlasNavigateBack -> navigateAtlasBack(session)
        AtlasNavigateForward -> navigateAtlasForward(session)
        else -> session
    }

private fun reduceFlyTo(
    session: AtlasSession,
    event: AtlasFlyToRequested,
): AtlasSession {
    val focusId = event.target.id.takeIf(session.hierarchy::contains) ?: event.revealAncestorId
    if (focusId == null || !session.hierarchy.contains(focusId)) return session
    val path = session.hierarchy.pathTo(focusId)
    if (path.isEmpty()) return session
    val focus =
        session.focus.copy(
            path = path,
            automaticExpandedIds = canonicalIds(path),
            requestTargetId = event.target.id,
        )
    val inspection =
        session.inspection.copy(
            primary = event.target,
            secondary = session.inspection.secondary.filterNot { target -> target == event.target },
            evidence = null,
        )
    return session.recording(
        recordHistory = true,
        updated =
            session.copy(
                focus = focus,
                inspection = inspection,
                overlay = AtlasOverlay.Closed,
                gesture = AtlasGesture.Idle,
            ),
    )
}

private fun reduceFocusChange(
    session: AtlasSession,
    event: AtlasFocusChanged,
): AtlasSession {
    val containsFocus = session.hierarchy.containsPath(event.focus.path)
    val containsExpansions = event.focus.expandedIds.all(session.hierarchy::contains)
    val containsAutomaticExpansions = event.focus.automaticExpandedIds.all(session.hierarchy::contains)
    return if (containsFocus && containsExpansions && containsAutomaticExpansions) {
        val focus =
            AtlasFocus(
                event.focus.path,
                canonicalIds(event.focus.expandedIds),
                canonicalIds(event.focus.automaticExpandedIds),
                event.focus.requestTargetId,
            )
        session.recording(event.recordHistory, session.copy(focus = focus))
    } else {
        session
    }
}

private fun reduceScopeChange(
    session: AtlasSession,
    event: AtlasScopeSelectionReplaced,
): AtlasSession {
    val selectedIds = canonicalIds(event.selectedIds)
    if (selectedIds.any { id -> !session.hierarchy.containsAt(id, event.level) }) return session
    val scope = normalizeAtlasScope(session.scope.replacing(event.level, selectedIds), session.hierarchy)
    return session.recording(true, session.copy(scope = scope))
}

private fun reducePrimaryInspection(
    session: AtlasSession,
    event: AtlasPrimaryInspectionChanged,
): AtlasSession {
    if (event.target == session.inspection.primary) return session
    val inspection =
        session.inspection.copy(
            primary = event.target,
            secondary = session.inspection.secondary.filterNot { target -> target == event.target },
            evidence = null,
        )
    return session.recording(event.recordHistory, session.copy(inspection = inspection))
}

private fun reduceSecondaryInspection(
    session: AtlasSession,
    event: AtlasSecondaryInspectionToggled,
): AtlasSession {
    if (event.target == session.inspection.primary) return session
    val targets =
        if (event.target in session.inspection.secondary) {
            session.inspection.secondary - event.target
        } else {
            session.inspection.secondary + event.target
        }
    val inspection = session.inspection.copy(secondary = canonicalInspectionTargets(targets))
    return session.recording(event.recordHistory, session.copy(inspection = inspection))
}

private fun reduceLayerChange(
    session: AtlasSession,
    event: AtlasLayersChanged,
): AtlasSession {
    val layers = AtlasLayers(event.enabled.distinct().sortedBy { layer -> layer.ordinal })
    return session.recording(event.recordHistory, session.copy(layers = layers))
}

private fun reduceLayoutOverride(
    session: AtlasSession,
    event: AtlasLayoutOverrideChanged,
): AtlasSession {
    val overrides =
        if (event.override == null) {
            session.layout.overrides - event.id
        } else {
            session.layout.overrides + (event.id to event.override)
        }
    val updated = session.copy(layout = session.layout.copy(overrides = overrides))
    return session.recording(event.recordHistory, updated)
}

internal fun normalizeAtlasScope(
    scope: AtlasScope,
    hierarchy: AtlasHierarchyIndex,
): AtlasScope =
    scope.levels.fold(AtlasScope.ALL) { acceptedScope, selection ->
        val selectedIds =
            selection.ids.filter { id ->
                hierarchy.containsAt(id, selection.level) &&
                    acceptedScope.levels.all { ancestorSelection ->
                        ancestorSelection.ids.any { ancestorId -> hierarchy.isDescendantOf(id, ancestorId) }
                    }
            }
        acceptedScope.replacing(selection.level, canonicalIds(selectedIds))
    }

private fun AtlasSession.recording(
    recordHistory: Boolean,
    updated: AtlasSession,
): AtlasSession {
    if (!recordHistory || navigationSnapshot() == updated.navigationSnapshot()) return updated
    val backward = (history.backward + navigationSnapshot()).takeLast(ATLAS_HISTORY_LIMIT)
    return updated.copy(history = AtlasHistory(backward = backward))
}

private fun recordAtlasCheckpoint(session: AtlasSession): AtlasSession {
    val snapshot = session.navigationSnapshot()
    if (session.history.backward.lastOrNull() == snapshot && session.history.forward.isEmpty()) return session
    return session.copy(
        history = AtlasHistory(backward = (session.history.backward + snapshot).takeLast(ATLAS_HISTORY_LIMIT)),
    )
}

private fun navigateAtlasBack(session: AtlasSession): AtlasSession {
    val target = session.history.backward.lastOrNull() ?: return session
    return session.restoring(target).copy(
        history =
            AtlasHistory(
                backward = session.history.backward.dropLast(1),
                forward = listOf(session.navigationSnapshot()) + session.history.forward,
            ),
    )
}

private fun navigateAtlasForward(session: AtlasSession): AtlasSession {
    val target = session.history.forward.firstOrNull() ?: return session
    return session.restoring(target).copy(
        history =
            AtlasHistory(
                backward = (session.history.backward + session.navigationSnapshot()).takeLast(ATLAS_HISTORY_LIMIT),
                forward = session.history.forward.drop(1),
            ),
    )
}
