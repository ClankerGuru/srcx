package zone.clanker.gradle.docx.preview

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

internal class DocxPreviewServer private constructor(
    private val server: HttpServer,
    private val executor: ExecutorService,
) : AutoCloseable {
    val url: URI = URI("http://127.0.0.1:${server.address.port}/")

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }

    companion object {
        fun start(siteDirectory: Path): DocxPreviewServer {
            val siteRoot = siteDirectory.toRealPath()
            require(Files.isRegularFile(siteRoot.resolve("index.html"))) { "DOCX site has no index.html: $siteRoot" }
            val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
            val threadNumber = AtomicInteger()
            val executor =
                Executors.newFixedThreadPool(PREVIEW_THREADS) { runnable ->
                    Thread(runnable, "docx-preview-${threadNumber.incrementAndGet()}").apply { isDaemon = true }
                }
            server.executor = executor
            server.createContext("/") { exchange ->
                handle(exchange, siteRoot)
            }
            server.start()
            return DocxPreviewServer(server, executor)
        }

        private fun handle(
            exchange: HttpExchange,
            siteRoot: Path,
        ) {
            runCatching { serve(exchange, siteRoot) }
                .onFailure { error ->
                    if (exchange.responseCode == NOT_SENT) {
                        runCatching {
                            respond(
                                exchange,
                                INTERNAL_ERROR,
                                "Preview request failed: ${error.message}\n".encodeToByteArray(),
                            )
                        }
                    }
                }.also { exchange.close() }
        }

        @Suppress("ReturnCount")
        private fun serve(
            exchange: HttpExchange,
            siteRoot: Path,
        ) {
            if (exchange.requestMethod != "GET" && exchange.requestMethod != "HEAD") {
                exchange.responseHeaders.set("Allow", "GET, HEAD")
                respond(exchange, METHOD_NOT_ALLOWED, "Method not allowed\n".encodeToByteArray())
                return
            }
            val relative = safeRelativePath(exchange.requestURI.rawPath)
            if (relative == null) {
                respond(exchange, FORBIDDEN, "Forbidden\n".encodeToByteArray())
                return
            }
            val unresolved = siteRoot.resolve(relative).normalize()
            val candidate =
                when {
                    Files.isDirectory(unresolved) -> unresolved.resolve("index.html")
                    else -> unresolved
                }
            if (!Files.isRegularFile(candidate)) {
                respond(exchange, NOT_FOUND, "Not found\n".encodeToByteArray())
                return
            }
            val realFile = candidate.toRealPath()
            if (!realFile.startsWith(siteRoot)) {
                respond(exchange, FORBIDDEN, "Forbidden\n".encodeToByteArray())
                return
            }
            val bytes = Files.readAllBytes(realFile)
            exchange.responseHeaders.set("Content-Type", mediaType(realFile))
            exchange.responseHeaders.set("X-Content-Type-Options", "nosniff")
            exchange.responseHeaders.set("Content-Length", bytes.size.toString())
            if (exchange.requestMethod == "HEAD") {
                exchange.sendResponseHeaders(OK, NO_RESPONSE_BODY)
            } else {
                exchange.sendResponseHeaders(OK, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }

        private fun safeRelativePath(decodedPath: String): Path? {
            if ('\u0000' in decodedPath || '\\' in decodedPath) {
                return null
            }
            if (decodedPath.contains("%2e", ignoreCase = true) || decodedPath.contains("%2f", ignoreCase = true)) {
                return null
            }
            val segments = decodedPath.removePrefix("/").split('/').filter(String::isNotEmpty)
            return if (segments.any { it == "." || it == ".." }) {
                null
            } else {
                Path.of(if (segments.isEmpty()) "index.html" else segments.joinToString("/"))
            }
        }

        private fun respond(
            exchange: HttpExchange,
            status: Int,
            bytes: ByteArray,
        ) {
            exchange.responseHeaders.set("Content-Type", "text/plain; charset=utf-8")
            exchange.responseHeaders.set("X-Content-Type-Options", "nosniff")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            if (exchange.requestMethod != "HEAD") exchange.responseBody.use { it.write(bytes) }
        }

        private fun mediaType(path: Path): String =
            when (
                path.fileName
                    .toString()
                    .substringAfterLast('.', "")
                    .lowercase()
            ) {
                "html" -> "text/html; charset=utf-8"
                "css" -> "text/css; charset=utf-8"
                "js", "mjs" -> "text/javascript; charset=utf-8"
                "json", "map" -> "application/json; charset=utf-8"
                "wasm" -> "application/wasm"
                "svg" -> "image/svg+xml"
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                "webp" -> "image/webp"
                "woff" -> "font/woff"
                "woff2" -> "font/woff2"
                else -> "application/octet-stream"
            }

        private const val PREVIEW_THREADS: Int = 4
        private const val OK: Int = 200
        private const val FORBIDDEN: Int = 403
        private const val NOT_FOUND: Int = 404
        private const val METHOD_NOT_ALLOWED: Int = 405
        private const val INTERNAL_ERROR: Int = 500
        private const val NOT_SENT: Int = -1
        private const val NO_RESPONSE_BODY: Long = -1
    }
}
