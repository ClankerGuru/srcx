package zone.clanker.docx.web.atlas.session

import zone.clanker.report.model.WorkspaceGraphRequest
import zone.clanker.report.model.WorkspaceGraphSlice

internal fun reduceAtlasRequest(
    session: AtlasSession,
    event: AtlasRequestEvent,
): AtlasSession =
    when (event) {
        is AtlasRequestStarted -> startAtlasRequest(session, event.request)
        is AtlasRequestSucceeded -> settleAtlasRequest(session, event.token, event.slice)
        is AtlasRequestFailed -> failAtlasRequest(session, event)
        is AtlasRequestCancelled -> cancelAtlasRequest(session, event.token)
    }

private fun startAtlasRequest(
    session: AtlasSession,
    request: WorkspaceGraphRequest,
): AtlasSession {
    if (request.workspaceId != session.generation.workspaceId.value) return session
    if (request.generationId != null && request.generationId != session.generation.generationId) return session
    val pinnedRequest = request.copy(generationId = session.generation.generationId)
    val token = AtlasRequestToken(session.generation, session.request.revision + 1)
    return session.copy(request = AtlasRequestState.Pending(token, pinnedRequest))
}

private fun settleAtlasRequest(
    session: AtlasSession,
    token: AtlasRequestToken,
    slice: WorkspaceGraphSlice,
): AtlasSession {
    val pending = session.matchingPendingRequest(token) ?: return session
    if (!slice.matches(pending.request, token)) return session
    return session
        .extendHierarchy(slice.atlasHierarchyNodes())
        .copy(request = AtlasRequestState.Settled(token, pending.request, slice))
}

private fun failAtlasRequest(
    session: AtlasSession,
    event: AtlasRequestFailed,
): AtlasSession {
    val pending = session.matchingPendingRequest(event.token) ?: return session
    return session.copy(request = AtlasRequestState.Failed(event.token, pending.request, event.message))
}

private fun cancelAtlasRequest(
    session: AtlasSession,
    token: AtlasRequestToken,
): AtlasSession {
    val pending = session.matchingPendingRequest(token) ?: return session
    return session.copy(request = AtlasRequestState.Cancelled(token, pending.request))
}

private fun AtlasSession.matchingPendingRequest(token: AtlasRequestToken): AtlasRequestState.Pending? {
    if (token.generation != generation) return null
    return (request as? AtlasRequestState.Pending)?.takeIf { pending -> pending.token == token }
}

private fun WorkspaceGraphSlice.matches(
    request: WorkspaceGraphRequest,
    token: AtlasRequestToken,
): Boolean =
    target.workspaceId == token.generation.workspaceId.value &&
        target.generationId == token.generation.generationId &&
        target.facet == request.facet &&
        viewport.scopeRootIds == request.view.selection.scopeRootIds
