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
 *
 * Events are queued in an unbounded channel and processed asynchronously.
 * Each subscriber runs in an isolated coroutine with error handling.
 */
internal class DefaultEventBus(
    private val extensionRegistry: ExtensionRegistry
) : EventBus {

    private val logger = LoggerFactory.getLogger(DefaultEventBus::class.java)
    private val subscribers = ConcurrentHashMap<KClass<*>, MutableList<EventSubscriber<*>>>()
    private val eventQueue = Channel<KodexEvent>(Channel.UNLIMITED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Security: Track subscribers from registered extensions
    private val allowedSubscribers = ConcurrentHashMap.newKeySet<EventSubscriber<*>>()

    init {
        // Collect all subscribers from registered extensions
        val extensionSubscribers = extensionRegistry.getAllOfType(EventSubscriberProvider::class)
            .flatMap { provider -> provider.getEventSubscribers() }

        // Add them to the allowed set
        allowedSubscribers.addAll(extensionSubscribers)

        // Register all allowed subscribers
        extensionSubscribers.forEach { subscriber ->
            subscribeInternal(subscriber)
        }

        // Start event processing coroutine loop
        scope.launch {
            for (event in eventQueue) {
                processEvent(event)
            }
        }
    }

    override suspend fun publish(event: KodexEvent) {
        // Non-blocking send to queue
        eventQueue.send(event)
    }

    override fun <T : KodexEvent> subscribe(subscriber: EventSubscriber<T>) {
        // Security validation: ensure subscriber comes from a registered extension
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

        // Sort by priority (higher priority first)
        subscriberList.sortByDescending { it.priority }
    }

    override fun <T : KodexEvent> unsubscribe(subscriber: EventSubscriber<T>) {
        subscribers[subscriber.eventType]?.remove(subscriber)
    }

    private suspend fun processEvent(event: KodexEvent) {
        val eventClass = event::class

        // Find all subscribers for this event type and parent type
        val subscribersForEvent = findSubscribersFor(eventClass)

        subscribersForEvent.forEach { subscriber ->
            // Each subscriber runs in isolated coroutine
            scope.launch {
                try {
                    @Suppress("UNCHECKED_CAST")
                    (subscriber as EventSubscriber<KodexEvent>).onEvent(event)
                } catch (e: Exception) {
                    // Log error but don't propagate
                    // This ensures one subscriber failure doesn't affect others
                    logger.error(
                        "Subscriber ${subscriber::class.simpleName} failed processing event " +
                        "${event.eventType} (${event.eventId})",
                        e
                    )
                }
            }
        }
    }

    /**
     * Find all subscribers that should receive this event.
     * Includes subscribers for the exact type and for KodexEvent (all events).
     */
    private fun findSubscribersFor(eventClass: KClass<out KodexEvent>): List<EventSubscriber<*>> {
        val result = mutableListOf<EventSubscriber<*>>()

        // Add subscribers for exact type
        subscribers[eventClass]?.let { result.addAll(it) }

        // Add subscribers for KodexEvent (subscribed to all events)
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
