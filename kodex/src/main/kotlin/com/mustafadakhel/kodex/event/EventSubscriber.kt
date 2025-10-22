package com.mustafadakhel.kodex.event

import kotlin.reflect.KClass

/**
 * Interface for extensions that want to receive events.
 * Subscribers are notified asynchronously when events are published.
 *
 * Only registered extensions can subscribe to events for security.
 */
public interface EventSubscriber<T : KodexEvent> {

    /**
     * The event type this subscriber handles.
     * Used for routing events to the correct subscribers.
     */
    public val eventType: KClass<out T>

    /**
     * Called when an event of the subscribed type is published.
     *
     * This method runs in an isolated coroutine - exceptions thrown here
     * will be caught and logged but won't affect other subscribers or the publisher.
     *
     * @param event The event to process
     */
    public suspend fun onEvent(event: T)

    /**
     * Priority for ordering multiple subscribers (higher = earlier).
     * Subscribers with higher priority are notified first.
     *
     * Default: 0
     */
    public val priority: Int get() = 0
}
