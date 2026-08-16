package zone.clanker.docx.service.http

import com.sun.net.httpserver.HttpServer
import zone.clanker.docx.service.workspace.WorkspaceEventHub
import zone.clanker.docx.service.workspace.WorkspaceGenerationIndexer
import zone.clanker.docx.service.workspace.WorkspaceRegistry
import zone.clanker.docx.service.workspace.WorkspaceWatcher
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal data class WorkspaceHttpSettings(
    val host: String,
    val port: Int,
    val token: String,
    val maxThreads: Int,
    val queueCapacity: Int,
)

internal data class WorkspaceHttpComponents(
    val registry: WorkspaceRegistry,
    val events: WorkspaceEventHub,
    val watcher: WorkspaceWatcher,
    val indexer: WorkspaceGenerationIndexer,
)

/** The replaceable JVM HTTP edge; no JDK HTTP type escapes this package. */
internal class WorkspaceHttpRuntime private constructor(
    private val server: HttpServer,
    private val executor: ExecutorService,
) : AutoCloseable {
    val port: Int = server.address.port

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }

    companion object {
        fun start(
            settings: WorkspaceHttpSettings,
            components: WorkspaceHttpComponents,
        ): WorkspaceHttpRuntime {
            val threadNumber = AtomicInteger()
            val executor =
                ThreadPoolExecutor(
                    settings.maxThreads,
                    settings.maxThreads,
                    0,
                    TimeUnit.MILLISECONDS,
                    ArrayBlockingQueue(settings.queueCapacity),
                    { runnable ->
                        Thread(
                            runnable,
                            "docx-service-http-${threadNumber.incrementAndGet()}",
                        ).apply { isDaemon = true }
                    },
                    ThreadPoolExecutor.CallerRunsPolicy(),
                )
            return runCatching {
                val server =
                    HttpServer.create(
                        InetSocketAddress(InetAddress.getByName(settings.host), settings.port),
                        settings.queueCapacity,
                    )
                server.executor = executor
                val router =
                    WorkspaceHttpRouter(
                        components.registry,
                        components.events,
                        components.watcher,
                        components.indexer,
                        settings.token,
                    )
                server.createContext("/", router::handle)
                server.start()
                WorkspaceHttpRuntime(server, executor)
            }.onFailure {
                executor.shutdownNow()
            }.getOrThrow()
        }
    }
}
