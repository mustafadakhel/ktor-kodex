package com.mustafadakhel.kodex.event

import com.mustafadakhel.kodex.extension.EventSubscriberProvider
import com.mustafadakhel.kodex.extension.ExtensionRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.reflect.KClass
import kotlin.reflect.full.superclasses

internal class DefaultEventBus() : EventBus {

    private val logger = LoggerFactory.getLogger(DefaultEventBus::class.java)
    private val subscribers = ConcurrentHashMap<KClass<*>, CopyOnWriteArrayList<EventSubscriber<*>>>()
    private val eventQueue = Channel<KodexEvent>(capacity = 1024, onBufferOverflow = BufferOverflow.SUSPEND)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val allowedSubscribers = ConcurrentHashMap.newKeySet<EventSubscriber<*>>()

    init {
        scope.launch {
            for (event in eventQueue) {
                processEvent(event)
            }
        }
    }

    internal fun registerExtensionSubscribers(extensionRegistry: ExtensionRegistry) {
        val extensionSubscribers = extensionRegistry.getAllOfType(EventSubscriberProvider::class)
            .flatMap { provider -> provider.getEventSubscribers() }

        allowedSubscribers.addAll(extensionSubscribers)

        extensionSubscribers.forEach { subscriber ->
            subscribeInternal(subscriber)
        }
    }

    override suspend fun publish(event: KodexEvent) {
        val result = eventQueue.trySend(event)
        if (result.isFailure && !result.isClosed) {
            logger.warn("Event queue full, applying backpressure for event: {}", event::class.simpleName)
            eventQueue.send(event)
        }
    }

    override fun <T : KodexEvent> subscribe(subscriber: EventSubscriber<T>) {
        if (subscriber !in allowedSubscribers) {
            throw IllegalArgumentException(
                "Subscriber ${subscriber::class.qualifiedName} is not from a registered extension. " +
                "Only subscribers provided by registered extensions can subscribe to events."
            )
        }

        subscribeInternal(subscriber)
    }

    private fun <T : KodexEvent> subscribeInternal(subscriber: EventSubscriber<T>) {
        subscribers.compute(subscriber.eventType) { _, existing ->
            val list = existing ?: CopyOnWriteArrayList()
            list.add(subscriber)
            CopyOnWriteArrayList(list.sortedByDescending { it.priority })
        }
    }

    override fun <T : KodexEvent> unsubscribe(subscriber: EventSubscriber<T>) {
        subscribers[subscriber.eventType]?.remove(subscriber)
    }

    private suspend fun processEvent(event: KodexEvent) {
        val subscribersForEvent = findSubscribersFor(event::class)

        subscribersForEvent.forEach { subscriber ->
            scope.launch {
                try {
                    @Suppress("UNCHECKED_CAST")
                    (subscriber as EventSubscriber<KodexEvent>).onEvent(event)
                } catch (e: Exception) {
                    logger.error(
                        "Subscriber ${subscriber::class.simpleName} failed processing event " +
                        "${event.eventType} (${event.eventId})",
                        e
                    )
                }
            }
        }
    }

    private fun findSubscribersFor(eventClass: KClass<out KodexEvent>): List<EventSubscriber<*>> {
        val result = mutableListOf<EventSubscriber<*>>()
        val visited = mutableSetOf<KClass<*>>()

        fun collectFrom(cls: KClass<*>) {
            if (!visited.add(cls)) return
            subscribers[cls]?.let { result.addAll(it) }
            for (superclass in cls.superclasses) {
                if (KodexEvent::class.java.isAssignableFrom(superclass.java)) {
                    collectFrom(superclass)
                }
            }
        }

        collectFrom(eventClass)
        return result.distinctBy { it }
    }

    override fun shutdown() {
        eventQueue.close()
        runBlocking {
            withTimeoutOrNull(5000) {
                scope.coroutineContext[Job]?.children?.forEach { it.join() }
            }
        }
        scope.cancel()
    }
}
