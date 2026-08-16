package zone.clanker.docx.service.workspace

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import zone.clanker.report.model.WorkspaceIndexStatus

internal class WorkspaceWatcher(
    scope: CoroutineScope,
    private val registry: WorkspaceRegistry,
    private val events: WorkspaceEventHub,
    private val indexer: WorkspaceGenerationIndexer,
    private val pollIntervalMilliseconds: Long,
) : AutoCloseable {
    private val wakeups = Channel<Unit>(Channel.CONFLATED)
    private val job: Job = scope.launch { watch() }

    fun requestRefresh() {
        wakeups.trySend(Unit)
    }

    suspend fun refreshOnce() {
        registry.refresh().forEach(events::publish)
        registry.pendingGenerations().forEach { request ->
            val result =
                runCatching { indexer.index(request) }
                    .getOrElse { error ->
                        WorkspaceIndexStatus.Failed(
                            generationId = request.generationId,
                            message = error.message ?: error.javaClass.simpleName,
                        )
                    }
            registry.completeIndex(request, result)?.let(events::publish)
        }
    }

    override fun close() {
        job.cancel()
        wakeups.close()
    }

    private suspend fun watch() {
        while (kotlin.coroutines.coroutineContext.isActive) {
            withTimeoutOrNull(pollIntervalMilliseconds) { wakeups.receiveCatching() }
            refreshOnce()
        }
    }
}
