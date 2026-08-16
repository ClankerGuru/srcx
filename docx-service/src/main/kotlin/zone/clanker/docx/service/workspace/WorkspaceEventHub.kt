package zone.clanker.docx.service.workspace

import zone.clanker.report.model.WorkspaceEvent
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal class WorkspaceEventHub(
    private val maxSubscribers: Int,
) : AutoCloseable {
    init {
        require(maxSubscribers > 0) { "Workspace event subscriber capacity must be positive." }
    }

    private val subscriptionLock = Any()
    private val closed = AtomicBoolean()
    private val nextSubscription = AtomicLong()
    private val subscribers = ConcurrentHashMap<Long, LinkedBlockingQueue<EventEnvelope>>()

    fun publish(event: WorkspaceEvent) {
        if (!closed.get()) {
            subscribers.values.forEach { queue ->
                if (!queue.offer(EventEnvelope.Event(event))) {
                    queue.poll()
                    queue.offer(EventEnvelope.Event(event))
                }
            }
        }
    }

    fun subscribe(): WorkspaceEventSubscription? =
        synchronized(subscriptionLock) {
            if (closed.get() || subscribers.size >= maxSubscribers) {
                null
            } else {
                val id = nextSubscription.incrementAndGet()
                val queue = LinkedBlockingQueue<EventEnvelope>(SUBSCRIBER_CAPACITY)
                subscribers[id] = queue
                WorkspaceEventSubscription(queue) { synchronized(subscriptionLock) { subscribers.remove(id) } }
            }
        }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            val closing =
                synchronized(subscriptionLock) {
                    subscribers.values.toList().also { subscribers.clear() }
                }
            closing.forEach { queue ->
                if (!queue.offer(EventEnvelope.Closed)) {
                    queue.poll()
                    queue.offer(EventEnvelope.Closed)
                }
            }
        }
    }

    private companion object {
        const val SUBSCRIBER_CAPACITY: Int = 128
    }
}

internal class WorkspaceEventSubscription(
    private val queue: LinkedBlockingQueue<EventEnvelope>,
    private val unsubscribe: () -> Unit,
) : AutoCloseable {
    fun next(timeoutSeconds: Long): EventEnvelope? = queue.poll(timeoutSeconds, TimeUnit.SECONDS)

    override fun close() = unsubscribe()
}

internal sealed interface EventEnvelope {
    data class Event(
        val value: WorkspaceEvent,
    ) : EventEnvelope

    data object Closed : EventEnvelope
}
