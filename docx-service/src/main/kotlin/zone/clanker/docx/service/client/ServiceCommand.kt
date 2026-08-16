package zone.clanker.docx.service.client

import zone.clanker.docx.service.DEFAULT_ENDPOINT_FILE
import zone.clanker.docx.service.DEFAULT_HOST
import zone.clanker.docx.service.DEFAULT_INDEX_FILE
import zone.clanker.docx.service.DEFAULT_POLL_INTERVAL_MILLISECONDS
import zone.clanker.docx.service.DocxServiceLimits
import zone.clanker.docx.service.defaultEventSubscriberCount
import zone.clanker.docx.service.defaultHttpQueueCapacity
import zone.clanker.docx.service.defaultHttpThreadCount
import zone.clanker.docx.service.defaultRegistryFile
import zone.clanker.docx.service.randomToken
import zone.clanker.docx.service.workspace.requireWorkspaceId
import java.net.URI
import java.nio.file.Path

internal sealed interface ServiceCommand {
    data class Serve(
        val options: ServiceLaunchOptions,
    ) : ServiceCommand

    data class Register(
        val connection: ServiceConnection,
        val workspaceId: String,
        val siteDirectory: Path,
    ) : ServiceCommand

    data class Unregister(
        val connection: ServiceConnection,
        val workspaceId: String,
    ) : ServiceCommand

    data class ListWorkspaces(
        val connection: ServiceConnection,
    ) : ServiceCommand

    data object Help : ServiceCommand
}

internal data class ServiceLaunchOptions(
    val host: String,
    val port: Int,
    val registryFile: Path,
    val endpointFile: Path,
    val indexFile: Path,
    val token: String,
    val limits: DocxServiceLimits,
)

internal data class ServiceConnection(
    val baseUrl: URI,
    val token: String?,
)

internal fun parseCommand(
    arguments: Array<String>,
    environment: Map<String, String> = System.getenv(),
): ServiceCommand {
    if (arguments.isEmpty() || arguments.contentEquals(arrayOf("--help")) || arguments.contentEquals(arrayOf("-h"))) {
        return ServiceCommand.Help
    }
    val command = arguments.first()
    val options = parseOptions(arguments.drop(1))
    return when (command) {
        "serve" -> parseServe(options, environment)
        "register" -> parseRegister(options, environment)
        "unregister" -> parseUnregister(options, environment)
        "list" -> ServiceCommand.ListWorkspaces(resolveConnection(options, environment, requireToken = false))
        else -> throw IllegalArgumentException("Unknown command '$command'.\n\n$USAGE")
    }
}

private fun parseServe(
    options: Map<String, String>,
    environment: Map<String, String>,
): ServiceCommand.Serve {
    requireOnly(options, SERVE_OPTIONS)
    val registry = options["registry"]?.let(Path::of) ?: defaultRegistryFile()
    val endpoint = options["endpoint"]?.let(Path::of) ?: registry.resolveSibling(DEFAULT_ENDPOINT_FILE)
    val index = options["index"]?.let(Path::of) ?: registry.resolveSibling(DEFAULT_INDEX_FILE)
    val token = options["token"] ?: environment["DOCX_SERVICE_TOKEN"]?.takeIf(String::isNotBlank) ?: randomToken()
    val maxHttpThreads = options.intValue("http-threads", defaultHttpThreadCount())
    return ServiceCommand.Serve(
        ServiceLaunchOptions(
            host = options["host"] ?: DEFAULT_HOST,
            port = options.intValue("port", 0),
            registryFile = registry.toAbsolutePath().normalize(),
            endpointFile = endpoint.toAbsolutePath().normalize(),
            indexFile = index.toAbsolutePath().normalize(),
            token = token,
            limits =
                DocxServiceLimits(
                    pollIntervalMilliseconds =
                        options.longValue("poll-milliseconds", DEFAULT_POLL_INTERVAL_MILLISECONDS),
                    maxHttpThreads = maxHttpThreads,
                    maxQueuedRequests = options.intValue("queued-requests", defaultHttpQueueCapacity(maxHttpThreads)),
                    maxEventSubscribers =
                        options.intValue("event-subscribers", defaultEventSubscriberCount(maxHttpThreads)),
                ),
        ),
    )
}

private fun parseRegister(
    options: Map<String, String>,
    environment: Map<String, String>,
): ServiceCommand.Register {
    requireOnly(options, CLIENT_OPTIONS + setOf("id", "site"))
    val workspaceId = requireWorkspaceId(options.required("id"))
    val siteDirectory = Path.of(options.required("site")).toAbsolutePath().normalize()
    return ServiceCommand.Register(
        connection = resolveConnection(options, environment, requireToken = true),
        workspaceId = workspaceId,
        siteDirectory = siteDirectory,
    )
}

private fun parseUnregister(
    options: Map<String, String>,
    environment: Map<String, String>,
): ServiceCommand.Unregister {
    requireOnly(options, CLIENT_OPTIONS + "id")
    return ServiceCommand.Unregister(
        connection = resolveConnection(options, environment, requireToken = true),
        workspaceId = requireWorkspaceId(options.required("id")),
    )
}

private fun resolveConnection(
    options: Map<String, String>,
    environment: Map<String, String>,
    requireToken: Boolean,
): ServiceConnection {
    val endpointPath =
        options["endpoint"]
            ?.let(Path::of)
            ?: defaultRegistryFile().resolveSibling(DEFAULT_ENDPOINT_FILE)
    val discovered =
        endpointPath
            .takeIf { options["service"] == null }
            ?.let(EndpointDiscovery::read)
    val baseUrl = options["service"]?.let(::normalizedServiceUri) ?: discovered?.baseUrl
    requireNotNull(baseUrl) { "No running service was discovered; pass --service or --endpoint." }
    val token = options["token"] ?: environment["DOCX_SERVICE_TOKEN"] ?: discovered?.token
    require(!requireToken || !token.isNullOrBlank()) { "A service token is required for this command." }
    return ServiceConnection(baseUrl, token)
}

private fun normalizedServiceUri(raw: String): URI {
    val parsed = URI(raw)
    require(parsed.scheme == "http" || parsed.scheme == "https") { "Service URL must use http or https." }
    require(parsed.host != null) { "Service URL must include a host." }
    return parsed.resolve(parsed.path.orEmpty().let { path -> if (path.endsWith('/')) path else "$path/" })
}

private fun parseOptions(arguments: List<String>): Map<String, String> {
    require(arguments.size % 2 == 0) { "Every option must have a value.\n\n$USAGE" }
    return arguments
        .chunked(2)
        .associate { pair ->
            val option = pair[0]
            require(option.startsWith("--")) { "Expected an option but found '$option'.\n\n$USAGE" }
            option.removePrefix("--") to pair[1]
        }.also { options ->
            require(options.size == arguments.size / 2) { "Each option may be supplied only once." }
        }
}

private fun requireOnly(
    options: Map<String, String>,
    allowed: Set<String>,
) {
    val unexpected = options.keys - allowed
    require(unexpected.isEmpty()) { "Unknown option(s): ${unexpected.sorted().joinToString()}.\n\n$USAGE" }
}

private fun Map<String, String>.required(name: String): String =
    requireNotNull(this[name]) { "Missing required --$name option.\n\n$USAGE" }

private fun Map<String, String>.intValue(
    name: String,
    default: Int,
): Int = this[name]?.let { value -> requireNotNull(value.toIntOrNull()) { "--$name must be an integer." } } ?: default

private fun Map<String, String>.longValue(
    name: String,
    default: Long,
): Long = this[name]?.let { value -> requireNotNull(value.toLongOrNull()) { "--$name must be an integer." } } ?: default

internal const val USAGE: String =
    """Usage:
  docx-service serve [--host HOST] [--port PORT] [--registry PATH] [--endpoint PATH] [--index PATH] [--token TOKEN]
    [--http-threads COUNT] [--queued-requests COUNT] [--event-subscribers COUNT]
  docx-service register --id ID --site PATH [--service URL] [--endpoint PATH] [--token TOKEN]
  docx-service unregister --id ID [--service URL] [--endpoint PATH] [--token TOKEN]
  docx-service list [--service URL] [--endpoint PATH]

Mutating commands also read DOCX_SERVICE_TOKEN. Port 0 asks the OS for a free port.
"""

private val SERVE_OPTIONS: Set<String> =
    setOf(
        "host",
        "port",
        "registry",
        "endpoint",
        "index",
        "token",
        "poll-milliseconds",
        "http-threads",
        "queued-requests",
        "event-subscribers",
    )
private val CLIENT_OPTIONS: Set<String> = setOf("service", "endpoint", "token")
