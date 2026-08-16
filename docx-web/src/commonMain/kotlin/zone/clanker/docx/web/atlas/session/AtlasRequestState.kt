package zone.clanker.docx.web.atlas.session

import zone.clanker.report.model.WorkspaceGraphRequest
import zone.clanker.report.model.WorkspaceGraphSlice

internal data class AtlasRequestToken(
    val generation: AtlasGeneration,
    val revision: Long,
) {
    init {
        require(revision > 0) { "Atlas request revisions must be positive" }
    }
}

internal sealed interface AtlasRequestState {
    val revision: Long

    data class Idle(
        override val revision: Long = 0,
    ) : AtlasRequestState {
        init {
            require(revision >= 0) { "Atlas request revision must not be negative" }
        }
    }

    data class Pending(
        val token: AtlasRequestToken,
        val request: WorkspaceGraphRequest,
    ) : AtlasRequestState {
        init {
            requireRequestMatchesToken(request, token)
        }

        override val revision: Long = token.revision
    }

    data class Settled(
        val token: AtlasRequestToken,
        val request: WorkspaceGraphRequest,
        val slice: WorkspaceGraphSlice,
    ) : AtlasRequestState {
        init {
            requireRequestMatchesToken(request, token)
            requireSliceMatchesToken(slice, request, token)
        }

        override val revision: Long = token.revision
    }

    data class Failed(
        val token: AtlasRequestToken,
        val request: WorkspaceGraphRequest,
        val message: String,
    ) : AtlasRequestState {
        init {
            requireRequestMatchesToken(request, token)
            require(message.isNotBlank()) { "Atlas request failure must explain the error" }
        }

        override val revision: Long = token.revision
    }

    data class Cancelled(
        val token: AtlasRequestToken,
        val request: WorkspaceGraphRequest,
    ) : AtlasRequestState {
        init {
            requireRequestMatchesToken(request, token)
        }

        override val revision: Long = token.revision
    }
}

private fun requireRequestMatchesToken(
    request: WorkspaceGraphRequest,
    token: AtlasRequestToken,
) {
    require(request.workspaceId == token.generation.workspaceId.value) {
        "Atlas request workspace must match its token"
    }
    require(request.generationId == token.generation.generationId) {
        "Atlas request generation must match its token"
    }
}

private fun requireSliceMatchesToken(
    slice: WorkspaceGraphSlice,
    request: WorkspaceGraphRequest,
    token: AtlasRequestToken,
) {
    require(slice.target.workspaceId == token.generation.workspaceId.value) {
        "Atlas slice workspace must match its token"
    }
    require(slice.target.generationId == token.generation.generationId) {
        "Atlas slice generation must match its token"
    }
    require(slice.target.facet == request.facet) { "Atlas slice facet must match its request" }
    require(slice.viewport.scopeRootIds == request.view.selection.scopeRootIds) {
        "Atlas slice scope roots must match its request"
    }
}
