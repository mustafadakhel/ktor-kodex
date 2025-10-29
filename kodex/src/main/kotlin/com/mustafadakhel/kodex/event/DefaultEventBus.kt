package com.mustafadakhel.kodex.event

import com.mustafadakhel.kodex.extension.ExtensionRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
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

    private val subscribers = ConcurrentHashMap<KClass<*>, MutableList<EventSubscriber<*>>>()
    private val eventQueue = Channel<KodexEvent>(Channel.UNLIMITED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        // Start event processing coroutine
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
        // TODO: Add security validation - ensure subscriber is from registered extension
        // For now, we trust that only extensions call this method

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

        // Find all subscribers for this event type and parent types
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
                    System.err.println(
                        "[EventBus] Subscriber ${subscriber::class.simpleName} " +
                        "failed processing event ${event.eventType} (${event.eventId}): ${e.message}"
                    )
                    e.printStackTrace(System.err)
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
}
