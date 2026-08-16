package zone.clanker.docx.service

import java.nio.file.Path
import java.security.SecureRandom
import java.util.Base64

internal data class DocxServiceConfig(
    val host: String = DEFAULT_HOST,
    val port: Int = 0,
    val registryFile: Path = defaultRegistryFile(),
    val endpointFile: Path = registryFile.resolveSibling(DEFAULT_ENDPOINT_FILE),
    val indexFile: Path = registryFile.resolveSibling(DEFAULT_INDEX_FILE),
    val token: String = randomToken(),
    val limits: DocxServiceLimits = DocxServiceLimits(),
) {
    init {
        require(host.isNotBlank()) { "DOCX service host must not be blank." }
        require(port in 0..MAX_PORT) { "DOCX service port must be between 0 and $MAX_PORT." }
        require(token.isNotBlank()) { "DOCX service token must not be blank." }
    }
}

internal data class DocxServiceLimits(
    val pollIntervalMilliseconds: Long = DEFAULT_POLL_INTERVAL_MILLISECONDS,
    val maxHttpThreads: Int = defaultHttpThreadCount(),
    val maxQueuedRequests: Int = defaultHttpQueueCapacity(maxHttpThreads),
    val maxEventSubscribers: Int = defaultEventSubscriberCount(maxHttpThreads),
) {
    init {
        require(pollIntervalMilliseconds > 0) { "DOCX service poll interval must be positive." }
        require(maxHttpThreads >= MIN_HTTP_THREADS) { "DOCX service requires at least $MIN_HTTP_THREADS HTTP threads." }
        require(maxQueuedRequests > 0) { "DOCX service HTTP queue capacity must be positive." }
        require(maxEventSubscribers in 1 until maxHttpThreads) {
            "DOCX event subscribers must be positive and fewer than the HTTP thread count."
        }
    }
}

internal fun defaultRegistryFile(): Path =
    Path.of(
        System.getProperty("user.home"),
        ".srcx",
        "docx-service",
        "registry.json",
    )

internal fun randomToken(): String =
    ByteArray(TOKEN_BYTES)
        .also(SecureRandom()::nextBytes)
        .let { bytes -> Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) }

internal fun defaultHttpThreadCount(processors: Int = Runtime.getRuntime().availableProcessors()): Int =
    (processors * HTTP_THREADS_PER_PROCESSOR).coerceIn(MIN_HTTP_THREADS, MAX_HTTP_THREADS)

internal fun defaultHttpQueueCapacity(httpThreads: Int): Int = httpThreads * QUEUED_REQUESTS_PER_THREAD

internal fun defaultEventSubscriberCount(httpThreads: Int): Int = (httpThreads / 2).coerceAtLeast(1)

internal const val DEFAULT_HOST: String = "127.0.0.1"
internal const val DEFAULT_ENDPOINT_FILE: String = "endpoint.json"
internal const val DEFAULT_INDEX_FILE: String = "workspace-index.sqlite"
internal const val DEFAULT_POLL_INTERVAL_MILLISECONDS: Long = 250
private const val MAX_PORT: Int = 65_535
private const val TOKEN_BYTES: Int = 32
private const val HTTP_THREADS_PER_PROCESSOR: Int = 2
private const val MIN_HTTP_THREADS: Int = 4
private const val MAX_HTTP_THREADS: Int = 32
private const val QUEUED_REQUESTS_PER_THREAD: Int = 16
