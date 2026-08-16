package zone.clanker.docx.service.http

import com.sun.net.httpserver.HttpExchange
import zone.clanker.docx.service.workspace.INDEX_FILE
import zone.clanker.docx.service.workspace.WorkspaceRegistry
import zone.clanker.docx.service.workspace.requireWorkspaceId
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

internal class StaticSiteResponder(
    private val registry: WorkspaceRegistry,
) {
    fun respond(exchange: HttpExchange) {
        if (exchange.requestMethod != "GET" && exchange.requestMethod != "HEAD") {
            exchange.responseHeaders.set("Allow", "GET, HEAD")
            respondText(exchange, METHOD_NOT_ALLOWED, "Method not allowed.\n")
        } else {
            respondGet(exchange)
        }
    }

    private fun respondGet(exchange: HttpExchange) {
        val route = parseRoute(exchange.requestURI.rawPath)
        if (route == null) {
            respondText(exchange, BAD_REQUEST, "Invalid workspace path.\n")
        } else {
            respondRoute(exchange, route)
        }
    }

    private fun respondRoute(
        exchange: HttpExchange,
        route: StaticRoute,
    ) {
        val siteDirectory = registry.siteDirectory(route.workspaceId)
        when {
            siteDirectory == null -> respondText(exchange, NOT_FOUND, "Workspace is not registered.\n")
            route.redirectToSlash -> redirectToWorkspace(exchange, route.workspaceId)
            else -> respondResolvedFile(exchange, siteDirectory, route.relativePath)
        }
    }

    private fun redirectToWorkspace(
        exchange: HttpExchange,
        workspaceId: String,
    ) {
        exchange.responseHeaders.set("Location", "/w/$workspaceId/")
        securityHeaders(exchange)
        exchange.sendResponseHeaders(TEMPORARY_REDIRECT, NO_RESPONSE_BODY)
    }

    private fun respondResolvedFile(
        exchange: HttpExchange,
        siteDirectory: Path,
        relativePath: String,
    ) {
        val file = resolveFile(siteDirectory, relativePath)
        if (file == null) respondText(exchange, NOT_FOUND, "Not found.\n") else respondFile(exchange, file)
    }

    private fun parseRoute(rawPath: String): StaticRoute? =
        runCatching {
            require('%' !in rawPath && '\u0000' !in rawPath && '\\' !in rawPath)
            val remainder = rawPath.removePrefix("/w/")
            require(remainder != rawPath && remainder.isNotBlank())
            val workspaceId = requireWorkspaceId(remainder.substringBefore('/'))
            val hasSlash = '/' in remainder
            val relative = remainder.substringAfter('/', INDEX_FILE).ifBlank { INDEX_FILE }
            val segments = relative.split('/').filter(String::isNotEmpty)
            require(segments.none { segment -> segment == "." || segment == ".." })
            StaticRoute(workspaceId, segments.joinToString("/"), !hasSlash)
        }.getOrNull()

    private fun resolveFile(
        siteDirectory: Path,
        relativePath: String,
    ): Path? =
        runCatching {
            val root = siteDirectory.toRealPath()
            val unresolved = root.resolve(relativePath).normalize()
            val candidate = if (Files.isDirectory(unresolved)) unresolved.resolve(INDEX_FILE) else unresolved
            candidate
                .takeIf(Files::isRegularFile)
                ?.toRealPath()
                ?.takeIf { file -> file.startsWith(root) }
        }.getOrNull()

    private fun respondFile(
        exchange: HttpExchange,
        file: Path,
    ) {
        val size = Files.size(file)
        exchange.responseHeaders.set("Content-Type", mediaType(file))
        exchange.responseHeaders.set("Content-Length", size.toString())
        exchange.responseHeaders.set("Cache-Control", "no-cache")
        val entityTag = entityTag(file)
        exchange.responseHeaders.set("ETag", entityTag)
        securityHeaders(exchange)
        if (exchange.requestHeaders.getFirst("If-None-Match") == entityTag) {
            exchange.sendResponseHeaders(NOT_MODIFIED, NO_RESPONSE_BODY)
        } else if (exchange.requestMethod == "HEAD") {
            exchange.sendResponseHeaders(OK, NO_RESPONSE_BODY)
        } else {
            exchange.sendResponseHeaders(OK, size)
            Files.newInputStream(file).use { input ->
                exchange.responseBody.use(input::transferTo)
            }
        }
    }

    private fun entityTag(file: Path): String {
        val attributes = Files.readAttributes(file, BasicFileAttributes::class.java)
        val identity = "${attributes.fileKey()}:${attributes.size()}:${attributes.lastModifiedTime().toMillis()}"
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.encodeToByteArray())
        return digest.take(ENTITY_TAG_BYTES).joinToString(prefix = "\"", postfix = "\"", separator = "") { byte ->
            "%02x".format(byte)
        }
    }

    private fun respondText(
        exchange: HttpExchange,
        status: Int,
        content: String,
    ) {
        val bytes = content.encodeToByteArray()
        exchange.responseHeaders.set("Content-Type", "text/plain; charset=utf-8")
        securityHeaders(exchange)
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        if (exchange.requestMethod != "HEAD") exchange.responseBody.use { output -> output.write(bytes) }
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

    private data class StaticRoute(
        val workspaceId: String,
        val relativePath: String,
        val redirectToSlash: Boolean,
    )

    private companion object {
        const val OK: Int = 200
        const val NOT_MODIFIED: Int = 304
        const val TEMPORARY_REDIRECT: Int = 307
        const val BAD_REQUEST: Int = 400
        const val NOT_FOUND: Int = 404
        const val METHOD_NOT_ALLOWED: Int = 405
        const val NO_RESPONSE_BODY: Long = -1
        const val ENTITY_TAG_BYTES: Int = 12
    }
}
