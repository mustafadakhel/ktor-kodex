package com.mustafadakhel.kodex.event

import com.mustafadakhel.kodex.extension.EventSubscriberProvider
import com.mustafadakhel.kodex.extension.ExtensionRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

/**
 * Default implementation of EventBus using coroutines and channels.
 */
internal class DefaultEventBus() : EventBus {

    private val logger = LoggerFactory.getLogger(DefaultEventBus::class.java)
    private val subscribers = ConcurrentHashMap<KClass<*>, MutableList<EventSubscriber<*>>>()
    private val eventQueue = Channel<KodexEvent>(Channel.UNLIMITED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
        eventQueue.send(event)
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
        val subscriberList = subscribers.computeIfAbsent(subscriber.eventType) {
            mutableListOf()
        }

        subscriberList.add(subscriber)

        subscriberList.sortByDescending { it.priority }
    }

    override fun <T : KodexEvent> unsubscribe(subscriber: EventSubscriber<T>) {
        subscribers[subscriber.eventType]?.remove(subscriber)
    }

    private suspend fun processEvent(event: KodexEvent) {
        val eventClass = event::class

        val subscribersForEvent = findSubscribersFor(eventClass)

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

        subscribers[eventClass]?.let { result.addAll(it) }

        if (eventClass != KodexEvent::class) {
            subscribers[KodexEvent::class]?.let { result.addAll(it) }
        }

        return result.distinctBy { it }
    }

    override fun shutdown() {
        scope.cancel()
        eventQueue.close()
    }
}
