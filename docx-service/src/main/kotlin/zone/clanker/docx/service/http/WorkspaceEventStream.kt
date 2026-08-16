package zone.clanker.docx.service.http

import com.sun.net.httpserver.HttpExchange
import zone.clanker.docx.service.workspace.EventEnvelope
import zone.clanker.docx.service.workspace.WorkspaceEventHub
import zone.clanker.docx.service.workspace.WorkspaceEventSubscription
import zone.clanker.docx.service.workspace.WorkspaceRegistry
import zone.clanker.report.model.WorkspaceEvent
import zone.clanker.report.model.WorkspaceServiceJson
import java.io.Writer
import java.nio.charset.StandardCharsets

internal class WorkspaceEventStream(
    private val registry: WorkspaceRegistry,
    private val events: WorkspaceEventHub,
) {
    fun respond(exchange: HttpExchange) {
        val subscription = events.subscribe()
        if (subscription == null) {
            respondAtCapacity(exchange)
        } else {
            respondStream(exchange, subscription)
        }
    }

    private fun respondStream(
        exchange: HttpExchange,
        subscription: WorkspaceEventSubscription,
    ) {
        exchange.responseHeaders.set("Content-Type", "text/event-stream; charset=utf-8")
        exchange.responseHeaders.set("Cache-Control", "no-store")
        exchange.responseHeaders.set("Connection", "keep-alive")
        securityHeaders(exchange)
        exchange.sendResponseHeaders(OK, CHUNKED_RESPONSE)
        subscription.use { active -> stream(exchange, active) }
    }

    private fun respondAtCapacity(exchange: HttpExchange) {
        val bytes = "Event subscriber capacity reached.\n".encodeToByteArray()
        exchange.responseHeaders.set("Content-Type", "text/plain; charset=utf-8")
        exchange.responseHeaders.set("Retry-After", "1")
        securityHeaders(exchange)
        exchange.sendResponseHeaders(SERVICE_UNAVAILABLE, bytes.size.toLong())
        exchange.responseBody.use { output -> output.write(bytes) }
    }

    private fun stream(
        exchange: HttpExchange,
        subscription: WorkspaceEventSubscription,
    ) {
        exchange.responseBody.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
            registry.snapshotEvents().forEach { event -> writeEvent(writer, event) }
            while (writeEnvelope(writer, subscription.next(HEARTBEAT_SECONDS))) Unit
        }
    }

    private fun writeEnvelope(
        writer: Writer,
        envelope: EventEnvelope?,
    ): Boolean =
        when (envelope) {
            is EventEnvelope.Event -> {
                writeEvent(writer, envelope.value)
                true
            }
            EventEnvelope.Closed -> false
            null -> {
                writer.write(": keep-alive\n\n")
                writer.flush()
                true
            }
        }

    private fun writeEvent(
        writer: Writer,
        event: WorkspaceEvent,
    ) {
        writer.append("id: ").append(event.sequence.toString()).append('\n')
        writer.append("event: workspace\n")
        writer.append("data: ").append(WorkspaceServiceJson.encodeEvent(event)).append("\n\n")
        writer.flush()
    }

    private companion object {
        const val OK: Int = 200
        const val SERVICE_UNAVAILABLE: Int = 503
        const val HEARTBEAT_SECONDS: Long = 15
        const val CHUNKED_RESPONSE: Long = 0
    }
}
