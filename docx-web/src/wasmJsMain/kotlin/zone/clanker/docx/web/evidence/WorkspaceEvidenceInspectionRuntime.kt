package zone.clanker.docx.web.evidence

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import zone.clanker.report.model.WorkspaceDeclarationEvidenceRequest
import zone.clanker.report.model.WorkspaceEvidenceLimits
import zone.clanker.report.model.WorkspaceEvidenceTarget
import zone.clanker.report.model.WorkspaceRelationshipOccurrenceRequest
import zone.clanker.report.model.WorkspaceRelationshipOccurrenceSelector
import zone.clanker.report.model.WorkspaceReverseUsageRequest

internal sealed interface WorkspaceEvidenceInspectionRequest {
    val target: WorkspaceEvidenceTarget

    data class Relationship(
        override val target: WorkspaceEvidenceTarget,
        val edgeId: String,
        val selector: WorkspaceRelationshipOccurrenceSelector,
    ) : WorkspaceEvidenceInspectionRequest

    data class Symbol(
        override val target: WorkspaceEvidenceTarget,
        val symbolId: String,
    ) : WorkspaceEvidenceInspectionRequest
}

internal class WorkspaceEvidenceInspectionRuntime {
    private val scope = MainScope()
    private var requestJob: Job? = null
    private var request: WorkspaceEvidenceInspectionRequest? = null
    private var gatewayProvider: WorkspaceEvidenceGatewayProvider? = null
    private var onStateChanged: (WorkspaceEvidenceInspectionState?) -> Unit = {}
    private var onLocationRequested: (projectId: String, fileId: String) -> Unit = { _, _ -> }
    private var relationshipNavigator: WorkspaceRelationshipEvidenceNavigator? = null
    private var usageNavigator: WorkspaceReverseUsageEvidenceNavigator? = null

    var state: WorkspaceEvidenceInspectionState? = null
        private set

    fun attach(
        provider: WorkspaceEvidenceGatewayProvider,
        onStateChanged: (WorkspaceEvidenceInspectionState?) -> Unit,
        onLocationRequested: (projectId: String, fileId: String) -> Unit,
    ) {
        gatewayProvider = provider
        this.onStateChanged = onStateChanged
        this.onLocationRequested = onLocationRequested
        state?.activeLocation?.let { location -> onLocationRequested(location.projectId, location.fileId) }
    }

    fun select(next: WorkspaceEvidenceInspectionRequest?) {
        if (next == request) {
            state?.activeLocation?.let { location -> onLocationRequested(location.projectId, location.fileId) }
            return
        }
        requestJob?.cancel()
        request = next
        relationshipNavigator = null
        usageNavigator = null
        when (next) {
            null -> publish(null)
            is WorkspaceEvidenceInspectionRequest.Relationship -> openRelationship(next)
            is WorkspaceEvidenceInspectionRequest.Symbol -> openSymbol(next)
        }
    }

    fun previous() {
        navigate { relationship, usage -> relationship?.previous() ?: usage?.previous() }
    }

    fun next() {
        navigate { relationship, usage -> relationship?.next() ?: usage?.next() }
    }

    fun selectOccurrence(index: Int) {
        relationshipNavigator?.select(index) ?: usageNavigator?.select(index)
    }

    fun release() {
        requestJob?.cancel()
        scope.cancel()
    }

    private fun openRelationship(selection: WorkspaceEvidenceInspectionRequest.Relationship) {
        val gateway = gatewayProvider?.current() ?: return publish(selection.unavailableState())
        publish(
            WorkspaceEvidenceInspectionState.Relationship(
                edgeId = selection.edgeId,
                selector = selection.selector,
            ),
        )
        val navigator =
            WorkspaceRelationshipEvidenceNavigator(
                gateway = gateway,
                onStateChanged = { page ->
                    publish(
                        WorkspaceEvidenceInspectionState.Relationship(
                            edgeId = selection.edgeId,
                            selector = selection.selector,
                            page = page,
                            loading = false,
                        ),
                    )
                },
                onSourceRequested = { projectId, fileId -> onLocationRequested(projectId, fileId) },
            )
        relationshipNavigator = navigator
        requestJob =
            scope.launch {
                runRequest(selection) {
                    navigator.open(
                        WorkspaceRelationshipOccurrenceRequest(
                            target = selection.target,
                            selector = selection.selector,
                            limit = WorkspaceEvidenceLimits.DEFAULT_PAGE_SIZE,
                        ),
                    )
                }
            }
    }

    private fun openSymbol(selection: WorkspaceEvidenceInspectionRequest.Symbol) {
        val gateway = gatewayProvider?.current() ?: return publish(selection.unavailableState())
        publish(WorkspaceEvidenceInspectionState.Symbol(symbolId = selection.symbolId))
        val navigator =
            WorkspaceReverseUsageEvidenceNavigator(
                gateway = gateway,
                onStateChanged = stateChanged@{ usages ->
                    val current = state as? WorkspaceEvidenceInspectionState.Symbol ?: return@stateChanged
                    if (current.symbolId == selection.symbolId) {
                        publish(current.copy(usages = usages, loading = false))
                    }
                },
                onSourceRequested = { projectId, fileId -> onLocationRequested(projectId, fileId) },
            )
        usageNavigator = navigator
        requestJob =
            scope.launch {
                runRequest(selection) {
                    val declaration =
                        navigator.declaration(
                            WorkspaceDeclarationEvidenceRequest(selection.target, selection.symbolId),
                        )
                    val current = state as? WorkspaceEvidenceInspectionState.Symbol
                    if (current?.symbolId == selection.symbolId) {
                        publish(current.copy(declaration = declaration))
                        declaration.declaration?.location?.let { location ->
                            onLocationRequested(location.projectId, location.fileId)
                        }
                    }
                    navigator.open(
                        WorkspaceReverseUsageRequest(
                            target = selection.target,
                            symbolId = selection.symbolId,
                            limit = WorkspaceEvidenceLimits.DEFAULT_PAGE_SIZE,
                        ),
                    )
                }
            }
    }

    private fun navigate(
        block: suspend (WorkspaceRelationshipEvidenceNavigator?, WorkspaceReverseUsageEvidenceNavigator?) -> Unit,
    ) {
        requestJob?.cancel()
        requestJob =
            scope.launch {
                runRequest(request) { block(relationshipNavigator, usageNavigator) }
            }
    }

    private suspend fun runRequest(
        expected: WorkspaceEvidenceInspectionRequest?,
        block: suspend () -> Unit,
    ) {
        val error = runCatching { block() }.exceptionOrNull() ?: return
        if (error is CancellationException) throw error
        if (request == expected) {
            publish(state.failed(error.message ?: "Exact source evidence could not be loaded"))
        }
    }

    private fun publish(next: WorkspaceEvidenceInspectionState?) {
        state = next
        onStateChanged(next)
    }
}

private fun WorkspaceEvidenceInspectionRequest.unavailableState(): WorkspaceEvidenceInspectionState =
    when (this) {
        is WorkspaceEvidenceInspectionRequest.Relationship ->
            WorkspaceEvidenceInspectionState.Relationship(
                edgeId = edgeId,
                selector = selector,
                loading = false,
                error = "This report does not expose an exact-evidence catalog.",
            )
        is WorkspaceEvidenceInspectionRequest.Symbol ->
            WorkspaceEvidenceInspectionState.Symbol(
                symbolId = symbolId,
                loading = false,
                error = "This report does not expose an exact-evidence catalog.",
            )
    }

private fun WorkspaceEvidenceInspectionState?.failed(message: String): WorkspaceEvidenceInspectionState? =
    when (this) {
        is WorkspaceEvidenceInspectionState.Relationship -> copy(loading = false, error = message)
        is WorkspaceEvidenceInspectionState.Symbol -> copy(loading = false, error = message)
        null -> null
    }
