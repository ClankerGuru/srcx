package zone.clanker.docx.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import zone.clanker.docx.service.http.WorkspaceHttpComponents
import zone.clanker.docx.service.http.WorkspaceHttpRuntime
import zone.clanker.docx.service.http.WorkspaceHttpSettings
import zone.clanker.docx.service.index.SqliteWorkspaceIndex
import zone.clanker.docx.service.workspace.DisabledWorkspaceGenerationIndexer
import zone.clanker.docx.service.workspace.EndpointFile
import zone.clanker.docx.service.workspace.RegistryLease
import zone.clanker.docx.service.workspace.WorkspaceEventHub
import zone.clanker.docx.service.workspace.WorkspaceGenerationIndexer
import zone.clanker.docx.service.workspace.WorkspaceRegistry
import zone.clanker.docx.service.workspace.WorkspaceRegistryStore
import zone.clanker.docx.service.workspace.WorkspaceWatcher
import zone.clanker.report.model.WorkspaceServiceEndpoint
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

internal class DocxWorkspaceServer private constructor(
    private val runtime: ServerRuntime,
    private val endpointFile: EndpointFile,
    private val registryLease: RegistryLease,
    val endpoint: WorkspaceServiceEndpoint,
    val indexDescription: String,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val stopped = CountDownLatch(1)

    fun awaitShutdown() = stopped.await()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runtime.close()
        endpointFile.close()
        registryLease.close()
        stopped.countDown()
    }

    companion object {
        fun start(config: DocxServiceConfig): DocxWorkspaceServer =
            runCatching { SqliteWorkspaceIndex.open(config.indexFile) }
                .fold(
                    onSuccess = { indexer -> start(config, indexer, "SQLite index ${config.indexFile}") },
                    onFailure = { error ->
                        start(
                            config,
                            DisabledWorkspaceGenerationIndexer,
                            "disabled (${error.message ?: error.javaClass.simpleName})",
                        )
                    },
                )

        internal fun start(
            config: DocxServiceConfig,
            indexer: WorkspaceGenerationIndexer,
        ): DocxWorkspaceServer = start(config, indexer, "custom index")

        private fun start(
            config: DocxServiceConfig,
            indexer: WorkspaceGenerationIndexer,
            indexDescription: String,
        ): DocxWorkspaceServer {
            System.setProperty("java.awt.headless", "true")
            val lease = RegistryLease.acquire(config.registryFile)
            return runCatching {
                startWithLease(config, indexer, indexDescription, lease)
            }.onFailure {
                indexer.close()
                lease.close()
            }.getOrThrow()
        }

        private fun startWithLease(
            config: DocxServiceConfig,
            indexer: WorkspaceGenerationIndexer,
            indexDescription: String,
            lease: RegistryLease,
        ): DocxWorkspaceServer {
            val store = WorkspaceRegistryStore(config.registryFile)
            val registry = WorkspaceRegistry(store, indexer.enabled)
            val events = WorkspaceEventHub(config.limits.maxEventSubscribers)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val watcher =
                WorkspaceWatcher(
                    scope = scope,
                    registry = registry,
                    events = events,
                    indexer = indexer,
                    pollIntervalMilliseconds = config.limits.pollIntervalMilliseconds,
                )
            val http =
                runCatching {
                    WorkspaceHttpRuntime.start(
                        settings =
                            WorkspaceHttpSettings(
                                host = config.host,
                                port = config.port,
                                token = config.token,
                                maxThreads = config.limits.maxHttpThreads,
                                queueCapacity = config.limits.maxQueuedRequests,
                            ),
                        components =
                            WorkspaceHttpComponents(
                                registry = registry,
                                events = events,
                                watcher = watcher,
                                indexer = indexer,
                            ),
                    )
                }.onFailure {
                    watcher.close()
                    scope.cancel()
                    events.close()
                }.getOrThrow()
            val endpoint =
                WorkspaceServiceEndpoint(
                    baseUrl = serviceUrl(config.host, http.port).toString(),
                    token = config.token,
                )
            return runCatching {
                val endpointFile = EndpointFile.write(config.endpointFile, endpoint)
                DocxWorkspaceServer(
                    runtime = ServerRuntime(http, scope, watcher, events, indexer),
                    endpointFile = endpointFile,
                    registryLease = lease,
                    endpoint = endpoint,
                    indexDescription = indexDescription,
                )
            }.onFailure {
                http.close()
                watcher.close()
                scope.cancel()
                events.close()
            }.getOrThrow()
        }

        private fun serviceUrl(
            configuredHost: String,
            port: Int,
        ): URI {
            val host = configuredHost.takeUnless { it == "0.0.0.0" || it == "::" } ?: DEFAULT_HOST
            return URI("http", null, host, port, "/", null, null)
        }
    }
}

private class ServerRuntime(
    private val http: WorkspaceHttpRuntime,
    private val scope: CoroutineScope,
    private val watcher: WorkspaceWatcher,
    private val events: WorkspaceEventHub,
    private val indexer: WorkspaceGenerationIndexer,
) : AutoCloseable {
    override fun close() {
        http.close()
        events.close()
        watcher.close()
        scope.cancel()
        indexer.close()
    }
}
